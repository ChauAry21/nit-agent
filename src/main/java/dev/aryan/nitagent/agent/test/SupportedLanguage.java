package dev.aryan.nitagent.agent.test;

public enum SupportedLanguage {
    JAVA("Java", "pom.xml"),
    JAVASCRIPT("JavaScript", "package.json"),
    TYPESCRIPT("TypeScript", "package.json"),
    REACT("React/TypeScript", "package.json"),
    PYTHON("Python", "requirements.txt"),
    GO("Go", "go.mod"),
    RUST("Rust", "Cargo.toml"),
    RUBY("Ruby", null),
    CSHARP("C#", null),
    CPP("C++", null),
    BASH("Bash", null),
    PHP("PHP", null),
    SWIFT("Swift", null),
    KOTLIN("Kotlin", null),
    UNKNOWN("unknown", null);

    public final String name;
    public final String depFile;

    SupportedLanguage(String name, String depFile) {
        this.name = name;
        this.depFile = depFile;
    }

    public static SupportedLanguage fromName(String name) {
        for (SupportedLanguage l : values()) {
            if (l.name.equals(name)) return l;
        }
        return UNKNOWN;
    }
}