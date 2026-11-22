package productdesigner;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {

    private final Claude claude;
    private final ClaudeToken token;

    StatusController(Claude claude, ClaudeToken token) {
        this.claude = claude;
        this.token = token;
    }

    /** token : état du token saisi dans l'interface ; sans token, le CLI utilise la connexion locale de Claude Code. */
    @GetMapping("/api/status")
    Map<String, Object> status() {
        return Map.of("model", claude.model(), "token", token.etat());
    }
}
