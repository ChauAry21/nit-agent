package dev.aryan.nitagent.tools;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class WorkspaceGuard {
    public Path requireRoot(String rootPath) throws IOException {
        if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("Workspace root is empty.");
        return requireRoot(Path.of(rootPath.replace("\"", "").trim()));
    }

    public Path requireRoot(Path rootPath) throws IOException {
        if (rootPath == null) throw new IllegalArgumentException("Workspace root is empty.");
        Path root = rootPath.normalize().toAbsolutePath();
        if (!Files.exists(root) || !Files.isDirectory(root)) throw new IllegalArgumentException("Workspace root does not exist or is not a directory.");
        return root.toRealPath();
    }

    public Path resolveInside(Path rootPath, String requestedPath) throws IOException {
        if (requestedPath == null || requestedPath.isBlank()) throw new IllegalArgumentException("Path is empty.");
        if (requestedPath.length() > 1024) throw new IllegalArgumentException("Path exceeds maximum length.");
        Path root = requireRoot(rootPath);
        Path requested = Path.of(requestedPath.replace("\"", "").trim());
        Path resolved = requested.isAbsolute() ? requested.normalize().toAbsolutePath() : root.resolve(requested).normalize().toAbsolutePath();
        if (Files.exists(resolved)) resolved = resolved.toRealPath();
        if (!resolved.startsWith(root)) throw new SecurityException("Path escapes workspace: " + requestedPath);
        return resolved;
    }
}