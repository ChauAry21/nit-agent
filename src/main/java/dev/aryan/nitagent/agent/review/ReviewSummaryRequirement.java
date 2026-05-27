package dev.aryan.nitagent.agent.review;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum ReviewSummaryRequirement {
    RATING("A rating in the format X/10 followed by one sentence justification."),
    TOP_FIXES("A numbered list of the top 3 things to fix immediately."),
    ENCOURAGEMENT("One sentence of genuine encouragement.");

    private final String instruction;

    ReviewSummaryRequirement(String instruction) {
        this.instruction = instruction;
    }

    public String instruction() {
        return instruction;
    }

    public static String buildInstructionList() {
        return "The SUMMARY section must contain:\n" + Arrays.stream(values())
                .map(requirement -> (requirement.ordinal() + 1) + ". " + requirement.instruction)
                .collect(Collectors.joining("\n"));
    }
}