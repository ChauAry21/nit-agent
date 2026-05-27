package dev.aryan.nitagent.agent.test;

import dev.aryan.nitagent.tools.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class TestGenAgentService {

    private final TestMakerTool testMakerTool;
    private final FileListTool fileListTool;
    private final WorkspaceGuard workspaceGuard;

    public TestGenAgentService(TestMakerTool testMakerTool, FileListTool fileListTool, WorkspaceGuard workspaceGuard) {
        this.testMakerTool = testMakerTool;
        this.fileListTool = fileListTool;
        this.workspaceGuard = workspaceGuard;
    }

    public void generateTests(String input, boolean isRepo, SseEmitter emitter, boolean fast) throws Exception {
        input = input.replace("\"", "").trim();

        if (!isRepo) {
            Path filePath = Path.of(input).normalize().toAbsolutePath();
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                emitter.send("Error: file path does not exist or is not a file.\n");
                return;
            }
            Path root = workspaceGuard.requireRoot(filePath.getParent());
            Path safeFile = workspaceGuard.resolveInside(root, filePath.toString());
            emitter.send("Generating tests for " + safeFile + "...\n");
            emitter.send("[thinking]\n");
            String result = testMakerTool.generateTests(safeFile.toString(), root, fast);
            emitter.send("[thinking_done]\n");
            emitter.send("[done] " + result + "\n");
            boolean clean = parseClean(result);
            boolean error = result.startsWith("Error") || result.startsWith("Generated tests but failed");
            int iters = parseIterations(result);
            emitter.send("[summary] files:1 | success:" + (error ? 0 : 1)
                    + " | errors:" + (error ? 1 : 0)
                    + " | clean:" + (clean ? "1/1" : "0/1")
                    + " | avg_iterations:" + iters + ".0\n");
            return;
        }

        Path root = workspaceGuard.requireRoot(input);
        emitter.send("Scanning repository for source files...\n");
        String fileList = fileListTool.list(root.toString());
        List<String> files = fileList.lines()
                .map(String::trim)
                .filter(f -> isSourceFile(f) && !isTestFile(f))
                .toList();

        if (files.isEmpty()) {
            emitter.send("No source files found in repository.\n");
            return;
        }

        emitter.send("Found " + files.size() + " source files. Generating tests...\n");

        int totalIterations = 0;
        int cleanCount = 0;
        int errorCount = 0;

        for (String file : files) {
            Path safeFile = workspaceGuard.resolveInside(root, file);
            emitter.send("[generating] " + safeFile + "\n");
            emitter.send("[thinking]\n");
            String result = testMakerTool.generateTests(safeFile.toString(), root, fast);
            emitter.send("[thinking_done]\n");
            emitter.send("[done] " + result + "\n");

            if (result.startsWith("Error") || result.startsWith("Generated tests but failed")) {
                errorCount++;
            } else {
                int iters = parseIterations(result);
                boolean clean = parseClean(result);
                totalIterations += iters;
                if (clean) cleanCount++;
            }
        }

        int successCount = files.size() - errorCount;
        double avgIterations = successCount > 0 ? (double) totalIterations / successCount : 0;

        emitter.send("[summary] files:" + files.size()
                + " | success:" + successCount
                + " | errors:" + errorCount
                + " | clean:" + cleanCount + "/" + successCount
                + " | avg_iterations:" + String.format("%.1f", avgIterations) + "\n");
    }

    private int parseIterations(String result) {
        try {
            for (String part : result.split("\\|")) {
                if (part.trim().startsWith("iterations:")) return Integer.parseInt(part.trim().replace("iterations:", "").trim());
            }
        } catch (Exception ignored) {}
        return 1;
    }

    private boolean parseClean(String result) {
        return result.contains("clean:true");
    }

    private boolean isSourceFile(String path) {
        return path.matches(".*\\.(java|js|ts|jsx|tsx|py|go|rs|rb|cs|cpp|cc)$");
    }

    private boolean isTestFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("test") || lower.contains("spec") || lower.contains("__tests__");
    }
}