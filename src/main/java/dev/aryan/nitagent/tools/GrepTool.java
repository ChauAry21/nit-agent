package dev.aryan.nitagent.tools;

import org.springframework.stereotype.Component;
import java.io.*;
import java.util.List;

@Component
public class GrepTool {

    public String grep(String pattern, String directoryPath) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder(
                        "wsl", "grep", "-r", "-E", pattern,
                        toWslPath(directoryPath)
                );
            } else {
                pb = new ProcessBuilder(
                        "grep", "-r", "-E", pattern, directoryPath
                );
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            System.err.println("GREP COMMAND: " + pb.command());
            System.err.println("GREP OUTPUT: [" + output + "]");
            process.waitFor();
            return output.isBlank() ? "No matches found." : output;
        } catch (Exception e) {
            return "Error running grep: " + e.getMessage();
        }
    }

    private String toWslPath(String windowsPath) {
        return windowsPath
                .replace("C:\\", "/mnt/c/")
                .replace("\\", "/");
    }
}