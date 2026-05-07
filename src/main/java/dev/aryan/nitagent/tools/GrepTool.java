package dev.aryan.nitagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class GrepTool {
    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final int MAX_PATTERN_LENGTH = 512;
    private static final int MAX_PATH_LENGTH = 1024;

    private final String mode;

    public GrepTool(@Value("${tool.grep.mode:auto}") String mode) {
        this.mode = mode == null ? "auto" : mode.toLowerCase(Locale.ROOT).trim();
    }

    public String grep(String pattern, String directoryPath) {
        if (pattern == null || pattern.isBlank()) return "Error: pattern is empty.";
        if (pattern.length() > MAX_PATTERN_LENGTH) return "Error: pattern exceeds maximum length.";
        if (directoryPath == null || directoryPath.isBlank()) return "Error: directory path is empty.";
        if (directoryPath.length() > MAX_PATH_LENGTH) return "Error: directory path exceeds maximum length.";

        Path root = Path.of(directoryPath).normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) return "Error: directory path does not exist.";

        if (mode.equals("java")) return javaGrep(pattern, root);

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        boolean useWsl = isWindows && (mode.equals("wsl") || mode.equals("auto")) && isWslAvailable();
        boolean useNative = !isWindows && mode.equals("auto");

        if (useWsl) return runProcessGrep(pattern, root.toString(), true);
        if (useNative) return runProcessGrep(pattern, root.toString(), false);
        if (mode.equals("wsl")) return "Error: WSL grep requested but WSL is not available.";

        log.warn("Falling back to Java grep");
        return javaGrep(pattern, root);
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
            ProcessBuilder pb = useWsl
                    ? new ProcessBuilder("wsl", "grep", "-r", "-E", "--exclude-dir=node_modules", "--exclude-dir=.git", "--exclude-dir=.venv", "--exclude-dir=__pycache__", "--exclude-dir=target", pattern, toWslPath(directoryPath))
                    : new ProcessBuilder("grep", "-r", "-E", "--exclude-dir=node_modules", "--exclude-dir=.git", "--exclude-dir=.venv", "--exclude-dir=__pycache__", "--exclude-dir=target", pattern, directoryPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            log.debug("grep returned exit code {} and {} chars", exitCode, output.length());
            return output.isBlank() ? "No matches found." : output;
        } catch (Exception e) {
            log.error("Error running process grep: {}", e.getMessage());
            return javaGrep(pattern, Path.of(directoryPath));
        }
    }

    private String javaGrep(String pattern, Path directoryPath) {
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex pattern: " + e.getMessage();
        }

        try (Stream<Path> paths = Files.walk(directoryPath)) {
            String output = paths
                    .filter(Files::isRegularFile)
                    .filter(f -> !isIgnored(f))
                    .flatMap(file -> grepFile(file, compiled))
                    .collect(Collectors.joining("\n"));
            return output.isBlank() ? "No matches found." : output;
        } catch (IOException e) {
            log.error("Error running Java grep: {}", e.getMessage());
            return "Error running grep: " + e.getMessage();
        }
    }

    private Stream<String> grepFile(Path file, Pattern pattern) {
        try {
            return Files.lines(file)
                    .filter(line -> pattern.matcher(line).find())
                    .map(line -> file + ": " + line)
                    .toList()
                    .stream();
        } catch (IOException e) {
            log.debug("Skipping unreadable file {}: {}", file, e.getMessage());
            return Stream.empty();
        }
    }

    private boolean isIgnored(Path path) {
        String s = path.toString();
        return s.contains("node_modules") || s.contains(".git") || s.contains(".venv") || s.contains("__pycache__") || s.contains("target");
    }

    private String toWslPath(String path) {
        return path.replace("C:\\", "/mnt/c/").replace("\\", "/");
    }
}