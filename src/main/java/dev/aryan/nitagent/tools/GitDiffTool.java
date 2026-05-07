package dev.aryan.nitagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class GitDiffTool {
    private static final Logger log = LoggerFactory.getLogger(GitDiffTool.class);
    private static final int MAX_PATH_LENGTH = 1024;
    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[;|`]|&&");

    private final String trustedRoot;

    public GitDiffTool(@Value("${tool.trusted-root:}") String trustedRoot) {
        this.trustedRoot = trustedRoot == null ? "" : trustedRoot.trim();
    }

    public String diff(String repoPath) {
        return diff(repoPath, "HEAD", "");
    }

    public String diff(String repoPath, String baseRef, String targetRef) {
        String validationError = validateRepoPath(repoPath);
        if (validationError != null) return validationError;

        File repoDir = Path.of(repoPath).normalize().toFile();
        String out = runGitDiff(repoDir, baseRef, targetRef);
        if (out.isBlank() || out.equals("No changes found.")) {
            log.debug("Primary git diff empty, falling back to previous commit");
            out = runGitDiff(repoDir, "HEAD~1", "HEAD");
        }

        return out.isBlank() ? "No changes found." : out;
    }

    private String validateRepoPath(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) return "Error: repository path is empty.";
        if (repoPath.length() > MAX_PATH_LENGTH) return "Error: repository path exceeds maximum length.";
        if (UNSAFE_PATH_CHARS.matcher(repoPath).find()) return "Error: repository path contains unsafe characters.";

        Path normalized = Path.of(repoPath).toAbsolutePath().normalize();
        if (!Files.exists(normalized) || !Files.isDirectory(normalized)) return "Error: path does not exist or is not a directory.";
        if (!Files.exists(normalized.resolve(".git"))) return "Error: path is not a git repository.";

        if (!trustedRoot.isBlank()) {
            Path trusted = Path.of(trustedRoot).toAbsolutePath().normalize();
            if (!normalized.startsWith(trusted)) return "Error: repository path is outside the trusted root.";
        }

        return null;
    }

    private String runGitDiff(File repoDir, String baseRef, String targetRef) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "diff"));
            if (targetRef == null || targetRef.isBlank()) {
                command.add(baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef);
            } else {
                command.add((baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef) + ".." + targetRef);
            }

            ProcessBuilder pb = new ProcessBuilder(command).directory(repoDir).redirectErrorStream(true);
            Process p = pb.start();
            String output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));
            int exitCode = p.waitFor();
            log.debug("{} returned exit code {} and {} chars", command, exitCode, output.length());
            return output.isBlank() ? "No changes found." : output;
        } catch (Exception e) {
            log.error("Error running git diff: {}", e.getMessage());
            return "Error running git diff: " + e.getMessage();
        }
    }
}