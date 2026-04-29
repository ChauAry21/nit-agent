package dev.aryan.nitagent.tools;

import java.util.*;
import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Component;
import dev.aryan.nitagent.client.OllamaClient;
import dev.aryan.nitagent.model.*;

@Component
public class TestMakerTool {

    private final OllamaClient ollamaClient;
    private static final int MAX_ITERATIONS = 3;

    public TestMakerTool(OllamaClient ollamaClient) {
        this.ollamaClient = ollamaClient;
    }

    public String generateTests(String filePath) {
        String sourceCode;
        try {
            sourceCode = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }

        String fileName = Path.of(filePath).getFileName().toString();
        String language = detectLanguage(fileName, sourceCode);
        String dependencies = readDependencyFile(filePath, language);
        String importedSources = readImportedSources(filePath, sourceCode, language);
        String context = buildContext(fileName, language, sourceCode, dependencies, importedSources);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("""
                You are an expert test engineer. You write thorough, production-grade tests.
                Given source code and its dependencies, generate a complete test file.
                Rules:
                - Use ONLY the libraries and classes visible in the provided dependency file and imported sources.
                - Do NOT invent constructors, methods, or classes that are not shown in the source.
                - Java: use JUnit 5 and Mockito only if they appear in the dependency file.
                - JavaScript/TypeScript: use Jest.
                - Python: use pytest.
                - Cover happy paths, edge cases, and error conditions.
                - For loops that depend on mock return values, always stub multiple return values to avoid infinite loops.
                - Mock all external dependencies using only their real method signatures as shown in the source.
                - Output ONLY the raw test file code. No explanations, no markdown, no backticks.
                """));
        messages.add(Message.user("Generate tests for this " + language + " file.\n\n" + context));

        String testCode = null;
        int iterations = 0;
        boolean clean = false;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            iterations = i + 1;
            OllamaResponse response = ollamaClient.chat(messages, List.of());

            testCode = response.message().content();
            if (testCode == null || testCode.isBlank()) return "Model returned empty test output.";
            testCode = testCode.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
            messages.add(Message.assistant(testCode, null));

            String critiquePrompt = buildCritiquePrompt(testCode, sourceCode, importedSources);
            messages.add(Message.user(critiquePrompt));

            OllamaResponse critiqueResponse = ollamaClient.chat(messages, List.of());

            String critique = critiqueResponse.message().content();
            if (critique == null || critique.isBlank()) { clean = true; break; }

            clean = critique.toLowerCase().contains("no issues") ||
                    critique.toLowerCase().contains("looks good") ||
                    critique.toLowerCase().contains("no hallucinations") ||
                    critique.toLowerCase().contains("no problems");

            if (clean) break;

            messages.add(Message.assistant(critique, null));
            messages.add(Message.user(
                    "Fix all the issues you identified. Output ONLY the corrected raw test file code. No explanations, no markdown, no backticks."
            ));
        }

        String testFilePath = resolveTestFilePath(filePath, fileName, language);
        try {
            Path testPath = Path.of(testFilePath);
            Files.createDirectories(testPath.getParent());
            Files.writeString(testPath, testCode);
            return "Tests written to: " + testFilePath + " | iterations:" + iterations + " | clean:" + clean;
        } catch (IOException e) {
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

    private String buildCritiquePrompt(String testCode, String sourceCode, String importedSources) {
        return """
                Review the generated test code below for the following issues:
                1. Hallucinated APIs, constructors, or methods not present in the source or imported files.
                2. Mocks that would cause infinite loops because they always return the same value in a loop.
                3. Wrong import statements for libraries not in the dependency file.
                4. Any other correctness issues.

                If there are NO issues, respond with exactly: "No issues found."
                If there ARE issues, list them clearly and concisely.

                SOURCE CODE:
                """ + sourceCode + "\n\nIMPORTED SOURCES:\n" + importedSources + "\n\nGENERATED TEST CODE:\n" + testCode;
    }

    private String readDependencyFile(String filePath, String language) {
        Path dir = Path.of(filePath).getParent();
        try {
            Path root = findProjectRoot(dir, language);
            if (root == null) return "";
            String depFile = switch (language) {
                case "Java" -> "pom.xml";
                case "JavaScript", "TypeScript", "React/TypeScript" -> "package.json";
                case "Python" -> "requirements.txt";
                case "Go" -> "go.mod";
                case "Rust" -> "Cargo.toml";
                default -> "";
            };
            if (depFile.isBlank()) return "";
            Path depPath = root.resolve(depFile);
            return Files.exists(depPath) ? Files.readString(depPath) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private Path findProjectRoot(Path dir, String language) {
        String marker = switch (language) {
            case "Java" -> "pom.xml";
            case "JavaScript", "TypeScript", "React/TypeScript" -> "package.json";
            case "Python" -> "requirements.txt";
            case "Go" -> "go.mod";
            case "Rust" -> "Cargo.toml";
            default -> "";
        };
        if (marker.isBlank()) return null;
        Path current = dir;
        for (int i = 0; i < 8; i++) {
            if (Files.exists(current.resolve(marker))) return current;
            Path parent = current.getParent();
            if (parent == null) break;
            current = parent;
        }
        return null;
    }

    private String readImportedSources(String filePath, String sourceCode, String language) {
        StringBuilder sb = new StringBuilder();
        Path sourceDir = Path.of(filePath).getParent();

        if (language.equals("Java")) {
            sourceCode.lines()
                    .filter(l -> l.startsWith("import " + detectBasePackage(sourceCode)))
                    .map(l -> l.replace("import ", "").replace(";", "").trim())
                    .forEach(fqn -> {
                        String relativePath = fqn.replace(".", File.separator) + ".java";
                        try {
                            Path root = findProjectRoot(sourceDir, "Java");
                            if (root == null) return;
                            Path candidate = root.resolve("src/main/java/" + relativePath);
                            if (Files.exists(candidate)) {
                                sb.append("// ").append(fqn).append("\n");
                                sb.append(Files.readString(candidate)).append("\n\n");
                            }
                        } catch (IOException ignored) {}
                    });
        } else if (language.equals("JavaScript") || language.equals("TypeScript") || language.equals("React/TypeScript")) {
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

    private String detectLanguage(String fileName, String sourceCode) {
        String firstLine = sourceCode.lines().findFirst().orElse("");
        if (firstLine.contains("python")) return "Python";
        if (firstLine.contains("node") || firstLine.contains("deno")) return "JavaScript";
        if (firstLine.contains("ruby")) return "Ruby";
        if (firstLine.contains("bash") || firstLine.contains("sh")) return "Bash";

        if (sourceCode.contains("package ") && sourceCode.contains("func ")) return "Go";
        if (sourceCode.contains("fn main()") || sourceCode.contains("pub fn ")) return "Rust";
        if (sourceCode.contains("import java") || (sourceCode.contains("package ") && sourceCode.contains("class "))) return "Java";
        if (sourceCode.contains("using System") || sourceCode.contains("namespace ")) return "C#";
        if (sourceCode.contains("#include") && (sourceCode.contains("std::") || sourceCode.contains("cout"))) return "C++";
        if (sourceCode.contains("import React") || sourceCode.contains("from 'react'") || sourceCode.contains("from \"react\"")) return "React/TypeScript";
        if (sourceCode.contains("export default") || sourceCode.contains("export const")) return "TypeScript";
        if (sourceCode.contains("require(") || sourceCode.contains("module.exports")) return "JavaScript";
        if (sourceCode.contains("def ") && sourceCode.contains("import ")) return "Python";

        if (!fileName.contains(".")) return "unknown";
        return switch (fileName.substring(fileName.lastIndexOf('.') + 1)) {
            case "java" -> "Java";
            case "js" -> "JavaScript";
            case "ts" -> "TypeScript";
            case "jsx", "tsx" -> "React/TypeScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "rb" -> "Ruby";
            case "cs" -> "C#";
            case "cpp", "cc", "cxx" -> "C++";
            case "sh", "bash" -> "Bash";
            case "php" -> "PHP";
            case "swift" -> "Swift";
            case "kt" -> "Kotlin";
            default -> "unknown";
        };
    }

    private String resolveTestFilePath(String originalPath, String fileName, String language) {
        String dir = Path.of(originalPath).getParent().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";

        return switch (language) {
            case "Java" -> dir.replace("main", "test") + "\\" + baseName + "Test.java";
            case "Python" -> dir + "\\test_" + baseName + ".py";
            case "Go" -> dir + "\\" + baseName + "_test.go";
            case "Rust" -> dir + "\\" + baseName + "_test.rs";
            default -> dir + "\\" + baseName + ".test" + ext;
        };
    }
}