package dev.aryan.nitagent.model;

import java.util.*;

public record Message(String role, String content, List<ToolCall> tool_calls) {
    public static Message system(String content) {return new Message("system", content, null);}

    public static Message user(String content) {return new Message("user", content, null);}

    public static Message tool(String content) {return new Message("tool", content, null);}

    public static Message assistant(String content, List<ToolCall> tool_calls) {return new Message("assistant", content, tool_calls);}
}