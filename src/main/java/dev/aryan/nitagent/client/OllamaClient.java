package dev.aryan.nitagent.client;

import dev.aryan.nitagent.model.Message;
import dev.aryan.nitagent.model.OllamaRequest;
import dev.aryan.nitagent.model.OllamaResponse;
import dev.aryan.nitagent.model.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class OllamaClient {
    private final String model;
    private final String fastModel;
    private final String apiKey;
    private final RestClient restClient;

    public OllamaClient(RestClient.Builder builder,
                        @Value("${ollama.base-url}") String baseUrl,
                        @Value("${ollama.model}") String model,
                        @Value("${ollama.fast-model:${ollama.model}}") String fastModel,
                        @Value("${ollama.api-key:}") String apiKey) {
        this.model = model;
        this.fastModel = fastModel;
        this.apiKey = apiKey;
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public OllamaResponse chat(List<Message> messages, List<Tool> tools) {
        return chat(messages, tools, false);
    }

    public OllamaResponse chat(List<Message> messages, List<Tool> tools, boolean fast) {
        OllamaRequest request = new OllamaRequest(fast ? fastModel : model, messages, tools, false);
        RestClient.RequestBodySpec spec = restClient.post().uri("/api/chat");
        if (!apiKey.isBlank()) spec.header("Authorization", "Bearer " + apiKey);
        return spec.body(request).retrieve().body(OllamaResponse.class);
    }
}