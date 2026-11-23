package com.testframe.plugin.cli;

import com.testframe.plugin.Json;
import com.testframe.plugin.PluginInfo;
import com.testframe.plugin.engine.HttpCaller;
import com.testframe.plugin.engine.MongoRunner;
import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.engine.RunControl;
import com.testframe.plugin.engine.RunEngine;
import com.testframe.plugin.format.ProjectDir;
import com.testframe.plugin.format.SourcesDecoder;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.Step;
import com.testframe.plugin.project.ProblemException;
import com.testframe.plugin.project.ProjectRuns;
import io.github.jsonassert.JsonataAssertionService;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The headless CLI (agents/specs/test-tour.md, "Headless CLI"): {@code run <projectDir> --env <env>
 * [--frames a,b] [--out file.json|file.xml]}. It reads the project with the read-only decoders,
 * resolves the sources in the environment, runs the frames and prints each card's text plus a
 * line per frame result. With {@code --out} it also writes a redacted JSON report or JUnit XML
 * (one {@code <testsuite>} per frame, one {@code <testcase>} per card). No web server starts and
 * nothing is written to the project. Exit codes: 0 passed, 1 failed, 2 bad invocation or project,
 * 3 interrupted (SIGINT/SIGTERM), 4 internal error.
 *
 * <p>On SIGINT/SIGTERM the shutdown hook cancels the frame in progress, waits up to
 * {@link #REPORT_GRACE_MS} for the main thread to write the reports (with what ran; the frames not
 * started are {@code not-run}) and halts the JVM with exit code 3. Every line printed to stdout or
 * stderr passes through the redactor.
 */
public final class Cli {

    public static final String COMMAND = "run";

    /** A frame the run never started (interrupted before it). */
    public static final String NOT_RUN = "not-run";

    /** How long the shutdown hook waits for the reports before halting. */
    static final long REPORT_GRACE_MS = 5_000;

    /**
     * The interruption of a run by SIGINT/SIGTERM, shared by the main thread and the shutdown hook.
     * {@link #interrupt()} cancels the frame in progress and, while frames are running, interrupts
     * the main thread (an OpenAPI spec fetch is abandoned too); once the main thread reports, it is
     * never interrupted again, so the report files are written whole.
     */
    static final class Interruption {
        private final Thread main;
        private final CountDownLatch done = new CountDownLatch(1);
        private boolean requested;
        private boolean reporting;
        private RunControl active;

        Interruption(Thread main) {
            this.main = main;
        }

        /** SIGINT/SIGTERM: stop the run (idempotent). */
        synchronized void interrupt() {
            requested = true;
            if (active != null) {
                active.cancel();
            }
            if (!reporting) {
                main.interrupt();
            }
        }

        synchronized boolean requested() {
            return requested;
        }

        /** The frame about to run; cancelled at once when an interruption already arrived. */
        synchronized void running(RunControl control) {
            active = control;
            if (requested && control != null) {
                control.cancel();
            }
        }

        /** Called by the main thread before it writes the reports: no more thread interrupts. */
        synchronized void reporting() {
            reporting = true;
            active = null;
            Thread.interrupted(); // clears a pending interrupt so the report files can be written
        }

        /** The reports are written (or there were none to write). */
        void finished() {
            done.countDown();
        }

        boolean awaitFinished(long ms) {
            try {
                return done.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Test seam: called with the run's interruption before the first frame starts. */
    Consumer<Interruption> onInterruption = i -> { };
    /** Test seam: false runs without registering the JVM shutdown hook. */
    boolean installShutdownHook = true;
    /** What stderr lines pass through: structural rules first, the environment's secrets once known. */
    private Redactor errRedactor = Redactor.structural();

    /** One parsed invocation. */
    record Invocation(Path projectDir, String env, List<String> frames, List<Path> outputs) {
    }

    /** One run frame kept for the reports. */
    private record FrameRun(ProjectRuns.Prepared prepared, ProjectRuns.FrameSummary summary) {
    }

    public static int main(String[] args, PrintStream out, PrintStream err) {
        return new Cli().run(args == null ? new String[0] : args, out, err);
    }

    int run(String[] args, PrintStream out, PrintStream err) {
        Invocation invocation = parse(args, err);
        if (invocation == null) {
            return 2;
        }
        MongoRunner mongo = new MongoRunner();
        try {
            return execute(invocation, out, err, mongo);
        } catch (RuntimeException e) {
            printErr(err, "run: internal error: " + oneLine(e));
            return 4;
        } finally {
            mongo.close();
        }
    }

    // ---------------------------------------------------------------- parsing

    private Invocation parse(String[] args, PrintStream err) {
        Path projectDir = null;
        String env = null;
        List<String> frames = null;
        List<Path> outputs = new ArrayList<>();
        int i = 1; // args[0] is "run"
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--env" -> {
                    if (++i >= args.length) {
                        printErr(err, "run: --env needs a value");
                        return null;
                    }
                    env = args[i];
                }
                case "--frames" -> {
                    if (++i >= args.length) {
                        printErr(err, "run: --frames needs a comma-separated list of frame names");
                        return null;
                    }
                    List<String> names = new ArrayList<>();
                    for (String name : args[i].split(",")) {
                        String trimmed = name.trim();
                        if (!trimmed.isEmpty()) {
                            names.add(trimmed);
                        }
                    }
                    if (names.isEmpty()) {
                        printErr(err, "run: --frames needs a comma-separated list of frame names");
                        return null;
                    }
                    frames = names;
                }
                case "--out" -> {
                    if (++i >= args.length) {
                        printErr(err, "run: --out needs a file (.json or .xml)");
                        return null;
                    }
                    Path path = Path.of(args[i]);
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!name.endsWith(".json") && !name.endsWith(".xml")) {
                        printErr(err, "run: --out format follows the extension (.json or .xml): " + args[i]);
                        return null;
                    }
                    outputs.add(path);
                }
                default -> {
                    if (arg.startsWith("--")) {
                        printErr(err, "run: unknown option " + arg);
                        return null;
                    }
                    if (projectDir != null) {
                        printErr(err, "run: unexpected argument " + arg);
                        return null;
                    }
                    projectDir = Path.of(arg);
                }
            }
            i++;
        }
        if (projectDir == null) {
            usage(err);
            return null;
        }
        if (env == null || env.isBlank()) {
            printErr(err, "run: --env is required");
            return null;
        }
        return new Invocation(projectDir, env, frames, List.copyOf(outputs));
    }

    private static void usage(PrintStream err) {
        err.println("usage: java -jar testframe-plugin-spring.jar run <projectDir> --env <env> [--frames a,b] [--out file.json|file.xml]");
    }

    // --------------------------------------------------------------- execution

    private int execute(Invocation inv, PrintStream out, PrintStream err, MongoRunner mongo) {
        ProjectRuns runs = new ProjectRuns(
                new RunEngine(mongo, new HttpCaller(), new JsonataAssertionService(), Json.MAPPER),
                ProjectRuns.httpFetcher(), Json.MAPPER);

        ProjectDir dir;
        try {
            dir = ProjectDir.at(inv.projectDir());
        } catch (IllegalArgumentException e) {
            printErr(err, "run: " + e.getMessage());
            return 2;
        }

        SourcesDecoder.Sources sources;
        try {
            sources = runs.sources(dir);
        } catch (IOException e) {
            printErr(err, "run: cannot read the sources files: " + oneLine(e));
            return 4;
        }
        if (!sources.valid()) {
            sources.errors().forEach(line -> printErr(err, line));
            return 2;
        }
        if (sources.hasEnvironment(inv.env())) {
            errRedactor = new Redactor(SourcesDecoder.resolve(sources, inv.env()).secrets());
        }

        List<String> frameNames;
        try {
            frameNames = inv.frames() != null ? inv.frames() : dir.frameNames();
        } catch (IOException e) {
            printErr(err, "run: cannot list the frames: " + oneLine(e));
            return 4;
        }

        List<FrameRun> frames = new ArrayList<>();
        List<ProjectRuns.Prepared> prepareds = new ArrayList<>();
        for (String name : frameNames) {
            try {
                ProjectRuns.Prepared prepared = runs.prepare(dir, sources, name, inv.env(), null);
                frames.add(new FrameRun(prepared, null));
                prepareds.add(prepared);
            } catch (ProblemException e) {
                e.problems().forEach(line -> printErr(err, line));
                return 2;
            } catch (IOException e) {
                printErr(err, "run: cannot read frame " + name + ": " + oneLine(e));
                return 4;
            }
        }

        Instant startedAt = Instant.now();
        Interruption interruption = new Interruption(Thread.currentThread());
        Thread hook = new Thread(() -> {
            interruption.interrupt();
            interruption.awaitFinished(REPORT_GRACE_MS);
            out.flush();
            err.flush();
            Runtime.getRuntime().halt(3);
        }, "testframe-cli-shutdown");
        if (installShutdownHook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }
        onInterruption.accept(interruption);

        try {
            List<FrameRun> completed = new ArrayList<>();
            String result;
            try {
                for (FrameRun fr : frames) {
                    if (interruption.requested()) {
                        break;
                    }
                    ProjectRuns.Prepared prepared = fr.prepared();
                    RunControl control = RunControl.withDeadline(null);
                    interruption.running(control);
                    Redactor redactor = prepared.redactor();
                    Consumer<Map<String, Object>> sink = event -> {
                        if ("card-finished".equals(event.get("event")) && event.get("result") instanceof CardResult card) {
                            out.println(redactor.apply(card.text()));
                        }
                    };
                    ProjectRuns.FrameSummary summary = runs.run(prepared, null, control, sink);
                    interruption.running(null);
                    completed.add(new FrameRun(prepared, summary));
                    out.println("frame " + summary.frame() + ": " + summary.status() + " " + counts(summary.counts()));
                    if (ProjectRuns.INTERRUPTED.equals(summary.status())) {
                        break;
                    }
                }
            } finally {
                interruption.reporting();
            }
            boolean interrupted = interruption.requested();
            boolean failed = false;
            for (FrameRun fr : completed) {
                if (ProjectRuns.INTERRUPTED.equals(fr.summary().status())) {
                    interrupted = true;
                } else if (ProjectRuns.FAILED.equals(fr.summary().status())) {
                    failed = true;
                }
            }
            for (FrameRun fr : frames.subList(completed.size(), frames.size())) {
                ProjectRuns.FrameSummary notRun = notRun(fr.prepared());
                completed.add(new FrameRun(fr.prepared(), notRun));
                out.println("frame " + notRun.frame() + ": " + NOT_RUN + " " + counts(notRun.counts()));
            }
            result = interrupted ? ProjectRuns.INTERRUPTED : failed ? ProjectRuns.FAILED : ProjectRuns.PASSED;

            writeReports(inv, dir.name(), result, startedAt, Instant.now(), completed, prepareds, err);
            return exitCode(result);
        } finally {
            out.flush();
            err.flush();
            interruption.finished();
            if (installShutdownHook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // shutting down: the hook halts with exit code 3 once it sees finished()
                }
            }
        }
    }

    /** A frame the run never started: every card {@code notRun}, no results. */
    private static ProjectRuns.FrameSummary notRun(ProjectRuns.Prepared prepared) {
        return new ProjectRuns.FrameSummary(prepared.frame(), NOT_RUN, null, 0,
                ProjectRuns.counts(List.of(), totalCards(prepared.steps())), List.of(), prepared.inputs(), Map.of());
    }

    private void printErr(PrintStream err, String line) {
        err.println(errRedactor.apply(line));
    }

    /** 0 passed, 1 failed, 3 interrupted (2 and 4 are returned by the caller). */
    static int exitCode(String result) {
        return switch (result) {
            case ProjectRuns.FAILED -> 1;
            case ProjectRuns.INTERRUPTED -> 3;
            default -> 0;
        };
    }

    private void writeReports(Invocation inv, String project, String result, Instant startedAt, Instant finishedAt,
                              List<FrameRun> completed, List<ProjectRuns.Prepared> prepareds, PrintStream err) {
        if (inv.outputs().isEmpty()) {
            return;
        }
        Redactor redactor = unionRedactor(prepareds);
        for (Path output : inv.outputs()) {
            try {
                String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".json")) {
                    Files.writeString(output, Json.MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(redactor.tree(jsonReport(project, inv.env(), result, startedAt, finishedAt, completed), Json.MAPPER)));
                } else {
                    Files.writeString(output, junitXml(completed, redactor));
                }
            } catch (IOException e) {
                err.println(redactor.apply("run: cannot write " + output + ": " + oneLine(e)));
            }
        }
    }

    private static Map<String, Object> jsonReport(String project, String environment, String result, Instant startedAt,
                                                  Instant finishedAt, List<FrameRun> completed) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("project", project);
        report.put("environment", environment);
        report.put("pluginVersion", PluginInfo.VERSION);
        report.put("startedAt", startedAt.toString());
        report.put("finishedAt", finishedAt.toString());
        report.put("result", result);
        List<Map<String, Object>> frames = new ArrayList<>();
        for (FrameRun fr : completed) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("frame", fr.summary().frame());
            frame.put("status", fr.summary().status());
            frame.put("inputs", fr.summary().inputs());
            frame.put("sources", fr.summary().sources());
            frame.put("results", fr.summary().results());
            frame.put("counts", fr.summary().counts());
            frame.put("elapsedMs", fr.summary().elapsedMs());
            frames.add(frame);
        }
        report.put("frames", frames);
        return report;
    }

    private String junitXml(List<FrameRun> completed, Redactor redactor) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
        for (FrameRun fr : completed) {
            ProjectRuns.FrameSummary summary = fr.summary();
            Map<String, Integer> stepOf = stepOf(fr.prepared().steps());
            Map<String, CardResult> byVar = new LinkedHashMap<>();
            summary.results().forEach(r -> byVar.put(r.varName(), r));
            Map<String, Integer> counts = summary.counts();
            int failures = counts.getOrDefault("error", 0);
            int skipped = counts.getOrDefault("skipped", 0) + counts.getOrDefault("notRun", 0);
            int tests = totalCards(fr.prepared().steps());
            double time = summary.elapsedMs() / 1000.0;

            xml.append("  <testsuite name=\"").append(attr(summary.frame())).append("\" tests=\"").append(tests)
                    .append("\" failures=\"").append(failures).append("\" skipped=\"").append(skipped)
                    .append("\" time=\"").append(seconds(time)).append("\">\n");
            for (Step step : fr.prepared().steps()) {
                for (Card card : step.cards()) {
                    String varName = card.varName() == null ? "" : card.varName();
                    CardResult result = byVar.get(varName);
                    Integer stepNumber = stepOf.get(varName);
                    xml.append("    <testcase classname=\"").append(attr(summary.frame()))
                            .append(".step-").append(stepNumber == null ? step.number() : stepNumber)
                            .append("\" name=\"").append(attr(varName))
                            .append("\" time=\"").append(seconds(result == null ? 0 : result.elapsedMs() / 1000.0))
                            .append("\">");
                    if (result != null && CardResult.ERROR.equals(result.status())) {
                        xml.append("<failure message=\"").append(attr(redactor.apply(result.text())))
                                .append("\">").append(escape(redactor.apply(result.output())))
                                .append("</failure>");
                    } else if (result == null || CardResult.SKIPPED.equals(result.status())) {
                        xml.append("<skipped/>");
                    }
                    xml.append("</testcase>\n");
                }
            }
            xml.append("  </testsuite>\n");
        }
        return xml.append("</testsuites>\n").toString();
    }

    private static Map<String, Integer> stepOf(List<Step> steps) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Step step : steps) {
            for (Card card : step.cards()) {
                if (card.varName() != null) {
                    map.put(card.varName(), step.number());
                }
            }
        }
        return map;
    }

    private static int totalCards(List<Step> steps) {
        int total = 0;
        for (Step step : steps) {
            total += step.cards().size();
        }
        return total;
    }

    private static Redactor unionRedactor(List<ProjectRuns.Prepared> prepareds) {
        Set<String> secrets = new LinkedHashSet<>();
        for (ProjectRuns.Prepared prepared : prepareds) {
            secrets.addAll(prepared.resolution().secrets());
        }
        return new Redactor(secrets);
    }

    private static String counts(Map<String, Integer> counts) {
        return "(success " + counts.getOrDefault("success", 0)
                + ", error " + counts.getOrDefault("error", 0)
                + ", skipped " + counts.getOrDefault("skipped", 0)
                + ", notRun " + counts.getOrDefault("notRun", 0) + ")";
    }

    private static String oneLine(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String attr(String text) {
        return escape(text).replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String seconds(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }
}