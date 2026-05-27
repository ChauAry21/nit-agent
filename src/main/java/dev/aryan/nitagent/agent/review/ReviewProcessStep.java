package dev.aryan.nitagent.agent.review;

import java.util.Arrays;
import java.util.stream.Collectors;

public enum ReviewProcessStep {
    LIST_FILES("Call list_files on the repository root."),
    READ_IMPORTANT_FILES("Read the most important source files before reviewing."),
    READ_SMALL_REPO("If this is a small repo, read every source file."),
    READ_LARGE_REPO("If this is a larger repo, read at least 5 important source files, prioritizing entry points, controllers, services, config files, package files, and tests."),
    RUN_GIT_DIFF("Call git_diff on the repository root before writing the review."),
    GREP_RISKS("Use grep to check for security risks, TODOs, hardcoded secrets, unsafe IO, subprocess usage, network calls, and error handling."),
    FINALIZE_AFTER_INSPECTION("Only after inspecting files, produce the final structured review.");

    private final String instruction;

    ReviewProcessStep(String instruction) {
        this.instruction = instruction;
    }

    public String instruction() {
        return instruction;
    }

    public static String buildInstructionList() {
        return Arrays.stream(values())
                .map(step -> (step.ordinal() + 1) + ". " + step.instruction)
                .collect(Collectors.joining("\n"));
    }
}