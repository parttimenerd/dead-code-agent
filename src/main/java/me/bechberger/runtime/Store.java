package me.bechberger.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Store {

    public enum State {
        NOT_LOADED("n"), LOADED("l"), USED("u");

        public final String prefix;

        State(String prefix) {
            this.prefix = prefix;
        }

        static State parse(String str) {
            return Stream.of(State.values()).filter(e -> str.contains(e.prefix)).findFirst().orElse(NOT_LOADED);
        }

        boolean isLarger(State other) {
            return this.ordinal() > other.ordinal();
        }

        boolean isLargerOrEqual(State other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    /**
     * Entry in the store, contains the state of the class and some metadata
     * <p>
     * Not thread-safe, but this is not a problem, as entries are only concurrently accessed
     * when loaded or stored and this is sequential per single entry
     */
    public static class Entry implements Comparable<Entry> {
        public final String className;
        private State state;
        /**
         * from outside: should report usage
         */
        private boolean report;
        /**
         * from outside: should remove class
         */
        public final boolean delete;
        public final String reportMessage;

        private String[] interfaces = new String[0];

        public Entry(String className, State initialState, boolean report, boolean delete, String reportMessage) {
            this.className = className;
            this.state = initialState;
            this.report = report;
            this.delete = delete;
            this.reportMessage = reportMessage;
        }

        public void setState(State state) {
            this.state = state;
        }

        public State getState() {
            return state;
        }

        private String prefix() {
            return state.prefix + (report ? "r" : "") + (delete ? "d" : "");
        }

        @Override
        public String toString() {
            return prefix() + " " + className + (reportMessage.isBlank() ? "": " " + reportMessage);
        }

        public static Entry parse(String line) {
            String[] parts = line.split(" ", 3);
            String prefix = parts[0];
            String className = parts[1];
            return new Entry(className, State.parse(prefix), prefix.contains("r"), prefix.contains("d"),
                    parts.length > 2 ? parts[2] : "");
        }

        @Override
        public int compareTo(Entry o) {
            return className.compareTo(o.className);
        }
    }

    private final ConcurrentHashMap<String, Entry> classes = new ConcurrentHashMap<>();
    private final List<Entry> multiClassEntries = new ArrayList<>();

    private OutputStream storeStream = null;

    public Store() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (storeStream != null) {
                Store.getInstance().writeTo(storeStream);
            }
        }));
    }

    public void setStoreStream(OutputStream storeStream) {
        this.storeStream = storeStream;
    }

    public void setStorePathIfNotNull(String storePath) throws IOException {
        if (storeStream == null) {
            setStoreStream(Files.newOutputStream(Path.of(storePath)));
        }
    }

    public Store load(Path file) throws IOException {
        return load(file, false);
    }

    /**
     * Load entries of the store from a file
     */
    public Store load(Path file, boolean mapToReport) throws IOException {
        Map<String, Entry> newClasses = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            Entry entry = Entry.parse(line);
            if (entry.className.endsWith("*")) {
                multiClassEntries.add(entry);
            } else {
                newClasses.put(entry.className, entry);
            }
            if (mapToReport) {
                entry.report = entry.state != State.USED;
                entry.state = State.NOT_LOADED;
            }
        }
        classes.putAll(newClasses);
        return this;
    }

    public void writeTo(OutputStream stream) {
        Stream.concat(classes.values().stream(), multiClassEntries.stream()).sorted().map(Entry::toString).forEach(s -> {
            try {
                stream.write(s.getBytes());
                stream.write('\n');
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Entry newEntry(String className) {
        return multiClassEntries.stream().filter(e -> className.startsWith(e.className.substring(0,
                e.className.length() - 1))).findFirst().map(e -> new Entry(className, State.NOT_LOADED, e.report,
                e.delete, e.reportMessage)).orElseGet(() -> new Entry(className, State.NOT_LOADED, false, false, ""));
    }

    private Entry get(String className) {
        return classes.computeIfAbsent(className, k -> newEntry(className));
    }

    public void processClassLoad(String className, String[] interfaces) {
        Entry entry = get(className);
        entry.setState(State.LOADED);
        entry.interfaces = interfaces;
        setStateOfInterfaces(entry, State.LOADED, null);
    }

    private void setStateOfInterfaces(Entry entry, State state, Consumer<Entry> interfaceEntryConsumer) {
        for (String iface : entry.interfaces) {
            Entry ifaceEntry = get(iface);
            if (ifaceEntry.getState().isLarger(state)) {
                continue;
            }
            ifaceEntry.setState(state);
            if (interfaceEntryConsumer != null) {
                interfaceEntryConsumer.accept(entry);
            }
            setStateOfInterfaces(ifaceEntry, state, interfaceEntryConsumer);
        }
    }

    public void processClassUsage(String className) {
        processClassUsage(className, null);
    }
    public void processClassUsage(String className, Class<?> klassOrNull) {
        Entry classEntry = get(className);

        Consumer<Entry> handler = (entry) -> {
            entry.setState(State.USED);

            if (entry.report) {
                System.err.printf("Class %s used%s%n", className, entry.reportMessage.isEmpty() ? "" :
                        ": " + entry.reportMessage);
            }
        };
        handler.accept(classEntry);
        if (klassOrNull == null) {
            setStateOfInterfaces(classEntry, State.USED, handler);
        } else {
            setStateOfInterfaces(klassOrNull, State.USED, handler);
        }
    }

    private void setStateOfInterfaces(Class<?> klass, State state, Consumer<Entry> handler) {

        for (Class<?> ifaceClass : klass.getInterfaces()) {
            String iface = ifaceClass.getName();
            Entry ifaceEntry = get(iface);
            if (ifaceEntry.getState().isLarger(State.USED)) {
                continue;
            }
            ifaceEntry.setState(State.USED);
            handler.accept(ifaceEntry);
            setStateOfInterfaces(ifaceClass, state, handler);
        }
    }

    public boolean shouldRemove(String className) {
        return classes.containsKey(className) && classes.get(className).delete;
    }

    public boolean isClassUsed(String className) {
        return classes.containsKey(className) && classes.get(className).getState() == State.USED;
    }

    public boolean isClassLoaded(String className) {
        return classes.containsKey(className) && classes.get(className).getState().isLargerOrEqual(State.LOADED);
    }

    public boolean isClassMarkedForDeletion(String className) {
        return classes.containsKey(className) && classes.get(className).delete;
    }

    public String getDeletionMessage(String className) {
        return classes.containsKey(className) ? classes.get(className).reportMessage : "";
    }

    public Set<String> getUsedClasses() {
        return classes.values().stream().filter(e -> e.getState() == State.USED).map(e -> e.className).collect(Collectors.toSet());
    }

    public Set<String> getUnusedClasses() {
        return classes.values().stream().filter(e -> e.getState() == State.LOADED).map(e -> e.className).collect(Collectors.toSet());
    }

    public Set<String> getLoadedClasses() {
        return classes.values().stream().filter(e -> e.getState().isLargerOrEqual(State.LOADED)).map(e -> e.className).collect(Collectors.toSet());
    }

    // creating the class is cheap, and we will need it for sure
    private static final Store INSTANCE = new Store();

    public static Store getInstance() {
        return INSTANCE;
    }
}
