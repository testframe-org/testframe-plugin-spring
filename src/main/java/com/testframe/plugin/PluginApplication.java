package com.testframe.plugin;

import com.testframe.plugin.cli.Cli;
import com.testframe.plugin.cli.FormatDump;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. {@code run <projectDir> --env <env> ...} is the headless CLI (no web server, no
 * Spring context; agents/specs/test-tour.md, "Headless CLI") and {@code format-dump <projectDir>} prints
 * the decoders' view of a project for the repo-root format parity gate; anything else starts the
 * HTTP plugin (runner protocol {@code /api/v1}, tour API {@code /api/v1/tour}).
 */
@SpringBootApplication
public class PluginApplication {

    public static void main(String[] args) {
        switch (args.length > 0 ? args[0] : "") {
            case Cli.COMMAND -> System.exit(Cli.main(args, System.out, System.err));
            case FormatDump.COMMAND -> System.exit(FormatDump.main(args, System.out, System.err));
            default -> SpringApplication.run(PluginApplication.class, args);
        }
    }
}
