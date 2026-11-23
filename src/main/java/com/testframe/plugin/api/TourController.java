package com.testframe.plugin.api;

import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.engine.RunControl;
import com.testframe.plugin.format.Names;
import com.testframe.plugin.format.ProjectDir;
import com.testframe.plugin.format.SourcesDecoder;
import com.testframe.plugin.project.ProblemException;
import com.testframe.plugin.project.ProjectRuns;
import com.testframe.plugin.project.ProjectsRoot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stateless tour API (agents/specs/test-tour.md), base {@code /api/v1/tour}. It reads the project
 * with the read-only decoders, resolves the sources in the environment (private layers included),
 * redacts every secret before anything leaves the plugin, and never writes to the project. The
 * plugin keeps no tour: each run is identified only by the client's {@code runId} for cancel.
 */
@RestController
@RequestMapping("/api/v1/tour")
public class TourController {

    public record TourRunRequest(String runId, String environment, Map<String, String> inputs) {
    }

    private final ProjectsRoot projects;
    private final ProjectRuns runs;
    private final RunSlots slots;
    private final RunRegistry registry;

    public TourController(ProjectsRoot projects, ProjectRuns runs, RunSlots slots, RunRegistry registry) {
        this.projects = projects;
        this.runs = runs;
        this.slots = slots;
        this.registry = registry;
    }

    @GetMapping("/projects")
    public void projects(HttpServletRequest request, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String name : projects.projects()) {
            ProjectDir dir = projects.project(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("frames", dir.frameNames());
            list.add(entry);
        }
        ApiResponses.json(response, 200, Map.of("projects", list), ApiResponses.redactor(request));
    }

    @GetMapping("/projects/{project}/environments")
    public void environments(@PathVariable String project, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProjectDir dir = projects.project(project);
        SourcesDecoder.Sources sources = runs.sources(dir);
        List<Map<String, Object>> environments = new ArrayList<>();
        for (String env : sources.environments()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", env);
            entry.put("sources", sources.valid() ? configuredRefs(sources, env) : List.of());
            environments.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environments", environments);
        body.put("errors", sources.valid() ? List.of() : sources.errors());
        ApiResponses.json(response, 200, body, ApiResponses.redactor(request));
    }

    @GetMapping("/projects/{project}/frames")
    public void frames(@PathVariable String project, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ProjectDir dir = projects.project(project);
        List<Map<String, Object>> frames = new ArrayList<>();
        for (String name : dir.frameNames()) {
            ProjectRuns.LoadedFrame loaded = runs.frame(dir, name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("steps", loaded.frame().steps().size());
            entry.put("cards", loaded.frame().cards().size());
            entry.put("inputs", loaded.inputs().size());
            entry.put("sources", loaded.sourceRefs());
            entry.put("errors", loaded.frame().errors());
            frames.add(entry);
        }
        ApiResponses.json(response, 200, Map.of("frames", frames), ApiResponses.redactor(request));
    }

    @GetMapping("/projects/{project}/frames/{frame}/inputs")
    public void inputs(@PathVariable String project, @PathVariable String frame, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
        ProjectDir dir = projects.project(project);
        ProjectRuns.LoadedFrame loaded = runs.frame(dir, frame);
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (ProjectRuns.InputCard input : loaded.inputs()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", input.step());
            entry.put("varName", input.varName());
            entry.put("title", input.title());
            entry.put("value", input.code() == null ? "" : input.code());
            inputs.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("frame", frame);
        body.put("inputs", inputs);
        ApiResponses.json(response, 200, body, ApiResponses.redactor(request));
    }

    @PostMapping("/projects/{project}/frames/{frame}/run")
    public void run(@PathVariable String project, @PathVariable String frame, @RequestBody TourRunRequest body,
                    HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (body == null || Names.isBlank(body.runId())) {
            throw new ProblemException(400, "runId is required");
        }
        if (Names.isBlank(body.environment())) {
            throw new ProblemException(400, "environment is required");
        }
        ProjectDir dir = projects.project(project);
        SourcesDecoder.Sources sources = runs.sources(dir);
        ProjectRuns.Prepared prepared = runs.prepare(dir, sources, frame, body.environment(),
                body.inputs() == null ? Map.of() : body.inputs());
        Redactor redactor = prepared.redactor();
        request.setAttribute(ApiResponses.REDACTOR, redactor);

        slots.acquire();
        try {
            RunControl control = registry.register(body.runId());
            try {
                Ndjson ndjson = new Ndjson(response, redactor, control);
                runs.run(prepared, body.runId(), control, ndjson::accept);
            } finally {
                registry.remove(body.runId());
            }
        } finally {
            slots.release();
        }
    }

    @PostMapping("/runs/{runId}/cancel")
    public void cancel(@PathVariable String runId, HttpServletResponse response) {
        if (registry.cancel(runId)) {
            response.setStatus(202);
            return;
        }
        throw new ProblemException(404, "no such run in progress: " + runId);
    }

    private static List<String> configuredRefs(SourcesDecoder.Sources sources, String env) {
        SourcesDecoder.Resolution resolution = SourcesDecoder.resolve(sources, env);
        List<String> refs = new ArrayList<>();
        resolution.sources().forEach((ref, source) -> {
            if (source.configured()) {
                refs.add(ref);
            }
        });
        return refs;
    }
}