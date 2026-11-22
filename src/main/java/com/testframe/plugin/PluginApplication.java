package com.testframe.plugin;

import com.testframe.plugin.cli.Cli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. {@code run <projectDir> --env <env> ...} is the headless CLI (no web server, no
 * Spring context; docs/test-tour.md, "Headless CLI"); anything else starts the HTTP plugin
 * (runner protocol {@code /api/v1}, tour API {@code /api/v1/tour}).
 */
@SpringBootApplication
public class PluginApplication {

    public static void main(String[] args) {
        if (args.length > 0 && Cli.COMMAND.equals(args[0])) {
            System.exit(Cli.main(args, System.out, System.err));
        }
        SpringApplication.run(PluginApplication.class, args);
    }
}
