package me.bechberger;

import javassist.*;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.FieldInfo;
import javassist.scopedpool.ScopedClassPoolFactoryImpl;
import javassist.scopedpool.ScopedClassPoolRepositoryImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;

/** class transformer to add code in static initializer. Cannot be used for retransformation*/
public class ClassTransformer implements ClassFileTransformer {
    private final ScopedClassPoolFactoryImpl scopedClassPoolFactory = new ScopedClassPoolFactoryImpl();

    private final Options options;

    public ClassTransformer(Options options) {
        this.options = options;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        try {
            ClassPool cp = scopedClassPoolFactory.create(loader, ClassPool.getDefault(),
                    ScopedClassPoolRepositoryImpl.getInstance());
            CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
            if (cc.isFrozen()) {
                return classfileBuffer;
            }
            transform(className, cc);
            return cc.toBytecode();

        } catch (CannotCompileException | IOException | RuntimeException e) {
            e.printStackTrace();
            return classfileBuffer;
        }
    }

    private void transform(String className, CtClass cc) throws CannotCompileException, IOException {
        appendWriteToInitializer(className, cc, "u");
        if (options.getOutput().isPresent()) {
            Files.write(options.getOutput().get(), ("l " + className + "\n").getBytes(), StandardOpenOption.APPEND);
        } else {
            System.err.println("l " + className);
        }
    }

    private void appendWriteToInitializer(String className, CtClass cc, String prefix) throws CannotCompileException,
            IOException {
        // adding it directly to a Store instance would be better,
        // but this would require more work, as we would have to extract the Store class
        // and put it on the boot class path
        // I did this before for the trace-validation project and it was a lot of work to get it right
        if (options.getOutput().isPresent()) {
            cc.makeClassInitializer().insertBefore(String.format("java.nio.file.Files.write(new java.io.File(\"%s\").toPath(), (\"%s %s\\n\").getBytes(), new java.nio.file.OpenOption[] {java.nio.file.StandardOpenOption.APPEND});", options.getOutput().get().toAbsolutePath(), prefix, className));
        } else {
            cc.makeClassInitializer().insertAfter(String.format("System.err.println(\"%s %s\");", prefix, className));
        }
    }
}
