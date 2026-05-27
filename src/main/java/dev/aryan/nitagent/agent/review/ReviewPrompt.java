package dev.aryan.nitagent.agent.review;

import java.nio.file.Path;

public final class ReviewPrompt {

    private ReviewPrompt() {}

    public static String build() {
        return """
You are Nit, an automated code review agent.

Role:
You are an elite senior software engineer and code reviewer with deep experience across backend systems, frontend applications, security engineering, testing, and maintainability.

Review standards:
1. Be direct and evidence based.
2. Do not produce generic advice.
3. Do not invent issues.
4. Base findings only on inspected files.
5. Never skip the security section.
6. Never approve hardcoded credentials, unsafe command execution, unsafe file IO, SQL injection risks, or path traversal risks without flagging them.
7. Do not write code blocks or full rewritten files in the review.
8. If you recommend a fix, explain the fix in plain English.
9. If there is not enough evidence for an issue, do not include it.
10. Do not claim exact line numbers unless the inspected file content included line numbers.
11. Do not claim exact complexity scores unless a tool calculated them.
""";
    }

    public static String initialRequest(Path workspaceRoot) {
        return """
Review the code in this repository: %s.

You must inspect the repository before writing the review.

Required process:
%s

%s

Rules for the final review:
1. Use exactly the required section headers.
2. Do not rename, reorder, skip, or add sections.
3. Every issue must reference a specific file, function, class, dependency, or behavior.
4. Do not include generic advice.
5. Do not write code blocks or full revised implementations.
6. Do not mention files that were not shown by list_files or read_file.
7. Do not invent line numbers.
8. Do not invent security findings.

Required section headers:
%s

%s

Write the review only after completing the required process.
""".formatted(
                workspaceRoot,
                ReviewProcessStep.buildInstructionList(),
                ReviewEvidenceRule.buildInstructionList(),
                ReviewSection.buildHeaderList(),
                ReviewSummaryRequirement.buildInstructionList()
        );
    }

    public static String forcedFinalRequest() {
        return """
Stop calling tools. You have reached the tool limit.

Produce the full structured review now.

Required section headers:
%s

%s

Rules:
1. Use only evidence from files already inspected.
2. Every issue must reference a specific file, function, class, dependency, or behavior.
3. Do not invent issues.
4. Do not include generic advice.
5. Do not write code blocks or full revised implementations.
6. Do not mention files that were not shown by list_files or read_file.
7. Do not invent line numbers.
8. Do not invent security findings.

%s

Write the review now.
""".formatted(
                ReviewSection.buildHeaderList(),
                ReviewEvidenceRule.buildInstructionList(),
                ReviewSummaryRequirement.buildInstructionList()
        );
    }

    public static String formatRetryRequest() {
        return """
Your response did not follow the required format.

Produce the structured review again using exactly these section headers in order:
%s

%s

Rules:
1. Do not call tools.
2. Do not respond conversationally.
3. Do not include generic advice.
4. Every issue must reference a specific file, function, class, dependency, or behavior.
5. Do not write code blocks or full revised implementations.
6. Do not mention files that were not shown by list_files or read_file.
7. Do not invent line numbers.
8. Do not invent security findings.

%s

Write the review now.
""".formatted(
                ReviewSection.buildHeaderList(),
                ReviewEvidenceRule.buildInstructionList(),
                ReviewSummaryRequirement.buildInstructionList()
        );
    }

    public static String toolLimitMessage() {
        return "Tool limit reached. No more tool calls will be executed. Produce the final structured review now.";
    }

    public static String loopLimitMessage() {
        return "Nit stopped after reaching the maximum review loop count. Try fast mode or a smaller repo.\n";
    }

    public static String emptyReviewMessage() {
        return "Nit finished but returned an empty review. Try again.\n";
    }
}