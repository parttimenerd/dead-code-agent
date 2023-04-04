package me.bechberger.processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static me.bechberger.processor.Util.classNameForJarEntry;

/**
 * Read the classes in the JARs (containing potentially other JARs)
 */
public class NestedJarProcessor {

    private final Path sourceFile;
    private Predicate<String> isClassIncluded;
    private Consumer<String> unusedLibraries;
    private Consumer<String> usedLibraries;

    private boolean isUsed = false;


    private NestedJarProcessor(Path sourceFile, Predicate<String> isClassIncluded, Consumer<String> unusedLibraryConsumer
            , Consumer<String> usedLibraryConsumer) {
        this.sourceFile = sourceFile;
        this.isClassIncluded = isClassIncluded;
        this.unusedLibraries = unusedLibraryConsumer;
        this.usedLibraries = usedLibraryConsumer;
    }

    public NestedJarProcessor(Path sourceFile) {
        this(sourceFile, c -> true, c -> {
        }, c -> {
        });
    }

    public NestedJarProcessor withClassFilter(Predicate<String> isClassUsed) {
        this.isClassIncluded = isClassUsed;
        return this;
    }

    public NestedJarProcessor withUnusedLibraryConsumer(Consumer<String> unusedLibraryConsumer) {
        this.unusedLibraries = unusedLibraryConsumer;
        return this;
    }

    public NestedJarProcessor withUsedLibraryConsumer(Consumer<String> usedLibraryConsumer) {
        this.usedLibraries = usedLibraryConsumer;
        return this;
    }

    public void process() throws IOException {
        try (JarFile jarFile = new JarFile(sourceFile.toFile())) {
            jarFile.stream().forEach(jarEntry -> {
                try {
                    String name = jarEntry.getName();
                    if (name.endsWith(".class")) {
                        processClassEntry(jarFile, jarEntry);
                    } else if (name.endsWith(".jar")) {
                        processJAREntry(jarFile, jarEntry);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void processClassEntry(JarFile jarFile, JarEntry jarEntry) {
        String className = classNameForJarEntry(jarEntry);
        if (isClassIncluded.test(className)) {
            isUsed = true;
        }
    }

    private void processJAREntry(JarFile jarFile, JarEntry jarEntry) throws IOException {
        String libraryName = Util.libraryNameForPath(jarEntry.getName());
        Path tempFile = Files.createTempFile("nested-jar", ".jar");
        tempFile.toFile().deleteOnExit();
        // copy entry over
        InputStream in = jarFile.getInputStream(jarEntry);
        Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        NestedJarProcessor nestedJarProcessor;

        nestedJarProcessor = new NestedJarProcessor(tempFile, isClassIncluded, unusedLibraries, usedLibraries);
        nestedJarProcessor.process();

        if (nestedJarProcessor.isUsed) {
            isUsed = true;
            usedLibraries.accept(libraryName);
        } else {
            unusedLibraries.accept(libraryName);
        }
    }
}
