package com.testframe.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Name, version and protocol of the plugin ({@code GET /api/v1/health}, run events, CLI reports). */
public final class PluginInfo {

    public static final String NAME = "testframe-plugin-spring";
    /** The runner protocol version (docs/runner-protocol.md, "Versioning"). */
    public static final int PROTOCOL = 1;
    public static final String VERSION = loadVersion();

    private PluginInfo() {
    }

    private static String loadVersion() {
        try (InputStream in = PluginInfo.class.getResourceAsStream("/plugin.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "");
                if (!v.isBlank() && !v.startsWith("@")) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "dev";
    }
}
