package dev.aryan.nitagent.tools;

import java.util.*;

import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class ToolExec {
    private static final Logger log = LoggerFactory.getLogger(ToolExec.class);
    private static final long MAX_FILE_SIZE_BYTES = 1024 * 1024;
    private final Map<String, String> fileCache = new java.util.concurrent.ConcurrentHashMap<>();
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
        log.debug("Executing tool: {} with args: {}", toolName, args);
        return switch (toolName) {
            case "list_files" -> fileListTool.list((String) args.get("path"));
            case "read_file" -> fileCache.computeIfAbsent((String) args.get("path"), fileReaderTool::read);
            case "git_diff" -> gitDiffTool.diff((String) args.get("repo_path"));
            case "grep" -> grepTool.grep((String) args.get("pattern"), (String) args.get("path"));
            case "generate_tests" -> "Test generation is handled via the /generate-tests endpoint.";
            default -> "Unknown tool: " + toolName;
        };
    }

    public void clearCache() {fileCache.clear();}
}
