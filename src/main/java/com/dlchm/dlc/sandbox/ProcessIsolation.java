package com.dlchm.dlc.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a best-effort OS process sandbox for shell tools.
 *
 * <p>macOS uses Seatbelt when {@code sandbox-exec} is available; Linux uses
 * bubblewrap when installed. Windows has no equivalent that ships with the OS,
 * so it keeps the workspace policy and process-tree cleanup and reports that
 * native isolation is unavailable — the boundary checks still apply, they just
 * are not enforced by the kernel.</p>
 */
public final class ProcessIsolation {
    private static volatile Boolean nativeAvailable;

    private ProcessIsolation() {}

    public static List<String> command(Path workspace, String shellCommand) {
        return command(workspace, shellCommand, Platform.get());
    }

    /**
     * Builds the argv for a command on a given platform.
     *
     * <p>The platform is a parameter so the Windows branch is testable from any
     * host. Without that seam it would only ever run on a Windows machine, and
     * the failure mode there — "Cannot run program bash" — is exactly the kind
     * that ships unnoticed.</p>
     */
    public static List<String> command(Path workspace, String shellCommand, Platform platform) {
        String payload = platform.wrapForExitCode(shellCommand);
        if (platform.isMac() && nativeAvailable(platform)) {
            return prefixed(List.of("/usr/bin/sandbox-exec", "-p", macProfile(workspace)),
                    payload, platform);
        }
        if (platform.isLinux() && nativeAvailable(platform)) {
            String root = workspace.toAbsolutePath().normalize().toString();
            return prefixed(List.of("bwrap", "--die-with-parent", "--ro-bind", "/", "/",
                    "--bind", root, root, "--proc", "/proc", "--dev", "/dev",
                    "--tmpfs", "/tmp", "--"), payload, platform);
        }
        // No native sandbox here: fall back to the platform shell so the command
        // still runs under the workspace policy rather than not running at all.
        if (!warnedAboutMissingSandbox && platform.isMac()) {
            warnedAboutMissingSandbox = true;
            System.err.println("[dlc] Warning: no OS sandbox available ("
                    + platform.name() + "). Commands are limited only by the workspace "
                    + "path checks, not by the kernel. Do not run untrusted commands.");
        }
        return concat(platform.shell(), payload);
    }

    private static volatile boolean warnedAboutMissingSandbox;

    /**
     * Builds the Seatbelt profile confining writes to the workspace.
     *
     * <p>Seatbelt resolves conflicting rules last-one-wins, so the blanket
     * {@code (allow default)} has to come FIRST. Placed last — as it was — it
     * silently overrides the deny below it and the sandbox permits writes
     * anywhere, which is indistinguishable from running the command unsandboxed.
     * That regression is invisible on a host where the sandbox is unavailable,
     * hence this is a separate, always-callable method rather than an inline
     * string inside {@link #command}.</p>
     */
    static String macProfile(Path workspace) {
        String root = workspace.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\").replace("\"", "\\\"");
        return "(version 1) "
                + "(allow default) "
                + "(deny file-write* (subpath \"/\")) "
                + "(allow file-write* (subpath \"" + root + "\")) "
                + "(allow file-write* (subpath \"/private/tmp\")) "
                + "(allow file-write* (subpath \"/tmp\"))";
    }

    private static List<String> prefixed(List<String> launcher, String payload, Platform platform) {
        List<String> command = new ArrayList<>(launcher);
        command.addAll(platform.shell());
        command.add(payload);
        return List.copyOf(command);
    }

    private static List<String> concat(List<String> head, String tail) {
        List<String> command = new ArrayList<>(head);
        command.add(tail);
        return List.copyOf(command);
    }

    public static boolean nativeAvailable() {
        return nativeAvailable(Platform.get());
    }

    /**
     * Whether the host has a usable native sandbox.
     *
     * <p>Windows answers false unconditionally: there is no Seatbelt
     * equivalent in the OS, and claiming otherwise would leave a command
     * unsandboxed while reporting it as isolated.</p>
     */
    static boolean nativeAvailable(Platform platform) {
        if (platform.isWindows()) return false;
        Boolean cached = nativeAvailable;
        if (cached != null) return cached;
        boolean available = platform.isMac()
                ? probe(List.of("/usr/bin/sandbox-exec", "-p", "(version 1) (allow default)", "true"))
                : platform.isLinux() && probe(List.of("bwrap", "--ro-bind", "/", "/", "--", "true"));
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

    private static boolean probe(List<String> command) {
        try {
            if (command.isEmpty()) return false;
            String head = command.get(0);
            if (!Path.of(head).isAbsolute() && Platform.findOnPath(head) == null) return false;
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

}
