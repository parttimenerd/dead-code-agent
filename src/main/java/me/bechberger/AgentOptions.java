package me.bechberger;

import java.nio.file.Path;
import java.util.Optional;

public class AgentOptions {

    private Optional<Path> input = Optional.empty();
    /**
     * default is stderr
     */
    private Optional<Path> output = Optional.empty();

    private void printHelp() {
        System.out.println("""
                Usage: java -javaagent:dead-code.jar=options ...
                Options:
                    help: Print this help message
                    input: the path to load the store with the metadata per class
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
                case "input" -> input = Optional.of(Path.of(value));
                case "output" -> output = Optional.of(Path.of(value));
                default -> optionsError("Unknown argument: " + key);
            }
        }
    }

    public AgentOptions(String agentArgs) {
        initOptions(agentArgs);
    }

    public Optional<Path> getOutput() {
        return output;
    }

    public Optional<Path> getInput() {
        return input;
    }
}
