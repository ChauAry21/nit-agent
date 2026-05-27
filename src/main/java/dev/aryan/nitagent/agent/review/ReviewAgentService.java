package dev.aryan.nitagent.agent.review;

import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import dev.aryan.nitagent.model.*;
import dev.aryan.nitagent.tools.*;
import dev.aryan.nitagent.client.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.*;

@Service
public class ReviewAgentService {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgentService.class);
    private static final int MAX_TOOL_CALLS = 35;
    private static final int MAX_AGENT_LOOPS = 50;

    private final OllamaClient ollamaClient;
    private final ToolExec toolExec;
    private final WorkspaceGuard workspaceGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewAgentService(OllamaClient ollamaClient, ToolExec toolExec, WorkspaceGuard workspaceGuard) {
        this.ollamaClient = ollamaClient;
        this.toolExec = toolExec;
        this.workspaceGuard = workspaceGuard;
    }

    public void review(String repoPath, SseEmitter emitter, boolean fast) throws Exception {
        repoPath = repoPath.replace("\"", "").trim();

        if (repoPath.length() > 1024) {
            emitter.send("Error: repository path exceeds maximum length.\n");
            return;
        }

        Path workspaceRoot = workspaceGuard.requireRoot(repoPath);
        toolExec.clearCache();

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(ReviewPrompt.build()));
        messages.add(Message.user(ReviewPrompt.initialRequest(workspaceRoot)));

        List<Tool> tools = buildTools();
        emitter.send("Starting review of " + workspaceRoot + "...\n");

        int toolCallCount = 0;
        int loopCount = 0;
        boolean forceFinal = false;

        while (loopCount++ < MAX_AGENT_LOOPS) {
            emitter.send("[thinking]\n");
            OllamaResponse response = ollamaClient.chat(messages, tools, fast);
            emitter.send("[thinking_done]\n");

            messages.add(Message.assistant(response.message().content(), response.message().tool_calls()));

            if (toolCallCount >= MAX_TOOL_CALLS && !forceFinal) {
                forceFinal = true;
                messages.add(Message.user(ReviewPrompt.forcedFinalRequest()));
            }

            if (response.isToolCall()) {
                if (forceFinal) {
                    messages.add(Message.tool(ReviewPrompt.toolLimitMessage()));
                    continue;
                }

                for (ToolCall toolCall : response.message().tool_calls()) {
                    toolCallCount++;

                    String toolName = toolCall.function().name();
                    Map<String, Object> args = toolCall.function().arguments();

                    emitter.send("[tool] " + toolName + " " + args + "\n");

                    String result = toolExec.execute(toolName, args, workspaceRoot);
                    messages.add(Message.tool(result));

                    if (toolCallCount >= MAX_TOOL_CALLS) {
                        forceFinal = true;
                        messages.add(Message.user(ReviewPrompt.forcedFinalRequest()));
                        break;
                    }
                }
            } else if (isJsonToolCall(response.message().content())) {
                String cleaned = response.message().content()
                        .replaceAll("(?s)```\\w*\\s*", "")
                        .replaceAll("(?s)```\\s*", "");

                int start = cleaned.indexOf('{');
                String prose = start > 0 ? cleaned.substring(0, start).trim() : "";

                if (!prose.isBlank()) {
                    emitter.send(prose + "\n");
                }

                if (forceFinal) {
                    messages.add(Message.tool(ReviewPrompt.toolLimitMessage()));
                    continue;
                }

                String result = handleJsonToolCall(cleaned, emitter, workspaceRoot);
                toolCallCount++;
                messages.add(Message.tool(result));

                if (toolCallCount >= MAX_TOOL_CALLS) {
                    forceFinal = true;
                    messages.add(Message.user(ReviewPrompt.forcedFinalRequest()));
                }
            } else {
                String content = response.message().content();

                if (content == null || content.isBlank()) {
                    emitter.send(SseEmitter.event().data(ReviewPrompt.emptyReviewMessage()));
                    emitter.complete();
                    return;
                }

                if (!content.contains(ReviewSection.SECURITY.header)) {
                    log.warn("Response missing required sections, retrying");
                    messages.add(Message.user(ReviewPrompt.formatRetryRequest()));
                    continue;
                }

                emitter.send(SseEmitter.event().data(content));
                emitter.complete();
                return;
            }
        }

        emitter.send(SseEmitter.event().data(ReviewPrompt.loopLimitMessage()));
        emitter.complete();
    }

    private boolean isJsonToolCall(String content) {
        if (content == null) return false;

        String trimmed = content
                .replaceAll("(?s)```\\w*\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        return trimmed.startsWith("{") && trimmed.contains("\"name\"") && trimmed.contains("\"arguments\"");
    }

    private String handleJsonToolCall(String content, SseEmitter emitter, Path workspaceRoot) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');

            if (start == -1 || end == -1) {
                throw new Exception("No JSON found in content");
            }

            String trimmed = content.substring(start, end + 1);
            JsonNode node = objectMapper.readTree(trimmed);

            String toolName = node.get("name").asText();
            Map<String, Object> args = objectMapper.convertValue(node.get("arguments"), new TypeReference<>() {});

            args.replaceAll((k, v) -> v instanceof String ? ((String) v).replace("\\\\", "\\") : v);

            emitter.send("[tool] " + toolName + " " + args + "\n");
            log.debug("Executing JSON tool call: {} with args: {}", toolName, args);

            return toolExec.execute(toolName, args, workspaceRoot);
        } catch (Exception e) {
            log.error("Failed to parse JSON tool call: {}", e.getMessage());
            return "Error parsing tool call: " + e.getMessage();
        }
    }

    private List<Tool> buildTools() {
        return List.of(
                Tool.of(new ToolDefinition(
                        "list_files",
                        "Lists all files in a directory",
                        new ToolSchema(
                                "object",
                                Map.of("path", new ToolProperty("string", "Directory path")),
                                List.of("path")
                        )
                )),
                Tool.of(new ToolDefinition(
                        "read_file",
                        "Reads the contents of a file",
                        new ToolSchema(
                                "object",
                                Map.of("path", new ToolProperty("string", "File path")),
                                List.of("path")
                        )
                )),
                Tool.of(new ToolDefinition(
                        "git_diff",
                        "Returns the git diff for a repo",
                        new ToolSchema(
                                "object",
                                Map.of("repo_path", new ToolProperty("string", "Repository path")),
                                List.of("repo_path")
                        )
                )),
                Tool.of(new ToolDefinition(
                        "grep",
                        "Searches for a pattern in files",
                        new ToolSchema(
                                "object",
                                Map.of(
                                        "pattern", new ToolProperty("string", "Pattern to search for"),
                                        "path", new ToolProperty("string", "Directory to search in")
                                ),
                                List.of("pattern", "path")
                        )
                ))
        );
    }
}