package productdesigner;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {

    private final Anthropic anthropic;

    StatusController(Anthropic anthropic) {
        this.anthropic = anthropic;
    }

    @GetMapping("/api/status")
    Map<String, Object> status() {
        return anthropic.configured()
                ? Map.of("anthropic", true, "fournisseur", anthropic.fournisseur(), "model", anthropic.model(),
                        "rechercheWeb", anthropic.rechercheWeb())
                : Map.of("anthropic", false, "message", Anthropic.CLE_ABSENTE);
    }
}
