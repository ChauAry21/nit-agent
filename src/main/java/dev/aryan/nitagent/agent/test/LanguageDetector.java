package dev.aryan.nitagent.agent.test;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class LanguageDetector {

    private LanguageDetector() {}

    public static SupportedLanguage detect(String fileName, String sourceCode) {
        String firstLine = sourceCode.lines().findFirst().orElse("").toLowerCase(Locale.ROOT);
        String lowerFile = fileName.toLowerCase(Locale.ROOT);
        String lowerSource = sourceCode.toLowerCase(Locale.ROOT);

        List<Candidate> candidates = List.of(
                shebang(firstLine),
                extension(lowerFile),
                framework(lowerFile, lowerSource),
                keywords(lowerSource)
        );

        return candidates.stream()
                .filter(c -> c.language != SupportedLanguage.UNKNOWN)
                .max(Comparator.comparingDouble(c -> c.confidence))
                .map(c -> c.language)
                .orElse(SupportedLanguage.UNKNOWN);
    }

    private static Candidate shebang(String firstLine) {
        if (!firstLine.startsWith("#!")) return unknown();
        if (firstLine.contains("python")) return new Candidate(SupportedLanguage.PYTHON, 1.0);
        if (firstLine.contains("node") || firstLine.contains("deno")) return new Candidate(SupportedLanguage.JAVASCRIPT, 1.0);
        if (firstLine.contains("ruby")) return new Candidate(SupportedLanguage.RUBY, 1.0);
        if (firstLine.contains("bash") || firstLine.contains(" sh")) return new Candidate(SupportedLanguage.BASH, 1.0);
        return unknown();
    }

    private static Candidate extension(String fileName) {
        Optional<String> ext = extensionOf(fileName);
        if (ext.isEmpty()) return unknown();

        return switch (ext.get()) {
            case "java" -> new Candidate(SupportedLanguage.JAVA, 0.85);
            case "js", "mjs", "cjs" -> new Candidate(SupportedLanguage.JAVASCRIPT, 0.85);
            case "ts" -> new Candidate(SupportedLanguage.TYPESCRIPT, 0.85);
            case "jsx", "tsx" -> new Candidate(SupportedLanguage.REACT, 0.9);
            case "py" -> new Candidate(SupportedLanguage.PYTHON, 0.85);
            case "go" -> new Candidate(SupportedLanguage.GO, 0.85);
            case "rs" -> new Candidate(SupportedLanguage.RUST, 0.85);
            case "rb" -> new Candidate(SupportedLanguage.RUBY, 0.85);
            case "cs" -> new Candidate(SupportedLanguage.CSHARP, 0.85);
            case "cpp", "cc", "cxx" -> new Candidate(SupportedLanguage.CPP, 0.85);
            case "sh", "bash" -> new Candidate(SupportedLanguage.BASH, 0.85);
            case "php" -> new Candidate(SupportedLanguage.PHP, 0.85);
            case "swift" -> new Candidate(SupportedLanguage.SWIFT, 0.85);
            case "kt", "kts" -> new Candidate(SupportedLanguage.KOTLIN, 0.85);
            default -> unknown();
        };
    }

    private static Candidate framework(String fileName, String sourceCode) {
        if ((fileName.endsWith(".jsx") || fileName.endsWith(".tsx")) && containsReact(sourceCode)) {
            return new Candidate(SupportedLanguage.REACT, 0.98);
        }

        if (containsReact(sourceCode)) {
            return new Candidate(SupportedLanguage.REACT, 0.8);
        }

        return unknown();
    }

    private static Candidate keywords(String sourceCode) {
        if (sourceCode.contains("package main") && sourceCode.contains("func ")) return new Candidate(SupportedLanguage.GO, 0.75);
        if (sourceCode.contains("fn main()") || sourceCode.contains("pub fn ")) return new Candidate(SupportedLanguage.RUST, 0.75);
        if (sourceCode.contains("import java") || (sourceCode.contains("package ") && sourceCode.contains("class "))) return new Candidate(SupportedLanguage.JAVA, 0.75);
        if (sourceCode.contains("using system") || sourceCode.contains("namespace ")) return new Candidate(SupportedLanguage.CSHARP, 0.75);
        if (sourceCode.contains("#include") && (sourceCode.contains("std::") || sourceCode.contains("cout"))) return new Candidate(SupportedLanguage.CPP, 0.75);
        if (sourceCode.contains("export default") || sourceCode.contains("export const") || sourceCode.contains("interface ")) return new Candidate(SupportedLanguage.TYPESCRIPT, 0.6);
        if (sourceCode.contains("require(") || sourceCode.contains("module.exports")) return new Candidate(SupportedLanguage.JAVASCRIPT, 0.7);
        if (sourceCode.contains("def ") && sourceCode.contains("import ")) return new Candidate(SupportedLanguage.PYTHON, 0.7);
        return unknown();
    }

    private static boolean containsReact(String sourceCode) {
        return sourceCode.contains("import react")
                || sourceCode.contains("from 'react'")
                || sourceCode.contains("from \"react\"")
                || sourceCode.contains("jsx.element");
    }

    private static Optional<String> extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot == -1 || dot == fileName.length() - 1) return Optional.empty();
        return Optional.of(fileName.substring(dot + 1));
    }

    private static Candidate unknown() {
        return new Candidate(SupportedLanguage.UNKNOWN, 0.0);
    }

    private record Candidate(SupportedLanguage language, double confidence) {}
}