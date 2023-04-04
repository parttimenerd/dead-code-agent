package me.bechberger.processor;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.scopedpool.ScopedClassPoolFactoryImpl;
import javassist.scopedpool.ScopedClassPoolRepositoryImpl;
import me.bechberger.runtime.Store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

import static me.bechberger.processor.Util.classNameForJarEntry;
import static me.bechberger.processor.Util.isIgnoredClassName;

/**
 * Remove classes and libraries from a JAR and write it back
 */
public class ClassAndLibraryTransformer {

    private final Path sourceFile;
    private Predicate<String> isLibraryIncluded;
    private Predicate<String> isClassIncluded;
    /** transforms the class file, might be null */
    private BiConsumer<ClassPool, CtClass> classTransformer;

    record JarEntryPair(String name, InputStream data) {
        static JarEntryPair of(Class<?> klass, String path) throws IOException {
            return new JarEntryPair(path, klass.getClassLoader().getResourceAsStream(path));
        }
    }

    private Supplier<List<JarEntryPair>> miscFilesSupplier = List::of;

    private final OutputStream target;

    private ClassAndLibraryTransformer(Path sourceFile, Predicate<String> isLibraryIncluded,
                                       Predicate<String> isClassIncluded,
                                       BiConsumer<ClassPool, CtClass> classTransformer, OutputStream target) {
        this.sourceFile = sourceFile;
        this.isClassIncluded = isClassIncluded;
        this.isLibraryIncluded = isLibraryIncluded;
        this.classTransformer = classTransformer;
        this.target = target;
    }

    public ClassAndLibraryTransformer(Path sourceFile, OutputStream target) {
        this(sourceFile, l -> true, c -> true, null, target);
    }

    public ClassAndLibraryTransformer withClassFilter(Predicate<String> isClassIncluded) {
        this.isClassIncluded = isClassIncluded;
        return this;
    }

    public ClassAndLibraryTransformer withLibraryFilter(Predicate<String> isLibraryIncluded) {
        this.isLibraryIncluded = isLibraryIncluded;
        return this;
    }

    public ClassAndLibraryTransformer withClassTransformer(BiConsumer<ClassPool, CtClass> classTransformer) {
        this.classTransformer = classTransformer;
        return this;
    }

    public ClassAndLibraryTransformer withMiscFilesSupplier(Supplier<List<JarEntryPair>> miscFilesSupplier) {
        this.miscFilesSupplier = miscFilesSupplier;
        return this;
    }

    public void process() throws IOException {
        process(true);
    }

    private void process(boolean outer) throws IOException {
        try (JarOutputStream jarOutputStream = new JarOutputStream(target); JarFile jarFile =
                new JarFile(sourceFile.toFile())) {
            jarFile.stream().forEach(jarEntry -> {
                try {
                    String name = jarEntry.getName();
                    if (name.endsWith(".class")) {
                        processClassEntry(jarOutputStream, jarFile, jarEntry);
                    } else if (name.endsWith(".jar")) {
                        processJAREntry(jarOutputStream, jarFile, jarEntry);
                    } else {
                        processMiscEntry(jarOutputStream, jarFile, jarEntry);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            if (outer) {
                for (JarEntryPair miscFile : miscFilesSupplier.get()) {
                    JarEntry jarEntry = new JarEntry(miscFile.name);
                    jarOutputStream.putNextEntry(jarEntry);
                    miscFile.data.transferTo(jarOutputStream);
                }
            }
        }
    }

    private static void processMiscEntry(JarOutputStream jarOutputStream, JarFile jarFile, JarEntry jarEntry) throws IOException {
        jarOutputStream.putNextEntry(jarEntry);
        jarFile.getInputStream(jarEntry).transferTo(jarOutputStream);
    }

    private void processClassEntry(JarOutputStream jarOutputStream, JarFile jarFile, JarEntry jarEntry) throws IOException {
        String className = classNameForJarEntry(jarEntry);
        if (isClassIncluded.test(className) || isIgnoredClassName(className)) {
            jarOutputStream.putNextEntry(jarEntry);
            InputStream classStream = jarFile.getInputStream(jarEntry);
            if (classTransformer != null && !isIgnoredClassName(className)) {
                classStream = transform(classStream);
            }
            classStream.transferTo(jarOutputStream);
        } else {
            System.out.println("Skipping class " + className);
        }
    }

    private final ScopedClassPoolFactoryImpl scopedClassPoolFactory = new ScopedClassPoolFactoryImpl();


    private InputStream transform(InputStream classStream) throws IOException {
        byte[] cl = classStream.readAllBytes();
        assert classTransformer != null;
        try {
            ClassPool cp = scopedClassPoolFactory.create(ClassPool.getDefault(),
                    ScopedClassPoolRepositoryImpl.getInstance());
            CtClass cc = cp.makeClass(new ByteArrayInputStream(cl));
            if (cc.isFrozen()) {
                return new ByteArrayInputStream(cc.toBytecode());
            }
            // classBeingRedefined is null if the class has not yet been defined
            classTransformer.accept(cp, cc);
            return new ByteArrayInputStream(cc.toBytecode());
        } catch (CannotCompileException | IOException | RuntimeException e) {
            e.printStackTrace();
            return new ByteArrayInputStream(cl);
        }
    }


    private void processJAREntry(JarOutputStream jarOutputStream, JarFile jarFile, JarEntry jarEntry) throws IOException {
        String name = jarEntry.getName();
        String libraryName = Util.libraryNameForPath(name);
        if (!isLibraryIncluded.test(libraryName)) {
            System.out.println("Skipping library " + libraryName);
            return;
        }
        Path tempFile = Files.createTempFile("nested-jar", ".jar");
        tempFile.toFile().deleteOnExit();
        // copy entry over
        InputStream in = jarFile.getInputStream(jarEntry);
        Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        ClassAndLibraryTransformer nestedJarProcessor;
        // create new JAR file
        // nesting JAR files is too cumbersome
        Path newJarFile = Files.createTempFile("new-jar", ".jar");
        newJarFile.toFile().deleteOnExit();
        try (OutputStream newOutputStream = Files.newOutputStream(newJarFile)) {
            nestedJarProcessor = new ClassAndLibraryTransformer(tempFile, isLibraryIncluded, isClassIncluded, classTransformer,
                    newOutputStream);
            nestedJarProcessor.process(false);
        }
        // see https://docs.spring.io/spring-boot/docs/current/reference/html/executable-jar.html
        JarEntry newJarEntry = new JarEntry(jarEntry.getName());
        newJarEntry.setMethod(JarEntry.STORED);
        newJarEntry.setCompressedSize(Files.size(newJarFile));
        CRC32 crc32 = new CRC32();
        crc32.update(Files.readAllBytes(newJarFile));
        newJarEntry.setCrc(crc32.getValue());
        jarOutputStream.putNextEntry(newJarEntry);
        Files.copy(newJarFile, jarOutputStream);
        tempFile.toFile().delete();
    }

    public static BiConsumer<ClassPool, CtClass> createUnusedClassTransformer(Predicate<String> isClassUsed, Function<String, String> messageSupplier, boolean exit) {
        return (cp, cc) -> {
            String className = cc.getName();
            if (isClassUsed.test(className)) {
                return;
            }
            try {
                String message = messageSupplier.apply(className);
                cc.makeClassInitializer().insertBefore(
                        String.format("System.err.println(\"Class %s is used which is not allowed%s\"); if (%s) { System.exit(1); }", className, message.isBlank() ? "" : (": " + message), exit));
            } catch (CannotCompileException e) {
                throw new RuntimeException(e);
            }
        };
    }

    public static BiConsumer<ClassPool, CtClass> createClassInstrumenter(Path output) {
        return (cp, cc) -> {
            String className = cc.getName();
            Store.getInstance().processClassLoad(className, cc.getClassFile().getInterfaces());
            try {
                cc.makeClassInitializer().insertBefore(String.format("me.bechberger.runtime.Store.getInstance().setStorePathIfNotNull(\"%s\"); me.bechberger.runtime.Store.getInstance()" +
                        ".processClassUsage(\"%s\", %s.class);", output.toString(), className, className));
            } catch (CannotCompileException e) {
                throw new RuntimeException(e);
            }
        };
    }

    public static Supplier<List<JarEntryPair>> createStoreClassSupplier() {
        return () -> {
            try {
                return List.of(JarEntryPair.of(Store.class, "me/bechberger/runtime/Store.class"),
                        JarEntryPair.of(Store.Entry.class, "me/bechberger/runtime/Store$Entry.class"),
                        JarEntryPair.of(Store.State.class, "me/bechberger/runtime/Store$State.class"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
