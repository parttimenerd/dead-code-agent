package me.bechberger;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputFilter.Config;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent entry
 */
public class Main {
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        Options options = new Options(agentArgs);
        // clear the file
        options.getOutput().ifPresent(out -> {
            try {
                Files.deleteIfExists(out);
                Files.createFile(out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        inst.addTransformer(new ClassTransformer(options), true);
    }
}
