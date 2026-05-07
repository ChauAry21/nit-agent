package dev.aryan.nitagent.agent.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aryan.nitagent.tools.FileListTool;
import dev.aryan.nitagent.tools.TestMakerTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.StreamSupport;

@Service
public class TestGenAgentService {

    private static final Logger log = LoggerFactory.getLogger(TestGenAgentService.class);
    private static final int MAX_PARALLEL_GENERATIONS = 5;

    private final TestMakerTool testMakerTool;
    private final FileListTool fileListTool;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TestGenAgentService(TestMakerTool testMakerTool, FileListTool fileListTool) {
        this.testMakerTool = testMakerTool;
        this.fileListTool = fileListTool;
    }

    public void generateTests(String input, boolean isRepo, SseEmitter emitter, boolean fast) throws Exception {
        generateTests(input, isRepo, emitter, fast, false);
    }

    public void generateTests(String input, boolean isRepo, SseEmitter emitter, boolean fast, boolean parallel) throws Exception {
        input = sanitizeBody(input);

        if (input.isBlank()) {
            emitter.send("Error: path is empty.\n");
            return;
        }

        if (!isRepo) {
            generateSingleFile(input, emitter, fast);
            return;
        }

        emitter.send("Scanning repository for source files...\n");
        String fileList = fileListTool.list(input);
        List<String> files = parseFiles(fileList).stream()
                .map(String::trim)
                .filter(f -> isSourceFile(f) && !isTestFile(f))
                .toList();

        if (files.isEmpty()) {
            emitter.send("No source files found in repository.\n");
            return;
        }

        emitter.send("Found " + files.size() + " source files. Generating tests...\n");

        if (parallel) {
            generateRepoParallel(files, emitter, fast);
        } else {
            generateRepoSequential(files, emitter, fast);
        }
    }

    private void generateSingleFile(String input, SseEmitter emitter, boolean fast) throws Exception {
        emitter.send("Generating tests for " + input + "...\n");
        emitter.send("[thinking]\n");
        String result = testMakerTool.generateTests(input, fast);
        emitter.send("[thinking_done]\n");
        emitter.send("[done] " + result + "\n");

        boolean clean = parseClean(result);
        boolean error = isErrorResult(result);
        int iterations = parseIterations(result);

        emitter.send("[summary] files:1 | success:" + (error ? 0 : 1)
                + " | errors:" + (error ? 1 : 0)
                + " | clean:" + (clean ? "1/1" : "0/1")
                + " | avg_iterations:" + iterations + ".0\n");
    }

    private void generateRepoSequential(List<String> files, SseEmitter emitter, boolean fast) throws Exception {
        int totalIterations = 0;
        int cleanCount = 0;
        int errorCount = 0;
        int skippedCount = 0;

        for (String file : files) {
            try {
                emitter.send("[generating] " + file + "\n");
                emitter.send("[thinking]\n");
                String result = testMakerTool.generateTests(file, fast);
                emitter.send("[thinking_done]\n");
                emitter.send("[done] " + result + "\n");

                if (isErrorResult(result)) {
                    errorCount++;
                    if (result.startsWith("Skipped")) skippedCount++;
                    continue;
                }

                int iterations = parseIterations(result);
                boolean clean = parseClean(result);
                totalIterations += iterations;
                if (clean) cleanCount++;
            } catch (Exception e) {
                errorCount++;
                skippedCount++;
                log.error("Failed generating tests for {}: {}", file, e.getMessage());
                emitter.send("[error] " + file + " | " + e.getMessage() + "\n");
            }
        }

        sendSummary(files.size(), totalIterations, cleanCount, errorCount, skippedCount, emitter);
    }

    private void generateRepoParallel(List<String> files, SseEmitter emitter, boolean fast) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_GENERATIONS, files.size()));

        try {
            List<CompletableFuture<FileResult>> futures = files.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> generateFileResult(file, fast), executor))
                    .toList();

            int totalIterations = 0;
            int cleanCount = 0;
            int errorCount = 0;
            int skippedCount = 0;

            for (CompletableFuture<FileResult> future : futures) {
                FileResult fileResult = future.join();

                emitter.send("[done] " + fileResult.file + " | " + fileResult.result + "\n");

                if (isErrorResult(fileResult.result)) {
                    errorCount++;
                    if (fileResult.result.startsWith("Skipped")) skippedCount++;
                    continue;
                }

                int iterations = parseIterations(fileResult.result);
                boolean clean = parseClean(fileResult.result);
                totalIterations += iterations;
                if (clean) cleanCount++;
            }

            sendSummary(files.size(), totalIterations, cleanCount, errorCount, skippedCount, emitter);
        } finally {
            executor.shutdownNow();
        }
    }

    private FileResult generateFileResult(String file, boolean fast) {
        try {
            String result = testMakerTool.generateTests(file, fast);
            return new FileResult(file, result);
        } catch (Exception e) {
            log.error("Failed generating tests for {}: {}", file, e.getMessage());
            return new FileResult(file, "Skipped unreadable or failed file: " + e.getMessage());
        }
    }

    private void sendSummary(int totalFiles, int totalIterations, int cleanCount, int errorCount, int skippedCount, SseEmitter emitter) throws Exception {
        int successCount = totalFiles - errorCount;
        double avgIterations = successCount == 0 ? 0 : (double) totalIterations / successCount;

        emitter.send("[summary] files:" + totalFiles
                + " | success:" + successCount
                + " | errors:" + errorCount
                + " | skipped:" + skippedCount
                + " | clean:" + cleanCount + "/" + successCount
                + " | avg_iterations:" + String.format("%.1f", avgIterations)
                + "\n");
    }

    private List<String> parseFiles(String fileList) {
        try {
            JsonNode root = objectMapper.readTree(fileList);
            JsonNode filesNode = root.get("files");

            if (filesNode == null || !filesNode.isArray()) {
                return fileList.lines().toList();
            }

            return StreamSupport.stream(filesNode.spliterator(), false)
                    .map(JsonNode::asText)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to parse file list as JSON, falling back to line parsing: {}", e.getMessage());
            return fileList.lines().toList();
        }
    }

    private String sanitizeBody(String body) {
        if (body == null) return "";
        return body.replace("\"", "").trim();
    }

    private boolean isSourceFile(String file) {
        return file.endsWith(".java")
                || file.endsWith(".js")
                || file.endsWith(".ts")
                || file.endsWith(".jsx")
                || file.endsWith(".tsx")
                || file.endsWith(".py")
                || file.endsWith(".go")
                || file.endsWith(".rs")
                || file.endsWith(".rb")
                || file.endsWith(".cs")
                || file.endsWith(".cpp")
                || file.endsWith(".cc")
                || file.endsWith(".cxx")
                || file.endsWith(".sh")
                || file.endsWith(".bash")
                || file.endsWith(".php")
                || file.endsWith(".swift")
                || file.endsWith(".kt");
    }

    private boolean isTestFile(String file) {
        String lower = file.toLowerCase();
        return lower.contains("/test/")
                || lower.contains("\\test\\")
                || lower.contains(".test.")
                || lower.contains(".spec.")
                || lower.endsWith("test.java")
                || lower.startsWith("test_")
                || lower.endsWith("_test.go")
                || lower.endsWith("_test.rs");
    }

    private boolean isErrorResult(String result) {
        return result.startsWith("Error")
                || result.startsWith("Skipped")
                || result.startsWith("Generated tests but failed")
                || result.startsWith("Model returned empty");
    }

    private int parseIterations(String result) {
        try {
            int start = result.indexOf("iterations:");
            if (start == -1) return 1;
            String sub = result.substring(start + "iterations:".length()).trim();
            return Integer.parseInt(sub.split("\\s|\\|")[0].trim());
        } catch (Exception e) {
            return 1;
        }
    }

    private boolean parseClean(String result) {
        int start = result.indexOf("clean:");
        if (start == -1) return false;
        String sub = result.substring(start + "clean:".length()).trim();
        return sub.startsWith("true");
    }

    private record FileResult(String file, String result) {}
}