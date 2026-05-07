package dev.aryan.nitagent.agent.test;

public final class TestPrompt {

    private TestPrompt() {}

    public static String buildGeneration(String language, String context) {
        return "Generate tests for this " + language + " file.\n\n" + context;
    }

    public static String buildSystem() {
        return """
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
                """;
    }

    public static String buildCritique(String testCode, String sourceCode, String importedSources) {
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

    public static String buildFix() {
        return "Fix all the issues you identified. Output ONLY the corrected raw test file code. No explanations, no markdown, no backticks.";
    }
}