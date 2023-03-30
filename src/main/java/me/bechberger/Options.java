package me.bechberger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public class Options {
    /**
     * default is stderr
     */
    private Optional<Path> output = Optional.empty();

    private void printHelp() {
        System.out.println("""
                Usage: java -javaagent:dead-code.jar=options ...
                Options:
                    help: Print this help message
                    output: the path to store the used and not used classes, default is stderr
                """);
    }

    private void optionsError(String msg) {
        System.err.println(msg);
        printHelp();
        System.exit(1);
    }

    private void initOptions(String agentArgs) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return;
        }
        for (String part : agentArgs.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) {
                optionsError("Invalid argument: " + part);
            }
            String key = kv[0];
            String value = kv[1];
            switch (key) {
                case "help" -> printHelp();
                case "output" -> output = Optional.of(Path.of(value));
                default -> optionsError("Unknown argument: " + key);
            }
        }
    }

    public Options(String agentArgs) {
        initOptions(agentArgs);
    }

    public Optional<Path> getOutput() {
        return output;
    }
}
