package dev.aryan.nitagent.model;

import java.util.*;

public record OllamaRequest(String model, List<Message> messages, List<Tool> tools, boolean stream) {
}
