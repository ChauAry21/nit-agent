package dev.aryan.nitagent.agent.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aryan.nitagent.client.OllamaClient;
import dev.aryan.nitagent.exception.ToolParsingException;
import dev.aryan.nitagent.model.Message;
import dev.aryan.nitagent.model.OllamaResponse;
import dev.aryan.nitagent.model.Tool;
import dev.aryan.nitagent.model.ToolCall;
import dev.aryan.nitagent.model.ToolDefinition;
import dev.aryan.nitagent.model.ToolProperty;
import dev.aryan.nitagent.model.ToolSchema;
import dev.aryan.nitagent.tools.ToolExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ReviewAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgentService.class);
    private static final int MAX_TOOL_CALLS = 15;
    private static final int MAX_REPO_PATH_LENGTH = 1024;
    private static final Path MALFORMED_TOOL_LOG = Path.of("logs", "malformed-tool-calls.log");

    private final OllamaClient ollamaClient;
    private final ToolExec toolExec;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewAgentService(OllamaClient ollamaClient, ToolExec toolExec) {
        this.ollamaClient = ollamaClient;
        this.toolExec = toolExec;
    }

    public void review(String repoPath, SseEmitter emitter, boolean fast) throws Exception {
        repoPath = sanitizeBody(repoPath);

        if (repoPath.isBlank()) {
            emitter.send("Error: repository path is empty.\n");
            emitter.complete();
            return;
        }

        if (repoPath.length() > MAX_REPO_PATH_LENGTH) {
            emitter.send("Error: repository path exceeds maximum length.\n");
            emitter.complete();
            return;
        }

        toolExec.clearCache();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(ReviewPrompt.build()));
        messages.add(Message.user("Review the code in this repository: " + repoPath + ". Follow your review process exactly. Do not respond conversationally. Call your tools in order and produce the structured review. If you need git_diff, you may pass repo_path, base_ref, and target_ref."));

        List<Tool> tools = buildTools();
        emitter.send("Starting review of " + repoPath + "...\n");

        int toolCallCount = 0;
        int parseRetries = 0;

        while (true) {
            emitter.send("[thinking]\n");
            OllamaResponse response = ollamaClient.chat(messages, tools, fast);
            emitter.send("[thinking_done]\n");

            messages.add(Message.assistant(response.message().content(), response.message().tool_calls()));

            if (toolCallCount >= MAX_TOOL_CALLS) {
                messages.add(Message.user("Stop calling tools. You have enough information.\nProduce the full structured review now using exactly these headers:\n" + ReviewSection.buildHeaderList() + "\nSUMMARY must include X/10 rating, top 3 fixes, one encouragement.\nDo not write code. Write the review now."));
            }

            if (response.isToolCall()) {
                for (ToolCall toolCall : response.message().tool_calls()) {
                    toolCallCount++;
                    String toolName = toolCall.function().name();
                    Map<String, Object> args = toolCall.function().arguments();
                    emitter.send("[tool] " + toolName + " " + args + "\n");
                    String result = toolExec.execute(toolName, args);
                    messages.add(Message.tool(result));
                }
                continue;
            }

            String content = response.message().content();

            if (isJsonToolCall(content)) {
                try {
                    String result = handleJsonToolCall(content, emitter);
                    toolCallCount++;
                    messages.add(Message.tool(result));
                    continue;
                } catch (ToolParsingException e) {
                    log.warn("JSON tool call parse failed: {}", e.getMessage());
                    logMalformedToolCall(content, e);

                    if (parseRetries == 0) {
                        parseRetries++;
                        emitter.send("[retry] Tool call parsing failed. Asking model for clean JSON tool call.\n");
                        messages.add(Message.user("Your last tool call could not be parsed. Output only one valid JSON object with this shape and no markdown: {\"name\":\"tool_name\",\"arguments\":{}}. Do not include prose."));
                        continue;
                    }

                    emitter.send("Error: failed to parse model tool call after retry. Review stopped cleanly.\n");
                    emitter.complete();
                    return;
                }
            }

            if (content == null || content.isBlank()) {
                emitter.send(SseEmitter.event().data("Nit finished but returned an empty review. Try again.\n"));
                emitter.complete();
                return;
            }

            if (!content.contains(ReviewSection.SECURITY.header)) {
                log.warn("Response missing required sections, retrying");
                messages.add(Message.user("Your response did not follow the required format. You MUST now produce the structured review using exactly these headers in order:\n\n" + ReviewSection.buildHeaderList() + "\n\nSUMMARY must include X/10 rating, top 3 fixes, one encouragement.\nDo not write code. Do not respond conversationally. Write the review now."));
                continue;
            }

            emitter.send(SseEmitter.event().data(content));
            emitter.complete();
            return;
        }
    }

    private String sanitizeBody(String body) {
        if (body == null) return "";
        return body.replace("\"", "").trim();
    }

    private boolean isJsonToolCall(String content) {
        if (content == null) return false;
        String trimmed = cleanMarkdown(content).trim();
        return trimmed.startsWith("{") && trimmed.contains("\"name\"") && trimmed.contains("\"arguments\"");
    }

    private String handleJsonToolCall(String content, SseEmitter emitter) {
        try {
            String cleaned = cleanMarkdown(content);
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start == -1 || end == -1 || end < start) {
                throw new IllegalArgumentException("No JSON object found in content");
            }

            String beforeJson = cleaned.substring(0, start).trim();
            if (!beforeJson.isBlank()) {
                emitter.send(beforeJson + "\n");
            }

            String json = cleaned.substring(start, end + 1);
            JsonNode node = objectMapper.readTree(json);

            JsonNode nameNode = node.get("name");
            JsonNode argsNode = node.get("arguments");

            if (nameNode == null || nameNode.asText().isBlank()) {
                throw new IllegalArgumentException("Missing tool name");
            }

            if (argsNode == null || !argsNode.isObject()) {
                throw new IllegalArgumentException("Missing tool arguments object");
            }

            String toolName = nameNode.asText();
            Map<String, Object> args = objectMapper.convertValue(argsNode, new TypeReference<>() {});
            args.replaceAll((k, v) -> v instanceof String s ? s.replace("\\\\", "\\") : v);

            emitter.send("[tool] " + toolName + " " + args + "\n");
            log.debug("Executing JSON tool call: {} with args: {}", toolName, args);
            return toolExec.execute(toolName, args);
        } catch (Exception e) {
            throw new ToolParsingException("Failed to parse tool call: " + e.getMessage(), e);
        }
    }

    private String cleanMarkdown(String content) {
        return content.replaceAll("(?s)```\\w*\\s*", "").replaceAll("(?s)```\\s*", "");
    }

    private void logMalformedToolCall(String content, Exception e) {
        try {
            Files.createDirectories(MALFORMED_TOOL_LOG.getParent());
            String entry = "\n--- " + Instant.now() + " ---\n" + e.getMessage() + "\n" + content + "\n";
            Files.writeString(MALFORMED_TOOL_LOG, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception logError) {
            log.warn("Could not write malformed tool call log: {}", logError.getMessage());
        }
    }

    private List<Tool> buildTools() {
        return List.of(
                Tool.of(new ToolDefinition("list_files", "Lists all files in a directory and returns JSON with files and count",
                        new ToolSchema("object", Map.of("path", new ToolProperty("string", "Directory path")), List.of("path")))),
                Tool.of(new ToolDefinition("read_file", "Reads the contents of a file",
                        new ToolSchema("object", Map.of("path", new ToolProperty("string", "File path")), List.of("path")))),
                Tool.of(new ToolDefinition("git_diff", "Returns the git diff for a repo. Supports optional base_ref and target_ref. If target_ref is omitted, it diffs against the given base_ref.",
                        new ToolSchema("object", Map.of(
                                "repo_path", new ToolProperty("string", "Repository path"),
                                "base_ref", new ToolProperty("string", "Base git ref, branch, or commit"),
                                "target_ref", new ToolProperty("string", "Target git ref, branch, or commit")
                        ), List.of("repo_path")))),
                Tool.of(new ToolDefinition("grep", "Searches for a pattern in files",
                        new ToolSchema("object", Map.of(
                                "pattern", new ToolProperty("string", "Pattern to search for"),
                                "path", new ToolProperty("string", "Directory to search in")), List.of("pattern", "path"))))
        );
    }
}