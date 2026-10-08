package com.dlchm.dlc.sandbox;

import java.util.Set;

/**
 * Command patterns that are refused outright or need human confirmation.
 *
 * <p>The destructive patterns cover PowerShell as well as POSIX shells: a
 * command vetted only against {@code rm -rf /} would happily wave through
 * {@code Remove-Item -Recurse -Force C:\} or {@code format C:}, which delete
 * just as much. Matching is done on a lower-cased substring rather than a
 * prefix, because a dangerous command is just as dangerous after {@code ;} or
 * {@code &&} as at the start of the line.</p>
 */
public class BashCommandSandbox {

    private static final Set<String> BLACKLISTED = Set.of(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=",
            "shutdown", "reboot", "init 0", "init 6",
            "> /dev/sda", ":(){ :|:& };:",
            // Windows / PowerShell equivalents of the above.
            "format c:", "format d:", "diskpart", "cipher /w",
            "remove-item -recurse -force c:\\", "remove-item -recurse -force c:/",
            "del /s /q c:\\", "del /s /q c:/", "rd /s /q c:\\", "rd /s /q c:/",
            "remove-item -recurse -force $env:systemroot",
            "reg delete hk lm\\software", "reg delete hklm\\software"
    );

    private static final Set<String> NEEDS_CONFIRM = Set.of(
            "rm ", "rmdir ", "git push --force", "git push -f",
            "git reset --hard", "git clean", "sudo",
            // PowerShell / Windows equivalents.
            "remove-item", "del ", "rd ", "rmdir",
            "reg delete", "takeown", "icacls", "cipher /",
            "move-item", "mv ", "cp -r"
    );

    public ValidationResult validate(String command) {
        String normalized = command.trim().toLowerCase();
        for (String b : BLACKLISTED) {
            if (normalized.contains(b)) {
                throw new SandboxViolationException("Blocked: " + command);
            }
        }
        // A separator anywhere in the line can hide a second command, so the
        // confirmation check scans the whole string instead of just its head.
        for (String p : NEEDS_CONFIRM) {
            if (containsCommand(normalized, p)) {
                return new ValidationResult(true, p.trim());
            }
        }
        return new ValidationResult(false, null);
    }

    /**
     * True when {@code needle} appears as a command, not as part of a word.
     *
     * <p>{@code "confirm "} contains {@code "rm "} but is harmless, so a plain
     * substring test would demand approval for ordinary prose.</p>
     */
    private static boolean containsCommand(String normalized, String needle) {
        int from = 0;
        while (true) {
            int at = normalized.indexOf(needle, from);
            if (at < 0) return false;
            boolean startOk = at == 0 || !Character.isLetterOrDigit(normalized.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end >= normalized.length()
                    || !Character.isLetter(normalized.charAt(end))
                    || needle.endsWith(" ");
            if (startOk && endOk) return true;
            from = at + 1;
        }
    }

    public record ValidationResult(boolean requiresConfirmation, String reason) {}
}
