package me.bechberger;

import me.bechberger.processor.Processor;
import picocli.CommandLine;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.jar.JarFile;

/**
 * Agent entry and CLI
 */
public class Main {
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        AgentOptions options = new AgentOptions(agentArgs);
        // clear the file
        options.getOutput().ifPresent(out -> {
            try {
                Files.deleteIfExists(out);
                Files.createFile(out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            inst.appendToBootstrapClassLoaderSearch(new JarFile(getExtractedJARPath().toFile()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        inst.addTransformer(new ClassTransformer(options), true);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Processor()).execute(args));
    }

    private static Path getExtractedJARPath() throws IOException {
        try (InputStream in = Main.class.getClassLoader().getResourceAsStream("dead-code-runtime.jar")){
            if (in == null) {
                throw new RuntimeException("Could not find dead-code-runtime.jar");
            }
            File file = File.createTempFile("runtime", ".jar");
            file.deleteOnExit();
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return file.toPath().toAbsolutePath();
        }
    }
}
