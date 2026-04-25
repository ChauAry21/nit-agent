package dev.aryan.nitagent.tools;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ToolExec {
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
        return switch (toolName) {
            case "list_files" -> fileListTool.list((String) args.get("path"));
            case "read_file" -> fileReaderTool.read((String) args.get("path"));
            case "git_diff" -> gitDiffTool.diff((String) args.get("repo_path"));
            case "grep" -> grepTool.grep((String) args.get("pattern"), (String) args.get("path"));
            case "generate_tests" -> testMakerTool.readForTesting((String) args.get("path"));
            default -> "Unknown tool: " + toolName;
        };
    }
}
