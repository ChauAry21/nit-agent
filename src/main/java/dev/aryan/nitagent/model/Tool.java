package dev.aryan.nitagent.model;

public record Tool(String type, ToolDefinition func) {
    public static Tool of(ToolDefinition def) {return new Tool("function", def);}
}