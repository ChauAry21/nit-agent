package dev.aryan.nitagent.tools;

import dev.aryan.nitagent.agent.test.CritiqueResult;
import dev.aryan.nitagent.agent.test.LanguageDetector;
import dev.aryan.nitagent.agent.test.SupportedLanguage;
import dev.aryan.nitagent.agent.test.TestPrompt;
import dev.aryan.nitagent.client.OllamaClient;
import dev.aryan.nitagent.model.Message;
import dev.aryan.nitagent.model.OllamaResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class TestMakerTool {

    private static final Logger log = LoggerFactory.getLogger(TestMakerTool.class);
    private static final int MAX_ITERATIONS = 3;
    private static final int MAX_PROJECT_ROOT_DEPTH = 8;
    private static final int MAX_IMPORTED_LINES = 200;
    private static final int MAX_IMPORTED_CHARS = 40_000;
    private static final long MAX_FILE_SIZE_BYTES = 1024 * 1024;
    private static final int MAX_PATH_LENGTH = 1024;

    private final OllamaClient ollamaClient;

    public TestMakerTool(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public String generateTests(String filePath, boolean fast) {
        if (filePath == null || filePath.isBlank()) return "Error reading file: path is empty.";
        if (filePath.length() > MAX_PATH_LENGTH) return "Error reading file: path exceeds maximum length.";

        String sourceCode;
        try {
            Path sourcePath = Path.of(filePath).normalize();

            if (!Files.exists(sourcePath)) return "Skipped unreadable file: file does not exist.";
            if (!Files.isRegularFile(sourcePath)) return "Skipped unreadable file: path is not a file.";
            if (Files.size(sourcePath) > MAX_FILE_SIZE_BYTES) return "Skipped unreadable file: file exceeds 1MB limit.";

            sourceCode = Files.readString(sourcePath);
        } catch (IOException e) {
            log.error("Error reading file {}: {}", filePath, e.getMessage());
            return "Skipped unreadable file: " + e.getMessage();
        }

        String fileName = Path.of(filePath).getFileName().toString();
        SupportedLanguage language = LanguageDetector.detect(fileName, sourceCode);
        String dependencies = readDependencyFile(filePath, language);
        String importedSources = readImportedSources(filePath, sourceCode, language);
        String context = buildContext(fileName, language.name, sourceCode, dependencies, importedSources);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(TestPrompt.buildSystem()));
        messages.add(Message.user(TestPrompt.buildGeneration(language.name, context)));

        String testCode = null;
        int iterations = 0;
        boolean clean = false;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            iterations = i + 1;
            OllamaResponse response = ollamaClient.chat(messages, List.of(), fast);

            testCode = response.message().content();
            if (testCode == null || testCode.isBlank()) return "Model returned empty test output.";
            testCode = stripCodeFence(testCode);
            messages.add(Message.assistant(testCode, null));

            messages.add(Message.user(TestPrompt.buildCritique(testCode, sourceCode, importedSources)));

            OllamaResponse critiqueResponse = ollamaClient.chat(messages, List.of(), true);
            String critique = critiqueResponse.message().content();
            if (critique == null) critique = "";

            clean = CritiqueResult.isClean(critique);
            if (clean) break;

            messages.add(Message.assistant(critique, null));
            messages.add(Message.user(TestPrompt.buildFix()));
        }

        String testFilePath = resolveTestFilePath(filePath, fileName, language);

        try {
            Path testPath = Path.of(testFilePath);
            Files.createDirectories(testPath.getParent());
            Files.writeString(testPath, testCode);
            return "Tests written to: " + testFilePath + " | iterations:" + iterations + " | clean:" + clean;
        } catch (IOException e) {
            log.error("Failed to write test file {}: {}", testFilePath, e.getMessage());
            return "Generated tests but failed to write file: " + e.getMessage() + " | iterations:" + iterations + " | clean:" + clean;
        }
    }

    private String stripCodeFence(String text) {
        return text.replaceAll("(?s)```[a-zA-Z0-9]*\\s*", "").replaceAll("```", "").trim();
    }

    private String buildContext(String fileName, String language, String sourceCode, String dependencies, String importedSources) {
        StringBuilder sb = new StringBuilder();
        sb.append("FILE: ").append(fileName).append("\n\n");
        sb.append("LANGUAGE: ").append(language).append("\n\n");
        sb.append("SOURCE CODE:\n").append(sourceCode).append("\n\n");
        if (!dependencies.isBlank()) sb.append("DEPENDENCY FILE:\n").append(dependencies).append("\n\n");
        if (!importedSources.isBlank()) sb.append("IMPORTED SOURCE FILES:\n").append(importedSources).append("\n\n");
        return sb.toString();
    }

    private String readDependencyFile(String filePath, SupportedLanguage language) {
        if (language.depFile == null) return "";

        Path dir = Path.of(filePath).normalize().getParent();

        try {
            Path root = findProjectRoot(dir, language);
            if (root == null) return "";

            Path depPath = root.resolve(language.depFile);
            if (!Files.exists(depPath) || !Files.isRegularFile(depPath)) return "";
            if (Files.size(depPath) > MAX_FILE_SIZE_BYTES) return "[DEPENDENCY FILE SKIPPED: exceeds 1MB]";

            return Files.readString(depPath);
        } catch (IOException e) {
            log.warn("Error reading dependency file for {}: {}", filePath, e.getMessage());
            return "";
        }
    }

    private Path findProjectRoot(Path dir, SupportedLanguage language) {
        if (dir == null || language.depFile == null) return null;

        Path current = dir;

        for (int i = 0; i < MAX_PROJECT_ROOT_DEPTH; i++) {
            if (Files.exists(current.resolve(language.depFile))) return current;

            Path parent = current.getParent();
            if (parent == null) break;
            current = parent;
        }

        return null;
    }

    private String readImportedSources(String filePath, String sourceCode, SupportedLanguage language) {
        StringBuilder sb = new StringBuilder();
        Path sourceDir = Path.of(filePath).normalize().getParent();

        if (sourceDir == null) return "";

        if (language == SupportedLanguage.JAVA) {
            Path root = findProjectRoot(sourceDir, SupportedLanguage.JAVA);
            if (root == null) return "";

            sourceCode.lines()
                    .filter(line -> line.startsWith("import " + detectBasePackage(sourceCode)))
                    .map(line -> line.replace("import ", "").replace(";", "").trim())
                    .forEach(fqn -> appendJavaImport(sb, root, fqn));
        } else if (language == SupportedLanguage.JAVASCRIPT || language == SupportedLanguage.TYPESCRIPT || language == SupportedLanguage.REACT) {
            sourceCode.lines()
                    .filter(line -> line.contains("from \"./") || line.contains("from '../") || line.contains("from './") || line.contains("from \"../"))
                    .forEach(line -> appendJsImport(sb, sourceDir, line));
        }

        String imported = sb.toString();

        if (imported.length() > MAX_IMPORTED_CHARS) {
            return imported.substring(0, MAX_IMPORTED_CHARS) + "\n[IMPORTED SOURCES TRUNCATED]";
        }

        return imported;
    }

    private void appendJavaImport(StringBuilder sb, Path root, String fqn) {
        String relativePath = fqn.replace(".", File.separator) + ".java";
        Path candidate = root.resolve("src/main/java/" + relativePath);

        appendImportedFile(sb, candidate, fqn);
    }

    private void appendJsImport(StringBuilder sb, Path sourceDir, String line) {
        String imp = line.replaceAll(".*from ['\"]([^'\"]+)['\"].*", "$1");

        for (String ext : List.of("", ".js", ".ts", ".jsx", ".tsx")) {
            Path candidate = sourceDir.resolve(imp + ext).normalize();

            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                appendImportedFile(sb, candidate, imp);
                return;
            }
        }
    }

    private void appendImportedFile(StringBuilder sb, Path candidate, String label) {
        try {
            if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) return;
            if (Files.size(candidate) > MAX_FILE_SIZE_BYTES) {
                sb.append("// ").append(label).append("\n");
                sb.append("[IMPORTED FILE SKIPPED: exceeds 1MB]\n\n");
                return;
            }

            List<String> lines = Files.readAllLines(candidate);
            sb.append("// ").append(label).append("\n");
            lines.stream().limit(MAX_IMPORTED_LINES).forEach(line -> sb.append(line).append("\n"));

            if (lines.size() > MAX_IMPORTED_LINES) {
                sb.append("[IMPORTED FILE TRUNCATED AT ").append(MAX_IMPORTED_LINES).append(" LINES]\n");
            }

            sb.append("\n");
        } catch (IOException e) {
            log.debug("Skipping imported file {}: {}", candidate, e.getMessage());
        }
    }

    private String detectBasePackage(String sourceCode) {
        return sourceCode.lines()
                .filter(line -> line.startsWith("package "))
                .map(line -> {
                    String[] parts = line.replace("package ", "").replace(";", "").trim().split("\\.");
                    return parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];
                })
                .findFirst()
                .orElse("dev.aryan");
    }

    private String resolveTestFilePath(String originalPath, String fileName, SupportedLanguage language) {
        Path original = Path.of(originalPath).normalize();
        Path dir = original.getParent();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";

        if (dir == null) dir = Path.of(".");

        return switch (language) {
            case JAVA -> replaceMainWithTest(dir).resolve(baseName + "Test.java").toString();
            case PYTHON -> dir.resolve("test_" + baseName + ".py").toString();
            case GO -> dir.resolve(baseName + "_test.go").toString();
            case RUST -> dir.resolve(baseName + "_test.rs").toString();
            default -> dir.resolve(baseName + ".test" + ext).toString();
        };
    }

    private Path replaceMainWithTest(Path dir) {
        String path = dir.toString();
        String replaced = path.replace(File.separator + "main" + File.separator, File.separator + "test" + File.separator);

        if (replaced.equals(path)) {
            replaced = path.replace("/main/", "/test/").replace("\\main\\", "\\test\\");
        }

        return Path.of(replaced);
    }
}