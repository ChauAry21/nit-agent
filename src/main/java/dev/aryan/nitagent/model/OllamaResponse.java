package dev.aryan.nitagent.model;

public record OllamaResponse(Message message, String done_reason) {
    public boolean isToolCall() {return message.tool_calls() != null && !message.tool_calls().isEmpty();}
}