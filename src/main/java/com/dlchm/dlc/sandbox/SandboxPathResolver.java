package com.dlchm.dlc.sandbox;

import java.nio.file.Path;
import java.util.List;

public class SandboxPathResolver {

    private final Path workspaceRoot;
    private final List<Path> blockedPaths;

    public SandboxPathResolver(String workspaceRoot, List<String> blockedPathStrings) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.blockedPaths = blockedPathStrings.stream()
                .map(p -> this.workspaceRoot.resolve(p).normalize())
                .toList();
    }

    public Path resolve(String userPath) {
        Path resolved = workspaceRoot.resolve(userPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new SandboxViolationException(
                    "Path traversal blocked: " + userPath + " escapes " + workspaceRoot);
        }
        for (Path blocked : blockedPaths) {
            if (resolved.startsWith(blocked)) {
                throw new SandboxViolationException("Access denied: " + userPath);
            }
        }
        return resolved;
    }

    public Path getWorkspaceRoot() {
        return workspaceRoot;
    }
}
