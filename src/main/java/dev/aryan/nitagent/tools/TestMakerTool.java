package dev.aryan.nitagent.tools;

import java.util.*;
import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import dev.aryan.nitagent.agent.test.*;
import dev.aryan.nitagent.client.OllamaClient;
import dev.aryan.nitagent.model.*;

@Component
public class TestMakerTool {

    private static final Logger log = LoggerFactory.getLogger(TestMakerTool.class);
    private static final int MAX_ITERATIONS = 3;
    private static final int MAX_PROJECT_ROOT_DEPTH = 8;

    private final OllamaClient ollamaClient;

    public TestMakerTool(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public String generateTests(String filePath, boolean fast) {
        String sourceCode;
        try {
            sourceCode = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            log.error("Error reading file {}: {}", filePath, e.getMessage());
            return "Error reading file: " + e.getMessage();
        }

        String fileName = Path.of(filePath).getFileName().toString();
        SupportedLanguage language = detectLanguage(fileName, sourceCode);
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
            testCode = testCode.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
            messages.add(Message.assistant(testCode, null));

            messages.add(Message.user(TestPrompt.buildCritique(testCode, sourceCode, importedSources)));

            OllamaResponse critiqueResponse = ollamaClient.chat(messages, List.of(), true);
            String critique = critiqueResponse.message().content();

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

    private String buildContext(String fileName, String language, String sourceCode, String dependencies, String importedSources) {
        StringBuilder sb = new StringBuilder();
        sb.append("FILE: ").append(fileName).append("\n\n");
        sb.append("SOURCE CODE:\n").append(sourceCode).append("\n\n");
        if (!dependencies.isBlank()) sb.append("DEPENDENCY FILE:\n").append(dependencies).append("\n\n");
        if (!importedSources.isBlank()) sb.append("IMPORTED SOURCE FILES:\n").append(importedSources).append("\n\n");
        return sb.toString();
    }

    private String readDependencyFile(String filePath, SupportedLanguage language) {
        if (language.depFile == null) return "";
        Path dir = Path.of(filePath).getParent();
        try {
            Path root = findProjectRoot(dir, language);
            if (root == null) return "";
            Path depPath = root.resolve(language.depFile);
            return Files.exists(depPath) ? Files.readString(depPath) : "";
        } catch (IOException e) {
            log.error("Error reading dependency file: {}", e.getMessage());
            return "";
        }
    }

    private Path findProjectRoot(Path dir, SupportedLanguage language) {
        if (language.depFile == null) return null;
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
        Path sourceDir = Path.of(filePath).getParent();

        if (language == SupportedLanguage.JAVA) {
            sourceCode.lines()
                    .filter(l -> l.startsWith("import " + detectBasePackage(sourceCode)))
                    .map(l -> l.replace("import ", "").replace(";", "").trim())
                    .forEach(fqn -> {
                        String relativePath = fqn.replace(".", File.separator) + ".java";
                        try {
                            Path root = findProjectRoot(sourceDir, SupportedLanguage.JAVA);
                            if (root == null) return;
                            Path candidate = root.resolve("src/main/java/" + relativePath);
                            if (Files.exists(candidate)) {
                                sb.append("// ").append(fqn).append("\n");
                                sb.append(Files.readString(candidate)).append("\n\n");
                            }
                        } catch (IOException ignored) {}
                    });
        } else if (language == SupportedLanguage.JAVASCRIPT || language == SupportedLanguage.TYPESCRIPT || language == SupportedLanguage.REACT) {
            sourceCode.lines()
                    .filter(l -> l.contains("from \"./") || l.contains("from '../") || l.contains("from './"))
                    .forEach(l -> {
                        String imp = l.replaceAll(".*from ['\"]([^'\"]+)['\"].*", "$1");
                        try {
                            for (String ext : List.of(".js", ".ts", ".jsx", ".tsx")) {
                                Path candidate = sourceDir.resolve(imp + ext);
                                if (Files.exists(candidate)) {
                                    sb.append("// ").append(imp).append("\n");
                                    sb.append(Files.readString(candidate)).append("\n\n");
                                    break;
                                }
                            }
                        } catch (IOException ignored) {}
                    });
        }

        return sb.toString();
    }

    private String detectBasePackage(String sourceCode) {
        return sourceCode.lines()
                .filter(l -> l.startsWith("package "))
                .map(l -> {
                    String[] parts = l.replace("package ", "").replace(";", "").trim().split("\\.");
                    return parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];
                })
                .findFirst()
                .orElse("dev.aryan");
    }

    private SupportedLanguage detectLanguage(String fileName, String sourceCode) {
        String firstLine = sourceCode.lines().findFirst().orElse("");
        if (firstLine.contains("python")) return SupportedLanguage.PYTHON;
        if (firstLine.contains("node") || firstLine.contains("deno")) return SupportedLanguage.JAVASCRIPT;
        if (firstLine.contains("ruby")) return SupportedLanguage.RUBY;
        if (firstLine.contains("bash") || firstLine.contains("sh")) return SupportedLanguage.BASH;

        if (sourceCode.contains("package ") && sourceCode.contains("func ")) return SupportedLanguage.GO;
        if (sourceCode.contains("fn main()") || sourceCode.contains("pub fn ")) return SupportedLanguage.RUST;
        if (sourceCode.contains("import java") || (sourceCode.contains("package ") && sourceCode.contains("class "))) return SupportedLanguage.JAVA;
        if (sourceCode.contains("using System") || sourceCode.contains("namespace ")) return SupportedLanguage.CSHARP;
        if (sourceCode.contains("#include") && (sourceCode.contains("std::") || sourceCode.contains("cout"))) return SupportedLanguage.CPP;
        if (sourceCode.contains("import React") || sourceCode.contains("from 'react'") || sourceCode.contains("from \"react\"")) return SupportedLanguage.REACT;
        if (sourceCode.contains("export default") || sourceCode.contains("export const")) return SupportedLanguage.TYPESCRIPT;
        if (sourceCode.contains("require(") || sourceCode.contains("module.exports")) return SupportedLanguage.JAVASCRIPT;
        if (sourceCode.contains("def ") && sourceCode.contains("import ")) return SupportedLanguage.PYTHON;

        if (!fileName.contains(".")) return SupportedLanguage.UNKNOWN;
        return switch (fileName.substring(fileName.lastIndexOf('.') + 1)) {
            case "java" -> SupportedLanguage.JAVA;
            case "js" -> SupportedLanguage.JAVASCRIPT;
            case "ts" -> SupportedLanguage.TYPESCRIPT;
            case "jsx", "tsx" -> SupportedLanguage.REACT;
            case "py" -> SupportedLanguage.PYTHON;
            case "go" -> SupportedLanguage.GO;
            case "rs" -> SupportedLanguage.RUST;
            case "rb" -> SupportedLanguage.RUBY;
            case "cs" -> SupportedLanguage.CSHARP;
            case "cpp", "cc", "cxx" -> SupportedLanguage.CPP;
            case "sh", "bash" -> SupportedLanguage.BASH;
            case "php" -> SupportedLanguage.PHP;
            case "swift" -> SupportedLanguage.SWIFT;
            case "kt" -> SupportedLanguage.KOTLIN;
            default -> SupportedLanguage.UNKNOWN;
        };
    }

    private String resolveTestFilePath(String originalPath, String fileName, SupportedLanguage language) {
        String dir = Path.of(originalPath).getParent().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";

        return switch (language) {
            case JAVA -> dir.replace("main", "test") + "\\" + baseName + "Test.java";
            case PYTHON -> dir + "\\test_" + baseName + ".py";
            case GO -> dir + "\\" + baseName + "_test.go";
            case RUST -> dir + "\\" + baseName + "_test.rs";
            default -> dir + "\\" + baseName + ".test" + ext;
        };
    }
}