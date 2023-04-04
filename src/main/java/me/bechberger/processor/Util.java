package me.bechberger.processor;

import java.nio.file.Path;
import java.util.jar.JarEntry;

public final class Util {
    static String libraryNameForPath(String path) {
        String name = Path.of(path).getFileName().toString();
        if (name.endsWith(".jar")) {
            String pre = name.substring(0, name.length() - 4);
            // remove parts that start with number.number
            return pre.replaceAll("-[0-9]+\\.[0-9]+.*$", "");
        }
        return name;
    }

    public static String classNameForJarEntry(JarEntry entry) {
        String name = entry.getName();
        return name.substring(0, name.length() - 6).replace('/', '.').replace("BOOT-INF.classes.", "");
    }

}
