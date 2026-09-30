package com.dlchm.dlc.tools;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

/** Prunes disposable data in DLC's dedicated Chrome profile, preserving login state. */
final class BrowserCacheCleaner {
    static final Path PROFILE = Path.of(System.getProperty("user.home"), ".dlc", "browser", "profile")
            .toAbsolutePath().normalize();
    private static final Set<String> CACHE_DIRS = Set.of(
            "Cache", "Code Cache", "GPUCache", "GrShaderCache", "ShaderCache",
            "DawnCache", "Media Cache", "CacheStorage");

    private BrowserCacheCleaner() { }

    static void prune() {
        if (!Files.isDirectory(PROFILE) || inUse()) return;
        try (var paths = Files.walk(PROFILE, 6)) {
            List<Path> caches = paths
                    .filter(p -> Files.isDirectory(p) && !Files.isSymbolicLink(p))
                    .filter(p -> CACHE_DIRS.contains(p.getFileName().toString()))
                    .filter(p -> p.normalize().startsWith(PROFILE))
                    .toList();
            for (Path cache : caches) deleteTree(cache);
        } catch (IOException ignored) {
            // Cache cleanup is best effort and must never block agent startup.
        }
    }

    static boolean inUse() {
        return inUse(PROFILE);
    }

    static boolean inUse(Path profile) {
        String profileArg = "--user-data-dir=" + profile;
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.anyMatch(p -> p.info().commandLine().orElse("").contains(profileArg));
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!root.startsWith(PROFILE) || Files.isSymbolicLink(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
