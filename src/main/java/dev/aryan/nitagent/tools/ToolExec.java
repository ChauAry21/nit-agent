package dev.aryan.nitagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolExec {

    private static final Logger log = LoggerFactory.getLogger(ToolExec.class);

    private final Map<String, String> fileCache = new ConcurrentHashMap<>();
    private final FileListTool fileListTool;
    private final FileReaderTool fileReaderTool;
    private final GitDiffTool gitDiffTool;
    private final GrepTool grepTool;
    private final TestMakerTool testMakerTool;

    public ToolExec(FileReaderTool fileReaderTool, FileListTool fileListTool, GitDiffTool gitDiffTool, GrepTool grepTool, TestMakerTool testMakerTool) {
        this.fileListTool = fileListTool;
        this.fileReaderTool = fileReaderTool;
        this.gitDiffTool = gitDiffTool;
        this.grepTool = grepTool;
        this.testMakerTool = testMakerTool;
    }

    public String execute(String toolName, Map<String, Object> args) {
        if (toolName == null || toolName.isBlank()) return "Unknown tool: empty tool name";
        if (args == null) return "Error: missing tool arguments";

        log.debug("Executing tool: {} with args: {}", toolName, args);

        return switch (toolName) {
            case "list_files" -> fileListTool.list(stringArg(args, "path"));
            case "read_file" -> readCached(stringArg(args, "path"));
            case "git_diff" -> gitDiffTool.diff(
                    stringArg(args, "repo_path"),
                    stringArgOrDefault(args, "base_ref", "HEAD"),
                    stringArgOrDefault(args, "target_ref", "")
            );
            case "grep" -> grepTool.grep(stringArg(args, "pattern"), stringArg(args, "path"));
            case "generate_tests" -> "Test generation is handled via the /generate-tests endpoint.";
            default -> "Unknown tool: " + toolName;
        };
    }

    public void clearCache() {
        fileCache.clear();
    }

    private String readCached(String path) {
        if (path == null || path.isBlank()) return "Error: file path is empty.";
        return fileCache.computeIfAbsent(path, fileReaderTool::read);
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String stringArgOrDefault(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}