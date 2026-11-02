package productdesigner;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fournit l'AnthropicClient, créé au premier usage pour que l'app démarre sans clé. */
@Component
public class Anthropic {

    static final String CLE_ABSENTE = "Clé Anthropic absente : ajouter productdesigner.anthropic.api-key"
            + " dans ~/.productdesigner/application.yml, puis redémarrer.";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private AnthropicClient client;

    Anthropic(@Value("${productdesigner.anthropic.api-key:}") String apiKey,
              @Value("${productdesigner.anthropic.model:claude-opus-5-5}") String model,
              @Value("${productdesigner.anthropic.base-url:https://api.anthropic.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
    }

    public String model() {
        return model;
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    public synchronized AnthropicClient client() {
        if (!configured()) {
            throw new ConfigManquanteException(CLE_ABSENTE);
        }
        if (client == null) {
            client = AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        }
        return client;
    }
}
