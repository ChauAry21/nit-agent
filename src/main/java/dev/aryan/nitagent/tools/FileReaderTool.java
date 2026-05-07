package dev.aryan.nitagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileReaderTool {
    private static final Logger log = LoggerFactory.getLogger(FileReaderTool.class);
    private static final int MAX_PATH_LENGTH = 1024;
    private static final long MAX_FILE_SIZE_BYTES = 1024 * 1024;

    public String read(String filePath) {
        if (filePath == null || filePath.isBlank()) return "Error: file path is empty.";
        if (filePath.length() > MAX_PATH_LENGTH) return "Error: file path exceeds maximum length.";

        try {
            Path path = Path.of(filePath).normalize();
            if (!Files.exists(path)) return "Error: file not found: " + filePath;
            if (!Files.isRegularFile(path)) return "Error: path is not a file: " + filePath;
            if (Files.size(path) > MAX_FILE_SIZE_BYTES) {
                log.warn("File {} exceeds 1MB limit, truncating", filePath);
                byte[] bytes = Files.readAllBytes(path);
                return new String(bytes, 0, (int) MAX_FILE_SIZE_BYTES, StandardCharsets.UTF_8) + "\n[FILE TRUNCATED AT 1MB]";
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Error reading file {}: {}", filePath, e.getMessage());
            return "Error reading file: " + e.getMessage();
        }
    }
}