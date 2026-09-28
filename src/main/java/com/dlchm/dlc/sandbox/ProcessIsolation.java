package com.dlchm.dlc.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Builds a best-effort OS process sandbox for shell tools.
 *
 * <p>macOS uses Seatbelt when {@code sandbox-exec} is available; Linux uses
 * bubblewrap when installed. Other platforms retain the workspace policy and
 * process-tree cleanup, and report that native isolation is unavailable.</p>
 */
public final class ProcessIsolation {
    private static volatile Boolean nativeAvailable;

    private ProcessIsolation() {}

    public static List<String> command(Path workspace, String shellCommand) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && nativeAvailable()) {
            String root = profilePath(workspace);
            String profile = "(version 1) "
                    + "(deny file-write* (subpath \"/\")) "
                    + "(allow file-write* (subpath \"" + root + "\")) "
                    + "(allow file-write* (subpath \"/private/tmp\")) "
                    + "(allow default)";
            return List.of("/usr/bin/sandbox-exec", "-p", profile, "bash", "-c", shellCommand);
        }
        if (os.contains("linux") && nativeAvailable()) {
            String root = workspace.toAbsolutePath().normalize().toString();
            return List.of("bwrap", "--die-with-parent", "--ro-bind", "/", "/",
                    "--bind", root, root, "--proc", "/proc", "--dev", "/dev",
                    "--tmpfs", "/tmp", "--", "bash", "-c", shellCommand);
        }
        return List.of("bash", "-c", shellCommand);
    }

    public static boolean nativeAvailable() {
        Boolean cached = nativeAvailable;
        if (cached != null) return cached;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean available = os.contains("mac")
                ? probe(List.of("/usr/bin/sandbox-exec", "-p", "(version 1) (allow default)", "true"))
                : os.contains("linux") && probe(List.of("bwrap", "--ro-bind", "/", "/", "--", "true"));
        nativeAvailable = available;
        return available;
    }

    public static void destroyTree(Process process) {
        if (process == null) return;
        ProcessHandle handle = process.toHandle();
        handle.descendants().toList().stream()
                .sorted((a, b) -> Long.compare(b.pid(), a.pid()))
                .forEach(child -> {
                    child.destroy();
                    if (child.isAlive()) child.destroyForcibly();
                });
        handle.destroy();
        if (handle.isAlive()) handle.destroyForcibly();
    }

    private static boolean executableOnPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(directory).resolve(name))) return true;
        }
        return false;
    }

    private static boolean probe(List<String> command) {
        try {
            if (command.isEmpty()) return false;
            if (!Path.of(command.get(0)).isAbsolute() && !executableOnPath(command.get(0))) return false;
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                destroyTree(process);
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String profilePath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
