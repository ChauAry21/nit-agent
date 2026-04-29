package dev.aryan.nitagent.agent;

import java.util.*;
import com.fasterxml.jackson.databind.*;
import dev.aryan.nitagent.model.*;
import dev.aryan.nitagent.tools.*;
import dev.aryan.nitagent.client.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ReviewAgentService {

    private final OllamaClient ollamaClient;
    private final ToolExec toolExec;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewAgentService(OllamaClient ollamaClient, ToolExec toolExec) {
        this.ollamaClient = ollamaClient;
        this.toolExec = toolExec;
    }

    public void review(String repoPath, SseEmitter emitter) throws Exception {
        List<Message> messages = new ArrayList<>();

        messages.add(Message.system("""
        You are Nit, an elite senior software engineer and code reviewer with decades of experience across systems programming, web development, and security engineering.
        You are professional, encouraging, and brutally honest. You do not sugarcoat issues, but you always explain why something is a problem and how to fix it.
        You genuinely want the developer to improve, not just to point out flaws.

        Your review process follows this strict order:
        1. Start by listing all files in the repository to understand the structure.
        2. Read every relevant source file before forming any opinions.
        3. Run a git diff to understand recent changes.
        4. Use grep to search for known anti-patterns, hardcoded secrets, or unsafe practices.
        5. Produce a structured review.

        Your review must always be structured as follows:

        SECURITY & EDGE CASES
        - Identify any security vulnerabilities, injection risks, missing input validation, exposed secrets, unsafe deserialization, or unhandled edge cases.
        - This section is mandatory. If there are no issues, explicitly state that.

        CODE QUALITY & READABILITY
        - Identify naming issues, overly complex logic, dead code, missing abstractions, or violations of clean code principles.
        - Call out anything that would make a pull request difficult to review or maintain.

        PERFORMANCE
        - Identify inefficient algorithms, unnecessary database calls, blocking operations, or memory issues.

        TEST COVERAGE
        - Identify untested logic, missing edge case tests, or weak assertions.
        - If test generation was requested or warranted, generate JUnit 5 test cases for the most critical or untested methods.

        SUMMARY
        - Give an honest overall assessment. Rate the code on a scale of 1 to 10 with a one sentence justification.
        - List the top three things the developer should fix immediately.
        - End with one genuine piece of encouragement.

        Rules you must follow:
        - Never skip the security section.
        - Never approve code that has hardcoded credentials or SQL injection risks without flagging them as critical.
        - If a file is too large to review fully, say so and focus on the highest risk areas.
        - Be direct. Do not pad your response with filler phrases.
        - You are Nit. You have standards.
        - You MUST call read_file on at least 3 source files before writing the review.
        - You MUST call git_diff before writing the review.
        - You MUST call grep at least once before writing the review.
        - Do NOT produce the final review until you have used all required tools.
        - If you have not used all tools yet, call the next tool immediately.
        """));

        messages.add(Message.user("Please review the code in this repository: " + repoPath));

        List<Tool> tools = buildTools();

        emitter.send("Starting review of " + repoPath + "...\n");

        while (true) {
            emitter.send("[thinking]\n");
            OllamaResponse response = ollamaClient.chat(messages, tools);
            emitter.send("[thinking_done]\n");
            messages.add(Message.assistant(response.message().content(), response.message().tool_calls()));

            if (response.isToolCall()) {
                for (ToolCall toolCall : response.message().tool_calls()) {
                    String toolName = toolCall.function().name();
                    Map<String, Object> args = toolCall.function().arguments();
                    emitter.send("[tool] " + toolName + " " + args + "\n");
                    String result = toolExec.execute(toolName, args);
                    messages.add(Message.tool(result));
                }
            } else if (isJsonToolCall(response.message().content())) {
                String cleaned = response.message().content().replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "");
                int start = cleaned.indexOf('{');
                String prose = cleaned.substring(0, start).trim();
                if (!prose.isBlank()) {
                    emitter.send(prose + "\n");
                }
                String result = handleJsonToolCall(cleaned, emitter);
                messages.add(Message.tool(result));
            } else {
                String content = response.message().content();
                System.err.println("FINAL CONTENT: [" + content + "]");
                if (content == null || content.isBlank()) {
                    emitter.send(SseEmitter.event().data("Nit finished but returned an empty review. Try again.\n"));
                } else {
                    emitter.send(SseEmitter.event().data(content));
                }
                emitter.complete();
                return;
            }
        }
    }

    private boolean isJsonToolCall(String content) {
        if (content == null) return false;
        String trimmed = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        return trimmed.contains("\"name\"") && trimmed.contains("\"arguments\"");
    }

    private String handleJsonToolCall(String content, SseEmitter emitter) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start == -1 || end == -1) throw new Exception("No JSON found in content");
            String trimmed = content.substring(start, end + 1);
            JsonNode node = objectMapper.readTree(trimmed);
            String toolName = node.get("name").asText();
            Map<String, Object> args = objectMapper.convertValue(node.get("arguments"), new TypeReference<>() {});
            args.replaceAll((k, v) -> v instanceof String ? ((String) v).replace("\\\\", "\\") : v);
            emitter.send("[tool] " + toolName + " " + args + "\n");
            System.err.println("EXECUTING TOOL: " + toolName + " with args: " + args);
            return toolExec.execute(toolName, args);
        } catch (Exception e) {
            System.err.println("FAILED TO PARSE: " + content);
            System.err.println("EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
            return "Error parsing tool call: " + e.getMessage();
        }
    }

    private List<Tool> buildTools() {
        return List.of(
                Tool.of(new ToolDefinition("list_files", "Lists all files in a directory",
                        new ToolSchema("object", Map.of("path", new ToolProperty("string", "Directory path")), List.of("path")))),

                Tool.of(new ToolDefinition("read_file", "Reads the contents of a file",
                        new ToolSchema("object", Map.of("path", new ToolProperty("string", "File path")), List.of("path")))),

                Tool.of(new ToolDefinition("git_diff", "Returns the git diff for a repo",
                        new ToolSchema("object", Map.of("repo_path", new ToolProperty("string", "Repository path")), List.of("repo_path")))),

                Tool.of(new ToolDefinition("grep", "Searches for a pattern in files",
                        new ToolSchema("object", Map.of(
                                "pattern", new ToolProperty("string", "Pattern to search for"),
                                "path", new ToolProperty("string", "Directory to search in")), List.of("pattern", "path")))),

                Tool.of(new ToolDefinition("generate_tests", "Reads a file to generate test cases",
                        new ToolSchema("object", Map.of("path", new ToolProperty("string", "File path to generate tests for")), List.of("path"))))
        );
    }
}