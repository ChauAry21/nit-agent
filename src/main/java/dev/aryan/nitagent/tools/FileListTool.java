package dev.aryan.nitagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileListTool {
    private static final Logger log = LoggerFactory.getLogger(FileListTool.class);
    private static final int MAX_PATH_LENGTH = 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String list(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) return error("directory path is empty");
        if (directoryPath.length() > MAX_PATH_LENGTH) return error("directory path exceeds maximum length");

        try {
            Path root = Path.of(directoryPath).normalize();
            if (!Files.exists(root) || !Files.isDirectory(root)) return error("directory path does not exist");

            List<String> files = Files.walk(root)
                    .filter(p -> !isIgnored(p))
                    .map(Path::toString)
                    .toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("files", files);
            payload.put("count", files.size());
            log.debug("list_files returned {} paths", files.size());
            return objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            log.error("Error listing files at {}: {}", directoryPath, e.getMessage());
            return error("Error listing files: " + e.getMessage());
        }
    }

    private boolean isIgnored(Path path) {
        String s = path.toString();
        return s.contains("node_modules")
                || s.contains(".git")
                || s.contains(".venv")
                || s.contains("__pycache__")
                || s.contains(".idea")
                || s.contains("target");
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message, "files", List.of(), "count", 0));
        } catch (IOException e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\",\"files\":[],\"count\":0}";
        }
    }
}