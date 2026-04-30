package dev.aryan.nitagent.agent;

import dev.aryan.nitagent.tools.FileListTool;
import dev.aryan.nitagent.tools.TestMakerTool;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Service
public class TestGenAgentService {

    private final TestMakerTool testMakerTool;
    private final FileListTool fileListTool;

    public TestGenAgentService(TestMakerTool testMakerTool, FileListTool fileListTool) {
        this.testMakerTool = testMakerTool;
        this.fileListTool = fileListTool;
    }

    public void generateTests(String input, boolean isRepo, SseEmitter emitter) throws Exception {
        input = input.replace("\"", "").trim();

        if (!isRepo) {
            emitter.send("Generating tests for " + input + "...\n");
            emitter.send("[thinking]\n");
            String result = testMakerTool.generateTests(input);
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

        emitter.send("Scanning repository for source files...\n");
        String fileList = fileListTool.list(input);
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
            emitter.send("[generating] " + file + "\n");
            emitter.send("[thinking]\n");
            String result = testMakerTool.generateTests(file);
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
                if (part.trim().startsWith("iterations:")) {
                    return Integer.parseInt(part.trim().replace("iterations:", "").trim());
                }
            }
        } catch (Exception ignored) {}
        return 1;
    }

    private boolean parseClean(String result) {
        return result.contains("clean:true");
    }

    private String parseSummaryFromResult(String result) {
        return result.contains("|") ? result.substring(result.indexOf('|') + 1).trim() : "";
    }

    private boolean isSourceFile(String path) {
        return path.matches(".*\\.(java|js|ts|jsx|tsx|py|go|rs|rb|cs|cpp|cc)$");
    }

    private boolean isTestFile(String path) {
        String lower = path.toLowerCase();
        return lower.contains("test") || lower.contains("spec") || lower.contains("__tests__");
    }
}