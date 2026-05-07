package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Component;
import org.slf4j.*;

@Component
public class FileReaderTool {
    private static final Logger log = LoggerFactory.getLogger(FileReaderTool.class);
    private static final long MAX_FILE_SIZE_BYTES = 1024 * 1024;

    public String read(String filePath) {
        if (filePath == null || filePath.isBlank()) return "Error: file path is empty.";
        if (filePath.length() > 1024) return "Error: file path exceeds max length.";
        try {
            Path path = Path.of(filePath).normalize();
            if (!Files.exists(path)) return "Error: file not found: " + filePath;
            if (Files.size(path) > MAX_FILE_SIZE_BYTES) {
                log.warn("File {} exceeds 1MB limit, truncating", filePath);
                return new String(Files.readAllBytes(path), 0, (int) MAX_FILE_SIZE_BYTES) + "\n[FILE TRUNCATED AT 1MB]";
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Error reading file {}: {}", filePath, e.getMessage());
            return "Error reading file: " + e.getMessage();
        }
    }
}