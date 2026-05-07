package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.file.*;
import java.util.stream.*;
import org.springframework.stereotype.Component;
import org.slf4j.*;

@Component
public class GitDiffTool {
    private static final Logger log = LoggerFactory.getLogger(GitDiffTool.class);

    public String diff(String repoPath) {
        if (repoPath == null || repoPath.isBlank()) return "Error: repository path is empty.";
        if (repoPath.length() > 1024) return "Error: repository path exceeds maximum length.";

        Path normalizedPath = Path.of(repoPath).normalize();
        File repoDir = normalizedPath.toFile();
        if (!repoDir.exists() || !repoDir.isDirectory()) return "Error: path does not exist or is not a directory.";

        String out = runGitDiff(repoDir, "HEAD");
        if (out.isBlank() || out.equals("No changes found")) {
            log.debug("HEAD diff empty, falling back to HEAD~1");
            out = runGitDiff(repoDir, "HEAD~1");
        }

        return out.isBlank() ? "No changes found." : out;
    }

    private String runGitDiff(File repoDir, String ref) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", ref)
                    .directory(repoDir)
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));
            p.waitFor();
            log.debug("git diff {} returned {} chars", ref, output.length());
            return output;
        } catch (Exception e) {
            log.error("Error running git diff {}: {}", ref, e.getMessage());
            return "";
        }
    }
}
