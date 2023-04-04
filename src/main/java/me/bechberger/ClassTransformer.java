package me.bechberger;

import javassist.*;
import javassist.scopedpool.ScopedClassPoolFactoryImpl;
import javassist.scopedpool.ScopedClassPoolRepositoryImpl;
import me.bechberger.runtime.Store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.security.ProtectionDomain;
import java.util.Arrays;

/**
 * class transformer to add code in static initializer. Cannot be used for retransformations
 */
public class ClassTransformer implements ClassFileTransformer {
    private final ScopedClassPoolFactoryImpl scopedClassPoolFactory = new ScopedClassPoolFactoryImpl();

    public ClassTransformer(AgentOptions options) {
        // load the data on startup
        options.getInput().ifPresent(f -> {
            try {
                Store.getInstance().load(f, true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // store the data on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Store.getInstance().writeTo(options.getOutput().map(f -> {
                try {
                    return Files.newOutputStream(f);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).orElse(System.err));
        }));
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className.startsWith("me/bechberger/runtime/Store") || className.startsWith("me/bechberger" +
                "/ClassTransformer") || className.startsWith("java/") || className.startsWith("jdk/internal") || className.startsWith("sun/")) {
            return classfileBuffer;
        }
        try {
            ClassPool cp = scopedClassPoolFactory.create(loader, ClassPool.getDefault(),
                    ScopedClassPoolRepositoryImpl.getInstance());
            CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
            if (cc.isFrozen()) {
                return classfileBuffer;
            }
            // classBeingRedefined is null if the class has not yet been defined
            transform(className, cc);
            return cc.toBytecode();
        } catch (CannotCompileException | IOException | RuntimeException | NotFoundException e) {
            e.printStackTrace();
            return classfileBuffer;
        }
    }

    private void transform(String className, CtClass cc) throws CannotCompileException, NotFoundException {
        String cn = formatClassName(className);
        Store.getInstance().processClassLoad(cn, cc.getClassFile().getInterfaces());
        cc.makeClassInitializer().insertAfter(String.format("me.bechberger.runtime.Store.getInstance()" +
                ".processClassUsage(\"%s\");", cn));
    }

    private String formatClassName(String className) {
        return className.replace("/", ".");
    }
}
