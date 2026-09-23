package com.bazlur.orders.ai;

import module java.base;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaModel;
import dev.langchain4j.model.ollama.OllamaModels;

/** Checks the local Ollama model and configures chat. All Ollama traffic goes through LangChain4j. */
public final class OllamaRuntime {
    // Ollama names hosted models with a "cloud" tag, e.g. gpt-oss:120b-cloud. LangChain4j does not expose
    // Ollama's remote-model fields, so the name is the guard here; Compose also runs Ollama with OLLAMA_NO_CLOUD=1.
    // A cloud model copied to a local-looking name would pass this check.
    private static final Pattern CLOUD_TAG = Pattern.compile(".*:(.+-)?cloud$");

    private final URI base;
    private final String model;
    private final Duration timeout;
    private final OllamaModels models;

    public record ModelInfo(String model, String digest, String parameterSize, String quantization) {}

    public OllamaRuntime(URI base, String model, Duration timeout) {
        if (!"http".equals(base.getScheme()) || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || base.getHost() == null || !Set.of("localhost", "127.0.0.1", "[::1]", "ollama").contains(base.getHost())
                || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
            throw new IllegalArgumentException("OLLAMA_BASE_URL must use loopback or the Compose service http://ollama:11434");
        }
        if (model.isBlank()) throw new IllegalArgumentException("Model name is required");
        if (CLOUD_TAG.matcher(model).matches()) {
            throw new IllegalArgumentException("This demo requires a locally installed model, not a cloud model: " + model);
        }
        this.base = base;
        this.model = model;
        this.timeout = timeout;
        models = OllamaModels.builder().baseUrl(base.toString()).timeout(timeout).maxRetries(0).build();
    }

    /** Confirms the model is installed and able to call tools; returns what the trace records about it. */
    public ModelInfo inspect() throws IOException {
        try {
            var digest = models.availableModels().content().stream()
                    .filter(installed -> model.equals(installed.getName()))
                    .map(OllamaModel::getDigest)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElseThrow(() -> new IOException("Model tag is not installed locally: " + model + " (see ollama list)"));
            var card = models.modelCard(model).content();
            if (card.getCapabilities() == null || !card.getCapabilities().contains("tools")) {
                throw new IOException("Model does not advertise tool calling: " + model);
            }
            var details = card.getDetails();
            return new ModelInfo(model, digest,
                    details == null ? "" : details.getParameterSize(),
                    details == null ? "" : details.getQuantizationLevel());
        } catch (RuntimeException e) {
            throw new IOException("Cannot check the model at local Ollama " + base + "; is ollama serve running?", e);
        }
    }

    // Ollama accepts think=false for models without thinking support, so no capability check is needed.
    public OllamaChatModel chatModel() {
        return OllamaChatModel.builder().baseUrl(base.toString()).modelName(model)
                .temperature(0.0).seed(42).numCtx(8192).numPredict(1024)
                .timeout(timeout).maxRetries(0).think(false).returnThinking(false)
                .build();
    }
}
