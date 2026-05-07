package dev.aryan.nitagent.tools;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.*;
import java.util.stream.Collectors;

@Component
public class GrepTool {

    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);

    public String grep(String pattern, String directoryPath) {
        if (pattern == null || pattern.isBlank()) return "Error: pattern is empty.";
        if (directoryPath == null || directoryPath.isBlank()) return "Error: directory path is empty.";

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        boolean wslAvailable = isWindows && isWslAvailable();

        if (isWindows && wslAvailable) {
            return runProcessGrep(pattern, directoryPath, true);
        } else if (!isWindows) {
            return runProcessGrep(pattern, directoryPath, false);
        } else {
            log.warn("WSL not available on Windows, falling back to Java grep");
            return javaGrep(pattern, directoryPath);
        }
    }

    private boolean isWslAvailable() {
        try {
            Process p = new ProcessBuilder("wsl", "--status").start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runProcessGrep(String pattern, String directoryPath, boolean useWsl) {
        try {
            ProcessBuilder pb;
            if (useWsl) {
                pb = new ProcessBuilder("wsl", "grep", "-r", "-E",
                        "--exclude-dir=node_modules", "--exclude-dir=.git",
                        "--exclude-dir=.venv", "--exclude-dir=__pycache__",
                        pattern, toWslPath(directoryPath));
            } else {
                pb = new ProcessBuilder("grep", "-r", "-E",
                        "--exclude-dir=node_modules", "--exclude-dir=.git",
                        "--exclude-dir=.venv", "--exclude-dir=__pycache__",
                        pattern, directoryPath);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            log.debug("grep output: {} chars", output.length());
            return output.isBlank() ? "No matches found." : output;
        } catch (Exception e) {
            log.error("Error running grep: {}", e.getMessage());
            return javaGrep(pattern, directoryPath);
        }
    }

    private String javaGrep(String pattern, String directoryPath) {
        try {
            return Files.walk(Path.of(directoryPath))
                    .filter(Files::isRegularFile)
                    .filter(f -> !f.toString().contains("node_modules"))
                    .filter(f -> !f.toString().contains(".git"))
                    .filter(f -> !f.toString().contains(".venv"))
                    .filter(f -> !f.toString().contains("__pycache__"))
                    .flatMap(file -> {
                        try {
                            return Files.lines(file)
                                    .filter(line -> line.matches(".*(" + pattern + ").*"))
                                    .map(line -> file + ": " + line);
                        } catch (IOException e) {
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Error running grep: " + e.getMessage();
        }
    }

    private String toWslPath(String windowsPath) {
        return windowsPath.replace("C:\\", "/mnt/c/").replace("\\", "/");
    }
}