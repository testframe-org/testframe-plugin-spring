package com.testframe.plugin.engine;

import com.testframe.plugin.Json;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.Operation;
import com.testframe.plugin.model.SourceConnection;
import com.testframe.plugin.model.Step;
import io.github.jsonassert.JsonataAssertionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three ways a run is stopped from the outside (agents/specs/runner-protocol.md, "Timeouts and
 * cancellation"): the card timeout, the run deadline and a cancel. Each one must produce a result
 * the caller can act on — a card {@code error} for the first two, and an abandoned card plus
 * {@code stopped: cancelled} for the last — and must never leave a worker running.
 *
 * <p>The card is an openapi request whose caller blocks far longer than the card timeout and
 * ignores it, which is what makes the watchdog (not the transport) the thing under test.
 */
class RunEngineControlTest {

    private static final int CARD_TIMEOUT_MS = 200;

    private static final Card SLOW = new Card("getSlow", "Get slow", "openapi", "slow-api", "restapi",
            null, "GET /slow", "slow", CARD_TIMEOUT_MS, "");
    private static final Card NEXT = new Card("getNext", "Get next", "openapi", "slow-api", "restapi",
            null, "GET /slow", "slow", CARD_TIMEOUT_MS, "");

    private MongoRunner mongo;
    private BlockingCaller http;
    private RunEngine engine;

    @BeforeEach
    void start() {
        mongo = new MongoRunner();
        http = new BlockingCaller();
        engine = new RunEngine(mongo, http, new JsonataAssertionService(), Json.MAPPER);
    }

    @AfterEach
    void stop() {
        http.release();
        http.close();
        mongo.close();
    }

    private static List<Step> frame(Card... cards) {
        return List.of(new Step(1, List.of(cards)));
    }

    private static RunEngine.RunContext context() {
        return new RunEngine.RunContext("local",
                Map.of("openapi/slow-api", SourceConnection.openapi("http://127.0.0.1:1", Map.of(),
                        List.of(new Operation("slow", "GET", "/slow", "slow")))),
                Map.of(), Map.of());
    }

    // -------------------------------------------------------------------- card timeout

    @Test
    void aCardThatOutlivesItsTimeoutIsAbortedAsAnError() throws Exception {
        RunEngine.FrameOutcome outcome = engine.runFrame(frame(SLOW), context(), RunControl.withDeadline(null),
                RunListener.NONE);

        assertEquals(RunEngine.STOP_ERROR, outcome.stopped());
        CardResult result = outcome.results().getFirst();
        assertEquals(CardResult.ERROR, result.status());
        assertEquals("timed out after " + CARD_TIMEOUT_MS + "ms", result.output());
        assertTrue(result.text().startsWith("[Step 1] Get slow: timed out after " + CARD_TIMEOUT_MS + "ms"),
                result.text());
        assertEquals(1, http.started.get(), "the card ran once");
        assertTrue(http.awaitInterrupted(), "the abandoned worker was interrupted");
    }

    @Test
    void theRunStopsAtTheFirstTimeoutSoTheNextCardNeverRuns() {
        RunEngine.FrameOutcome outcome = engine.runFrame(frame(SLOW, NEXT), context(), RunControl.withDeadline(null),
                RunListener.NONE);

        assertEquals(1, outcome.results().size(), "the card after the error is not returned");
        assertEquals(1, http.started.get());
    }

    // -------------------------------------------------------------------- run deadline

    @Test
    void theDeadlineAbortsTheCardInProgress() {
        RunEngine.FrameOutcome outcome = engine.runFrame(frame(SLOW), context(), RunControl.withDeadline(300L),
                RunListener.NONE);

        assertEquals(RunEngine.STOP_DEADLINE, outcome.stopped());
        CardResult result = outcome.results().getFirst();
        assertEquals(CardResult.ERROR, result.status());
        assertEquals("run deadline exceeded", result.output());
    }

    @Test
    void aDeadlineAlreadyReachedStopsTheRunBeforeTheCardStarts() throws InterruptedException {
        RunControl control = RunControl.withDeadline(1L);
        Thread.sleep(20);

        RunEngine.FrameOutcome outcome = engine.runFrame(frame(SLOW, NEXT), context(), control, RunListener.NONE);

        assertEquals(RunEngine.STOP_DEADLINE, outcome.stopped());
        assertEquals(1, outcome.results().size());
        assertEquals("run deadline exceeded", outcome.results().getFirst().output());
        assertEquals(0, http.started.get(), "no card was started");
    }

    // --------------------------------------------------------------------------- cancel

    @Test
    void aCancelBeforeTheRunRunsNothing() {
        RunControl control = RunControl.withDeadline(null);
        control.cancel();

        RunEngine.FrameOutcome outcome = engine.runFrame(frame(SLOW, NEXT), context(), control, RunListener.NONE);

        assertEquals(RunEngine.STOP_CANCELLED, outcome.stopped());
        assertTrue(outcome.results().isEmpty());
        assertEquals(0, http.started.get());
    }

    @Test
    void aCancelDuringACardAbandonsItAndStopsTheRun() throws Exception {
        RunControl control = RunControl.withDeadline(null);
        ExecutorService main = Executors.newSingleThreadExecutor();
        try {
            Future<RunEngine.FrameOutcome> future = main.submit(
                    () -> engine.runFrame(frame(SLOW, NEXT), context(), control, RunListener.NONE));
            assertTrue(http.entered.await(30, TimeUnit.SECONDS), "the card started");

            control.cancel();

            RunEngine.FrameOutcome outcome = future.get(30, TimeUnit.SECONDS);
            assertEquals(RunEngine.STOP_CANCELLED, outcome.stopped());
            assertTrue(outcome.results().isEmpty(), "the abandoned card produces no result");
            assertEquals(1, http.started.get(), "the card after the cancelled one never ran");
            assertTrue(http.awaitInterrupted(), "the abandoned worker was interrupted");
        } finally {
            main.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------ RunControl

    @Test
    void theDeadlineIsClampedToTheMaximumRun() {
        assertTrue(RunControl.withDeadline(null).remainingMs() <= Limits.MAX_RUN_MS);
        assertTrue(RunControl.withDeadline(null).remainingMs() > Limits.MAX_RUN_MS - 30_000,
                "no deadline asked for means the maximum");
        assertTrue(RunControl.withDeadline(-1L).remainingMs() <= Limits.MAX_RUN_MS);
        assertTrue(RunControl.withDeadline(999_999_999L).remainingMs() <= Limits.MAX_RUN_MS,
                "a caller cannot ask for a run longer than the maximum");
        assertTrue(RunControl.withDeadline(1000L).remainingMs() <= 1000);
    }

    @Test
    void cancelInterruptsTheRegisteredCard() throws Exception {
        RunControl control = RunControl.withDeadline(null);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = workers.submit(() -> {
                entered.countDown();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            control.running(future);

            control.cancel();

            assertTrue(control.isCancelled());
            assertThrows(CancellationException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertTrue(interrupted.get());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void aCardRegisteredAfterTheCancelIsCancelledAtOnce() throws Exception {
        RunControl control = RunControl.withDeadline(null);
        control.cancel();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = workers.submit(() -> {
            });
            control.running(future);
            assertTrue(future.isCancelled() || future.isDone());
        } finally {
            workers.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------- the seam

    /**
     * A caller that blocks far past the card timeout and ignores it, so the watchdog — not the
     * transport — is what has to stop the card.
     */
    private static final class BlockingCaller extends HttpCaller {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interruptedLatch = new CountDownLatch(1);
        final AtomicInteger started = new AtomicInteger();
        final AtomicBoolean interrupted = new AtomicBoolean();
        volatile boolean released;

        @Override
        public Response call(String method, String url, Map<String, String> headers, String body, long timeoutMs) {
            started.incrementAndGet();
            entered.countDown();
            try {
                while (!released) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
                interruptedLatch.countDown();
                Thread.currentThread().interrupt();
            }
            return new Response(0, Map.of(), "", false, timeoutMs, "blocked");
        }

        /** Waits for the abandoned worker to see its interrupt (the watchdog only asks for it). */
        boolean awaitInterrupted() throws InterruptedException {
            return interruptedLatch.await(30, TimeUnit.SECONDS);
        }

        void release() {
            released = true;
        }
    }
}
