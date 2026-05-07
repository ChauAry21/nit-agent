package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.slf4j.*;

@Component
public class FileListTool {
    private static final Logger log = LoggerFactory.getLogger(FileListTool.class);

    public String list(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) return "Error: directory path is empty.";
        if (directoryPath.length() > 1024) return "Error: directory path exceeds maximum length.";

        try {
            String out = Files.walk(Path.of(directoryPath))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(".git"))
                    .filter(p -> !p.toString().contains(".venv"))
                    .filter(p -> !p.toString().contains("__pycache__"))
                    .filter(p -> !p.toString().contains(".idea"))
                    .map(p -> p.toString())
                    .collect(Collectors.joining("\n"));

            log.debug("list_files returned {} paths", out.lines().count());
            return out;
        } catch (IOException e) {
            log.error("Error listing files at {}: {}", directoryPath, e.getMessage());
            return "Error listing files: " + e.getMessage();
        }
    }
}