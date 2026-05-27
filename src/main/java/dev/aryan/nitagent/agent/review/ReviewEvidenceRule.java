package dev.aryan.nitagent.agent.review;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum ReviewEvidenceRule {
    ONLY_INSPECTED_FILES("Only mention files that appeared in tool output or were read with read_file."),
    NO_FAKE_LINE_NUMBERS("Do not include line numbers unless the read_file output clearly included line numbers."),
    NO_UNVERIFIED_SECURITY_FINDINGS("Do not claim hardcoded secrets, SQL injection, command injection, or path traversal unless the inspected code directly supports it."),
    NO_UNVERIFIED_COMPLEXITY("Do not claim cyclomatic complexity numbers unless they were calculated by a tool."),
    QUOTE_EVIDENCE("For each serious issue, include a short evidence phrase from the inspected code or describe the exact behavior seen."),
    UNCERTAIN_LANGUAGE("If evidence is weak, say 'potential issue' instead of stating it as confirmed.");

    private final String instruction;

    ReviewEvidenceRule(String instruction) {
        this.instruction = instruction;
    }

    public String instruction() {
        return instruction;
    }

    public static String buildInstructionList() {
        return "Evidence rules:\n" + Arrays.stream(values())
                .map(rule -> (rule.ordinal() + 1) + ". " + rule.instruction)
                .collect(Collectors.joining("\n"));
    }
}