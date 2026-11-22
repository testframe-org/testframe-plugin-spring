package com.testframe.plugin.api;

import com.testframe.plugin.Json;
import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.engine.RunControl;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * An NDJSON stream (one JSON object per line, flushed per event). Every event is redacted right
 * before it is written. When the client goes away the run is cancelled (it stops after the card
 * in progress, whose result is discarded).
 */
final class Ndjson implements Consumer<Object> {

    private final OutputStream out;
    private final Redactor redactor;
    private final RunControl control;
    private volatile boolean broken;

    Ndjson(HttpServletResponse response, Redactor redactor, RunControl control) throws IOException {
        response.setStatus(200);
        response.setContentType(ApiResponses.NDJSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        this.out = response.getOutputStream();
        this.redactor = redactor;
        this.control = control;
    }

    @Override
    public synchronized void accept(Object event) {
        if (broken) {
            return;
        }
        try {
            out.write(Json.MAPPER.writeValueAsBytes(redactor.tree(event, Json.MAPPER)));
            out.write('\n');
            out.flush();
        } catch (IOException | RuntimeException e) {
            broken = true;
            control.cancel();
        }
    }
}
