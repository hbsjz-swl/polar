package com.dlchm.dlc.sandbox;

import java.util.Set;

public class BashCommandSandbox {

    private static final Set<String> BLACKLISTED = Set.of(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=",
            "shutdown", "reboot", "init 0", "init 6",
            "> /dev/sda", ":(){ :|:& };:"
    );

    private static final Set<String> NEEDS_CONFIRM = Set.of(
            "rm ", "git push --force", "git push -f",
            "git reset --hard", "git clean", "sudo"
    );

    public ValidationResult validate(String command) {
        String normalized = command.trim().toLowerCase();
        for (String b : BLACKLISTED) {
            if (normalized.contains(b)) {
                throw new SandboxViolationException("Blocked: " + command);
            }
        }
        for (String p : NEEDS_CONFIRM) {
            if (normalized.startsWith(p) || normalized.contains(" && " + p)) {
                return new ValidationResult(true, p);
            }
        }
        return new ValidationResult(false, null);
    }

    public record ValidationResult(boolean requiresConfirmation, String reason) {}
}
