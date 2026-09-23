package com.bazlur.orders.config;

import module java.base;

import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The local Ollama server and the model the agent uses; set via OLLAMA_BASE_URL, OLLAMA_MODEL and OLLAMA_TIMEOUT_SECONDS. */
@ConfigurationProperties("orders.ollama")
public record OllamaProperties(URI baseUrl, String model, Duration timeout) {

    // Temperature 0 and a fixed seed for repeatable answers; think=false is accepted by models without thinking.
    public OllamaChatModel chatModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl.toString())
                .modelName(model)
                .temperature(0.0)
                .seed(42)
                .numCtx(8192)
                .numPredict(1024)
                .timeout(timeout)
                .maxRetries(0)
                .think(false)
                .returnThinking(false)
                .build();
    }
}
