package me.bechberger.processor;

import me.bechberger.runtime.Store;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Command(name = "dead-code", mixinStandardHelpOptions = true,
        description = "Process the information gathered by the dead-code agent")
public class Processor implements Runnable {

    @Spec
    CommandLine.Model.CommandSpec spec;
    @Parameters(index = "0", description = "The input store file")
    private Path input;

    @Override
    public void run() {
        throw new ParameterException(spec.commandLine(), "Specify a subcommand");
    }

    private Set<String> getClasses(Path jar) throws IOException {
        Set<String> classes = new HashSet<>();
        new NestedJarProcessor(jar).withClassFilter(classes::add).process();
        return classes;
    }

    @Command(name = "unusedClasses", description = "List all unused (but loaded or present in JAR) classes")
    public void unusedClasses(@Parameters(arity = "0..1", paramLabel = "JAR") Path jar) throws IOException {
        Store store = new Store().load(input);
        Set<String> unusedClasses;
        if (jar != null) {
            Set<String> usedClasses = store.getUsedClasses();
            unusedClasses = getClasses(jar).stream().filter(c -> !usedClasses.contains(c)).collect(Collectors.toSet());
        } else {
            unusedClasses = store.getUnusedClasses();
        }
        unusedClasses.stream().sorted().forEach(System.out::println);
    }

    @Command(name = "usedClasses", description = "List all used classes")
    public void usedClasses() throws IOException {
        Store store = new Store().load(input);
        store.getUsedClasses().stream().sorted().forEach(System.out::println);
    }

    @Command(name = "loadedClasses", description = "List all loaded classes")
    public void loadedClasses() throws IOException {
        Store store = new Store().load(input);
        store.getLoadedClasses().stream().sorted().forEach(System.out::println);
    }

    @Command(name = "allClasses", description = "List all classes")
    public void allClasses(@Parameters(index = "0", paramLabel = "JAR") Path jar) throws IOException {
        getClasses(jar).stream().sorted().forEach(System.out::println);
    }

    @Command(name = "unusedLibraries", description = "List all unused libraries")
    public void unusedLibraries(@Parameters(index = "0", paramLabel = "JAR") Path jar) throws IOException {
        Libraries libraries = findUsedAndUnusedLibraries(jar, new Store().load(input), false);
        libraries.unused.stream().sorted().forEach(System.out::println);
    }

    @Command(name = "usedLibraries", description = "List all used libraries")
    public void usedLibraries(@Parameters(index = "0", paramLabel = "JAR") Path jar) throws IOException {
        Libraries libraries = findUsedAndUnusedLibraries(jar, new Store().load(input), false);
        libraries.used.stream().sorted().forEach(System.out::println);
    }

    @Command(name = "notLoadedLibraries", description = "List all libraries that are present but are not loaded")
    public void notLoadedLibraries(@Parameters(index = "0", paramLabel = "JAR") Path jar) throws IOException {
        Libraries libraries = findUsedAndUnusedLibraries(jar, new Store().load(input), true);
        libraries.unused.stream().sorted().forEach(System.out::println);
    }

    @Command(name = "loadedLibraries", description = "List all loaded libraries")
    public void loadedLibraries(@Parameters(index = "0", paramLabel = "JAR") Path jar) throws IOException {
        Libraries libraries = findUsedAndUnusedLibraries(jar, new Store().load(input), true);
        libraries.used.stream().sorted().forEach(System.out::println);
    }

    @Command(name = "instrumentUnusedClasses", description = "Instrument all unused classes to add an alert (or System.exit(1) depending on the options)")
    public void instrumentUnusedClasses(@Parameters(index = "0", paramLabel = "JAR") Path jar, @Parameters(index = "1", paramLabel
            = "OUTPUT_JAR") Path output, @Option(names = "--exit", description = "add System.exit(1) to every unused class") boolean exit) throws IOException {
        Store store = new Store().load(input);
        ClassAndLibraryTransformer clr =
                new ClassAndLibraryTransformer(jar, Files.newOutputStream(output)).withClassTransformer(ClassAndLibraryTransformer.createUnusedClassTransformer((cn) -> store.isClassUsed(cn) && !store.isClassMarkedForDeletion(cn), store::getDeletionMessage, exit));
        clr.process();
    }

    @Command(name = "instrument", description = "Instrument all classes to store information about which classes are loaded and used")
    public void instrument(@Parameters(index = "0", paramLabel = "JAR") Path jar, @Parameters(index = "1", paramLabel
            = "OUTPUT_JAR") Path output, @Option(names = "--exit", description = "add System.exit(1) to every unused class") boolean exit) throws IOException {
        ClassAndLibraryTransformer clr =
                new ClassAndLibraryTransformer(jar, Files.newOutputStream(output)).withClassTransformer(ClassAndLibraryTransformer.createClassInstrumenter(input)).withMiscFilesSupplier(ClassAndLibraryTransformer.createStoreClassSupplier());
        clr.process();
    }

    @Command(name = "reduceJAR", description = "Remove all unused classes and libraries from the JAR")
    public void reduceJAR(@Parameters(index = "0", paramLabel = "JAR") Path jar, @Parameters(index = "1", paramLabel
            = "OUTPUT_JAR") Path output,
                          @Option(names = "--onlyLibraries") boolean onlyLibraries) throws IOException {
        Store store = new Store().load(input);
        Libraries libraries = findUsedAndUnusedLibraries(jar, store, false);
        ClassAndLibraryTransformer clr =
                new ClassAndLibraryTransformer(jar, Files.newOutputStream(output)).withLibraryFilter(libraries.used::contains);
        if (!onlyLibraries) {
            clr.withClassFilter(c -> store.isClassLoaded(c) && !store.isClassMarkedForDeletion(c));
        }
        clr.process();
    }

    private record Libraries(Set<String> used, Set<String> unused) {
    }

    /** a class is used if one of its classes is loaded */
    private static Libraries findUsedAndUnusedLibraries(Path jar, Store store, boolean countLoadedAsUsed) throws IOException {
        Set<String> usedLibraries = new HashSet<>();
        Set<String> unusedLibraries = new HashSet<>();
        new NestedJarProcessor(jar).withClassFilter(countLoadedAsUsed ? store::isClassLoaded : store::isClassUsed)
                .withUnusedLibraryConsumer(unusedLibraries::add).withUsedLibraryConsumer(usedLibraries::add).process();
        return new Libraries(usedLibraries, unusedLibraries);
    }
}
