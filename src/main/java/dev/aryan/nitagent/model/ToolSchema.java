package dev.aryan.nitagent.model;

import java.util.*;

public record ToolSchema(String type, Map<String, ToolProperty> properties, List<String> required) {
}
