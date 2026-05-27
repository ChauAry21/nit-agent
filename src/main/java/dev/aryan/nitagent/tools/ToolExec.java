package dev.aryan.nitagent.tools;

import java.nio.file.Path;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class ToolExec {
    private static final Logger log = LoggerFactory.getLogger(ToolExec.class);
    private final Map<String, String> fileCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final FileListTool fileListTool;
    private final FileReaderTool fileReaderTool;
    private final GitDiffTool gitDiffTool;
    private final GrepTool grepTool;
    private final TestMakerTool testMakerTool;
    private final WorkspaceGuard workspaceGuard;

    public ToolExec(FileReaderTool fileReaderTool, FileListTool fileListTool, GitDiffTool gitDiffTool, GrepTool grepTool, TestMakerTool testMakerTool, WorkspaceGuard workspaceGuard) {
        this.fileListTool = fileListTool;
        this.fileReaderTool = fileReaderTool;
        this.gitDiffTool = gitDiffTool;
        this.grepTool = grepTool;
        this.testMakerTool = testMakerTool;
        this.workspaceGuard = workspaceGuard;
    }

    public String execute(String toolName, Map<String, Object> args, Path workspaceRoot) {
        log.debug("Executing tool: {} with args: {}", toolName, args);
        try {
            return switch (toolName) {
                case "list_files" -> fileListTool.list(workspaceGuard.resolveInside(workspaceRoot, (String) args.get("path")).toString());
                case "read_file" -> {
                    Path path = workspaceGuard.resolveInside(workspaceRoot, (String) args.get("path"));
                    yield fileCache.computeIfAbsent(path.toString(), fileReaderTool::read);
                }
                case "git_diff" -> gitDiffTool.diff(workspaceGuard.resolveInside(workspaceRoot, (String) args.get("repo_path")).toString());
                case "grep" -> grepTool.grep((String) args.get("pattern"), workspaceGuard.resolveInside(workspaceRoot, (String) args.get("path")).toString());
                case "generate_tests" -> "Test generation is handled via the /generate-tests endpoint.";
                default -> "Unknown tool: " + toolName;
            };
        } catch (Exception e) {
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }

    public void clearCache() {
        fileCache.clear();
    }
}