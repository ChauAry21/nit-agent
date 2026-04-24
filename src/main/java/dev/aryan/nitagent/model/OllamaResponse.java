package dev.aryan.nitagent.model;

public record OllamaResponse(Message message, String doneReason) {
    public boolean isToolCall() {return message.toolCalls() != null && !message.toolCalls().isEmpty();}
}