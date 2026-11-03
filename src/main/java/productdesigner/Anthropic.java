package productdesigner;

import com.anthropic.bedrock.backends.BedrockMantleBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

/**
 * Fournit l'AnthropicClient, créé au premier usage pour que l'app démarre sans configuration.
 * Deux fournisseurs : l'API Claude d'Anthropic (clé API), ou Claude in Amazon Bedrock (identifiants AWS,
 * profil SSO compris). Bedrock n'a pas d'outils serveur : pas de recherche web, donc pas de Méthode research.
 */
@Component
public class Anthropic {

    static final String CLE_ABSENTE = "Clé Anthropic absente : ajouter productdesigner.anthropic.api-key"
            + " dans ~/.productdesigner/application.yml, puis redémarrer (ou productdesigner.anthropic.fournisseur: bedrock).";

    static final String SANS_RECHERCHE_WEB = "La research a besoin de la recherche web, indisponible sur Amazon Bedrock :"
            + " passer cette Question en grilling, ou utiliser le fournisseur anthropic.";

    private final boolean bedrock;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final String region;
    private final String profil;
    private AnthropicClient client;

    Anthropic(@Value("${productdesigner.anthropic.fournisseur:anthropic}") String fournisseur,
              @Value("${productdesigner.anthropic.api-key:}") String apiKey,
              @Value("${productdesigner.anthropic.model:}") String model,
              @Value("${productdesigner.anthropic.base-url:}") String baseUrl,
              @Value("${productdesigner.bedrock.region:}") String region,
              @Value("${productdesigner.bedrock.profil:}") String profil) {
        this.bedrock = switch (fournisseur) {
            case "anthropic" -> false;
            case "bedrock" -> true;
            default -> throw new IllegalArgumentException("productdesigner.anthropic.fournisseur : anthropic ou bedrock, pas " + fournisseur);
        };
        this.apiKey = apiKey;
        // Sur Bedrock, les identifiants de modèle portent le préfixe « anthropic. ».
        this.model = !model.isBlank() ? model : bedrock ? "anthropic.claude-opus-5-5" : "claude-opus-5-5";
        this.baseUrl = baseUrl;
        this.region = region;
        this.profil = profil;
    }

    public String model() {
        return model;
    }

    public String fournisseur() {
        return bedrock ? "bedrock" : "anthropic";
    }

    /** Bedrock : les identifiants AWS ne sont vérifiés qu'au premier appel (chaîne standard, SSO compris). */
    public boolean configured() {
        return bedrock || !apiKey.isBlank();
    }

    /** Outils serveur (web search) : API Anthropic seulement. */
    public boolean rechercheWeb() {
        return !bedrock;
    }

    public synchronized AnthropicClient client() {
        if (!configured()) {
            throw new ConfigManquanteException(CLE_ABSENTE);
        }
        if (client == null) {
            client = bedrock ? bedrockClient() : AnthropicOkHttpClient.builder().apiKey(apiKey)
                    .baseUrl(baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl).build();
        }
        return client;
    }

    /** Région et profil vides : repli sur la chaîne AWS standard (AWS_REGION, AWS_PROFILE, ~/.aws/config…). */
    private AnthropicClient bedrockClient() {
        BedrockMantleBackend.Builder backend = BedrockMantleBackend.builder();
        if (!profil.isBlank()) {
            backend.awsProfile(profil);
        }
        if (!region.isBlank()) {
            backend.region(Region.of(region));
        } else if (!profil.isBlank()) {
            backend.region(DefaultAwsRegionProviderChain.builder().profileName(profil).build().getRegion());
        }
        if (!baseUrl.isBlank()) {
            // Tests : faux serveur local, sans signature AWS.
            backend.baseUrl(baseUrl).skipAuth(true);
        }
        if (profil.isBlank() && region.isBlank() && baseUrl.isBlank()) {
            backend.fromEnv();
        }
        return AnthropicOkHttpClient.builder().backend(backend.build()).build();
    }
}
