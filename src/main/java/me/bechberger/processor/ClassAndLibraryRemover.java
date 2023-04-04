package me.bechberger.processor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static java.util.zip.Deflater.DEFAULT_COMPRESSION;
import static me.bechberger.processor.Util.classNameForJarEntry;

/**
 * Remove classes and libraries from a JAR and write it back
 */
public class ClassAndLibraryRemover {

    private final Path sourceFile;
    private Predicate<String> isLibraryIncluded;
    private Predicate<String> isClassIncluded;

    private final OutputStream target;

    private ClassAndLibraryRemover(Path sourceFile, Predicate<String> isLibraryIncluded,
                                   Predicate<String> isClassIncluded, OutputStream target) {
        this.sourceFile = sourceFile;
        this.isClassIncluded = isClassIncluded;
        this.isLibraryIncluded = isLibraryIncluded;
        this.target = target;
    }

    public ClassAndLibraryRemover(Path sourceFile, OutputStream target) {
        this(sourceFile, l -> true, c -> true, target);
    }

    public ClassAndLibraryRemover withClassFilter(Predicate<String> isClassIncluded) {
        this.isClassIncluded = isClassIncluded;
        return this;
    }

    public ClassAndLibraryRemover withLibraryFilter(Predicate<String> isLibraryIncluded) {
        this.isLibraryIncluded = isLibraryIncluded;
        return this;
    }

    public void process() throws IOException {
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
        }
    }

    private static void processMiscEntry(JarOutputStream jarOutputStream, JarFile jarFile, JarEntry jarEntry) throws IOException {
        if (jarOutputStream != null) {
            jarOutputStream.putNextEntry(jarEntry);
            jarFile.getInputStream(jarEntry).transferTo(jarOutputStream);
        }
    }

    private void processClassEntry(JarOutputStream jarOutputStream, JarFile jarFile, JarEntry jarEntry) throws IOException {
        String className = classNameForJarEntry(jarEntry);
        if (isClassIncluded.test(className)) {
            processMiscEntry(jarOutputStream, jarFile, jarEntry);
        } else {
            // System.out.println("Skipping " + className);
        }
    }


    private void processJAREntry(JarOutputStream jarOutputStream, JarFile jarFile, JarEntry jarEntry) throws IOException {
        String name = jarEntry.getName();
        String libraryName = Util.libraryNameForPath(name);
        if (!isLibraryIncluded.test(libraryName)) {
            //System.out.println("Skipping " + libraryName);
            return;
        }
        Path tempFile = Files.createTempFile("nested-jar", ".jar");
        tempFile.toFile().deleteOnExit();
        // copy entry over
        InputStream in = jarFile.getInputStream(jarEntry);
        Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        ClassAndLibraryRemover nestedJarProcessor;
        // create new JAR file
        // nesting JAR files is too cumbersome
        Path newJarFile = Files.createTempFile("new-jar", ".jar");
        newJarFile.toFile().deleteOnExit();
        try (OutputStream newOutputStream = Files.newOutputStream(newJarFile)) {
            nestedJarProcessor = new ClassAndLibraryRemover(tempFile, isLibraryIncluded, isClassIncluded,
                    newOutputStream);
            nestedJarProcessor.process();
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
}
