package dev.aryan.nitagent.tools;

import java.io.*;
import java.util.stream.*;
import org.springframework.stereotype.Component;

@Component
public class GitDiffTool {

    public String diff(String repoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD")
                    .directory(new java.io.File(repoPath))
                    .redirectErrorStream(true);

            Process p = pb.start();

            String output = new BufferedReader(new InputStreamReader(p.getInputStream()))
                    .lines()
                    .collect(Collectors.joining("\n"));

            return output.isBlank() ? "No changes found" : output;
        } catch (Exception e) {return "Error running git diff: " + e.getMessage();}
    }
}
