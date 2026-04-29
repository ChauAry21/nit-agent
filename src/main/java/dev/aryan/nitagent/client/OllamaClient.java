package dev.aryan.nitagent.client;

import dev.aryan.nitagent.model.Message;
import dev.aryan.nitagent.model.Tool;
import dev.aryan.nitagent.model.OllamaRequest;
import dev.aryan.nitagent.model.OllamaResponse;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;

@Component
public class OllamaClient {
    private final String model;
    private final String fastModel;
    private final RestClient restClient;

    public OllamaClient(RestClient.Builder builder,
                        @Value("${ollama.base-url}") String baseUrl,
                        @Value("${ollama.model}") String model,
                        @Value("${ollama.fast-model}") String fastModel) {
        this.model = model;
        this.fastModel = fastModel;
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public OllamaResponse chat(List<Message> messages, List<Tool> tools) {
        return chat(messages, tools, false);
    }

    public OllamaResponse chat(List<Message> messages, List<Tool> tools, boolean fast) {
        OllamaRequest request = new OllamaRequest(fast ? fastModel : model, messages, tools, false);
        return restClient.post()
                .uri("/api/chat")
                .body(request)
                .retrieve()
                .body(OllamaResponse.class);
    }
}