package me.bechberger;

import me.bechberger.processor.Processor;
import picocli.CommandLine;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static Path getExtractedJARPath() {
        try {
            // based on https://github.com/gkubisa/jni-maven/blob/master/src/main/java/ie/agisoft/LibraryLoader.java
            InputStream in = Main.class.getClassLoader().getResourceAsStream("dead-code-runtime.jar");
            assert in != null;

            File file = File.createTempFile("runtime", ".jar");

            file.deleteOnExit();
            try {
                byte[] buf = new byte[4096];
                try (OutputStream out = new FileOutputStream(file)) {
                    while (in.available() > 0) {
                        int len = in.read(buf);
                        if (len >= 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }
            } finally {
                in.close();
            }
            return file.toPath().toAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
