package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Sessions de bout en bout : le CLI Claude Code est remplacé par un script qui transmet prompt et arguments à un faux
 * serveur, lequel rejoue des sorties stream-json préparées.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionTest {

    static HttpServer fauxClaude;
    /** Chaque appel : arguments du CLI (un par ligne), puis le prompt reçu sur stdin. */
    static final List<String> requetes = new CopyOnWriteArrayList<>();
    static final Queue<String> reponses = new ConcurrentLinkedQueue<>();

    static String json(String texte) {
        return texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Événements stream-json d'un texte (en deux deltas), sans la ligne result. */
    static String deltas(String texte) {
        StringBuilder sb = new StringBuilder("{\"type\":\"system\",\"subtype\":\"init\"}\n");
        if (!texte.isEmpty()) {
            sb.append("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}}\n");
            int moitie = texte.length() / 2;
            for (String morceau : List.of(texte.substring(0, moitie), texte.substring(moitie))) {
                sb.append("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"")
                        .append(json(morceau)).append("\"}}}\n");
            }
        }
        return sb.toString();
    }

    /** Sortie d'un appel : le texte streamé, puis le result (10 tokens en entrée, 5 en sortie) avec la sortie structurée. */
    static String stream(String texte, String structure) {
        return deltas(texte) + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"" + json(texte)
                + "\",\"session_id\":\"s\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_read_input_tokens\":0,"
                + "\"cache_creation_input_tokens\":0,\"server_tool_use\":{\"web_search_requests\":0}},\"modelUsage\":{\"claude-opus-5-5\":{}}"
                + (structure == null ? "" : ",\"structured_output\":" + structure.replace("\n", "")) + "}\n";
    }

    /** Échec du CLI : result en erreur, code de sortie 1. */
    static String erreur(String message) {
        return "ERREUR {\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"result\":\"" + json(message) + "\"}\n";
    }

    static final String ROUND = """
            {"round":{"questions":[{"id":"Q1","titre":"Cible","corps":"Pour qui ?","recommandation":"Les devs"}]},"decisions":[],"questions":[]}""";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        fauxClaude = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fauxClaude.createContext("/claude", exchange -> {
            String args = new String(java.util.Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("X-Args")), StandardCharsets.UTF_8);
            requetes.add(args + "\n" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String reponse = reponses.poll();
            if (reponse == null) {
                reponse = stream("Rien.", null);
            }
            if (reponse.startsWith("LENT ")) {
                // "LENT <réponse>" : répond après 1,5 s, pour tester les envois concurrents.
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reponse = reponse.substring(5);
            }
            // "ERREUR <sortie>" ou "COUPE <sortie>" : le CLI sort en code 1 (erreur, ou sortie tronquée sans result).
            int code = reponse.startsWith("ERREUR ") || reponse.startsWith("COUPE ") ? 500 : 200;
            byte[] body = (code == 200 ? reponse : reponse.substring(reponse.indexOf(' ') + 1)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        fauxClaude.start();
        Path dir = Files.createTempDirectory("productdesigner");
        Path stub = dir.resolve("claude");
        Files.writeString(stub, """
                #!/bin/sh
                corps=$(mktemp)
                code=$(curl -s -o "$corps" -w '%%{http_code}' --data-binary @- -H "X-Args: $(printf '%%s\\n' "$@" | base64 -w0)" http://localhost:%d/claude)
                cat "$corps"; rm -f "$corps"
                [ "$code" = 200 ]
                """.formatted(fauxClaude.getAddress().getPort()));
        stub.toFile().setExecutable(true);
        registry.add("productdesigner.data-dir", dir::toString);
        registry.add("spring.ai.claude-code.executable", stub::toString);
        // Faux CLI : 10 tokens en entrée, soit 10 % d'une fenêtre de 100 ; seuil abaissé à 5 %.
        registry.add("productdesigner.contexte.fenetre", () -> "100");
        registry.add("productdesigner.contexte.seuil", () -> "0.05");
    }

    @AfterAll
    static void stop() {
        fauxClaude.stop(0);
    }

    @BeforeEach
    void reset() {
        requetes.clear();
        reponses.clear();
    }

    @LocalServerPort
    int port;

    String send(String method, String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                        .build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Crée un Projet et une Session grilling, renvoie l'id de la Session. */
    String nouvelleSession() throws Exception {
        String projet = send("POST", "/api/projets", "{\"nom\":\"P\",\"description\":\"Une idée\"}");
        String projetId = projet.replaceAll(".*\"id\":(\\d+).*", "$1");
        String session = send("POST", "/api/projets/" + projetId + "/sessions", "{\"methode\":\"grilling\"}");
        return session.replaceAll(".*\"id\":(\\d+).*", "$1");
    }

    @Test
    void roundStreamePuisHistoriqueRejoueAvecLeRecord() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Bonjour, voici Q1.", ROUND));

        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(sse).contains("event:delta", "Bonjour, ", "voici Q1.", "event:record", "\"recommandation\":\"Les devs\"", "event:fin");
        assertThat(sse).doesNotContain("avertissement");
        assertThat(requetes).hasSize(1);
        assertThat(requetes.get(0)).contains("--json-schema", "\"prototype_html\"", "--system-prompt", "arbre de décisions",
                "Champs utilisés par cette Méthode : round, decision", "Une idée", "--setting-sources", "--strict-mcp-config");
        // Aucun outil : ni Bash, ni fichiers, ni web.
        assertThat(requetes.get(0)).contains("--tools\n\n").doesNotContain("WebSearch");
        assertThat(requetes.get(0)).doesNotContain("Matt Pocock");

        assertThat(send("GET", "/api/sessions/" + id, null))
                .contains("\"role\":\"user\",\"texte\":\"Salut\"", "\"texte\":\"Bonjour, voici Q1.\"", "\"titre\":\"Cible\"");

        reponses.add(stream("Noté.", ROUND));
        send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Q1: Les devs\"}");
        String second = requetes.get(1);
        // Historique rejoué en texte dans l'ordre, record compris.
        assertThat(second.indexOf("Salut")).isLessThan(second.indexOf("voici Q1."));
        assertThat(second).contains("<record>{\"round\":{\"questions\":[{\"id\":\"Q1\"");
        assertThat(second.indexOf("<record>")).isLessThan(second.indexOf("Q1: Les devs"));
    }

    @Test
    void decisionProposeeNestEnBaseQuApresConfirmation() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Noté.", """
                {"round":{"questions":[]},"decisions":[{"texte":"Cible : les devs"},{"texte":"Sortie en Markdown"}],"questions":[]}"""));
        send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Q1: oui\"}");

        String panneau = send("GET", "/api/sessions/" + id + "/decisions", null);
        assertThat(panneau).contains("\"texte\":\"Cible : les devs\"", "\"indice\":1", "\"decisions\":[]");
        long messageId = Long.parseLong(panneau.replaceAll(".*?\"messageId\":(\\d+).*", "$1"));

        // Modifier puis confirmer : D-1 avec le texte édité ; la proposition sort de la liste.
        String d1 = send("POST", "/api/sessions/" + id + "/decisions",
                "{\"messageId\":" + messageId + ",\"indice\":0,\"texte\":\"Cible : les devs backend\"}");
        assertThat(d1).contains("\"numero\":1", "Cible : les devs backend");
        assertThat(send("POST", "/api/sessions/" + id + "/decisions",
                "{\"messageId\":" + messageId + ",\"indice\":1,\"texte\":\"Sortie en Markdown\"}")).contains("\"numero\":2");
        assertThat(send("POST", "/api/sessions/" + id + "/decisions",
                "{\"messageId\":" + messageId + ",\"indice\":0,\"texte\":\"doublon\"}")).contains("déjà confirmée");

        panneau = send("GET", "/api/sessions/" + id + "/decisions", null);
        assertThat(panneau).startsWith("{\"proposees\":[]").contains("Cible : les devs backend", "\"numero\":2");
    }

    @Test
    void creerUnProjetOuvreLaSessionDeTraceQuiRemplitLaMap() throws Exception {
        String projet = send("POST", "/api/projets", "{\"nom\":\"Carte\",\"description\":\"Une idée floue\"}");
        assertThat(projet).contains("\"sessionTraceId\":");
        String projetId = projet.replaceAll(".*\"id\":(\\d+),.*", "$1");
        String session = projet.replaceAll(".*\"sessionTraceId\":(\\d+).*", "$1");
        assertThat(send("GET", "/api/sessions/" + session, null)).contains("\"methode\":\"wayfinder\"");

        String record = """
                {"round":{"questions":[]},"decisions":[],
                 "questions":[{"titre":"Choix du SDK","corps":"SDK ou HTTP ?","type":"research","blocked_by":[]},
                              {"titre":"Modèle de données","corps":"Quelles entités ?","type":"grilling","blocked_by":["Choix du SDK"]}],
                 "map":{"destination":"Spec MVP prête à découper","brouillard":["Gestion des coûts"],"hors_perimetre":["Multi-utilisateur"]}}""";
        reponses.add(stream("Voici la Map.", record));
        send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Mon idée\"}");
        assertThat(requetes.getLast()).contains("Tu planifies, tu ne fais pas", "round, map, questions");

        // Un 2e record répétant une Question ne la duplique pas.
        reponses.add(stream("Je complète.", record));
        send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"ok\"}");

        String map = send("GET", "/api/projets/" + projetId + "/map", null);
        assertThat(map).contains("\"destination\":\"Spec MVP prête à découper\"",
                "\"numero\":1,\"titre\":\"Choix du SDK\"", "\"type\":\"research\"",
                "\"numero\":2,\"titre\":\"Modèle de données\"", "\"blockedBy\":[1]",
                "Gestion des coûts", "Multi-utilisateur");
        assertThat(map).doesNotContain("\"numero\":3");
    }

    @Test
    void sessionSurUneQuestionLaPorteDansLeContexte() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Q\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        send("PUT", "/api/projets/" + projetId + "/map", "{\"destination\":\"Spec MVP\",\"notes\":\"\"}");
        String carte = send("POST", "/api/projets/" + projetId + "/questions",
                "{\"titre\":\"Choix du stockage\",\"corps\":\"SQLite ou fichiers ?\",\"type\":\"grilling\",\"blockedBy\":[]}");
        String questionId = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        String session = send("POST", "/api/projets/" + projetId + "/questions/" + questionId + "/sessions", null)
                .replaceAll(".*?\"id\":(\\d+),.*", "$1");

        reponses.add(stream("Allons-y.", ROUND));
        send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Go\"}");
        assertThat(requetes.getLast()).contains("Destination de la Map : Spec MVP",
                "Question à résoudre dans cette Session : Choix du stockage", "SQLite ou fichiers ?");
    }

    @Test
    void cloreResoutOuRouvreLaQuestion() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Clore\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        send("POST", p + "/questions", "{\"titre\":\"A\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[]}");
        send("POST", p + "/questions", "{\"titre\":\"B\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[1]}");
        String carte = send("GET", p + "/map", null);
        String qa = carte.replaceAll(".*\\{\"id\":(\\d+),\"numero\":1,.*", "$1");
        String qb = carte.replaceAll(".*\\{\"id\":(\\d+),\"numero\":2,.*", "$1");

        // Sans Décision : retour à open.
        String s1 = send("POST", p + "/questions/" + qa + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        assertThat(send("POST", "/api/sessions/" + s1 + "/clore", null)).contains("\"statut\":\"close\"");
        assertThat(send("GET", p + "/map", null)).contains("\"numero\":1,\"titre\":\"A\",\"corps\":\"\",\"type\":\"grilling\",\"statut\":\"open\"");
        assertThat(send("POST", "/api/sessions/" + s1 + "/messages", "{\"texte\":\"encore\"}")).contains("Session close");

        // Avec une Décision confirmée : resolved, et B entre dans la frontière.
        String s2 = send("POST", p + "/questions/" + qa + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        reponses.add(stream("Noté.", """
                {"round":{"questions":[]},"decisions":[{"texte":"A tranchée"}],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]}}"""));
        send("POST", "/api/sessions/" + s2 + "/messages", "{\"texte\":\"oui\"}");
        String messageId = send("GET", "/api/sessions/" + s2 + "/decisions", null).replaceAll(".*?\"messageId\":(\\d+).*", "$1");
        send("POST", "/api/sessions/" + s2 + "/decisions", "{\"messageId\":" + messageId + ",\"indice\":0,\"texte\":\"A tranchée\"}");
        send("POST", "/api/sessions/" + s2 + "/clore", null);
        carte = send("GET", p + "/map", null);
        assertThat(carte).contains("\"titre\":\"A\",\"corps\":\"\",\"type\":\"grilling\",\"statut\":\"resolved\"",
                "\"id\":" + qb + ",\"numero\":2,\"titre\":\"B\",\"corps\":\"\",\"type\":\"grilling\",\"statut\":\"open\",\"blockedBy\":[1],\"ouvrable\":true");
    }

    @Test
    void continuerDansUneNouvelleSession() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Long\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"Gros sujet\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[]}");
        send("POST", p + "/brouillard", "{\"texte\":\"Zone floue\"}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        String s1 = send("POST", p + "/questions/" + q + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");

        assertThat(send("GET", "/api/sessions/" + s1 + "/contexte", null)).contains("\"seuilAtteint\":false");
        reponses.add(stream("Réponse longue.", """
                {"round":{"questions":[]},"decisions":[{"texte":"Choix X retenu"}],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]}}"""));
        send("POST", "/api/sessions/" + s1 + "/messages", "{\"texte\":\"Historique à ne pas reprendre\"}");
        String messageId = send("GET", "/api/sessions/" + s1 + "/decisions", null).replaceAll(".*?\"messageId\":(\\d+).*", "$1");
        send("POST", "/api/sessions/" + s1 + "/decisions", "{\"messageId\":" + messageId + ",\"indice\":0,\"texte\":\"Choix X retenu\"}");
        assertThat(send("GET", "/api/sessions/" + s1 + "/contexte", null)).contains("\"tokens\":10", "\"seuilAtteint\":true");

        String s2 = send("POST", "/api/sessions/" + s1 + "/continuer", null);
        assertThat(s2).contains("\"questionId\":" + q, "\"continuedFrom\":" + s1, "\"statut\":\"active\"");
        assertThat(send("GET", "/api/sessions/" + s1, null)).contains("\"statut\":\"close\"");
        assertThat(send("GET", p + "/map", null)).contains("\"titre\":\"Gros sujet\",\"corps\":\"\",\"type\":\"grilling\",\"statut\":\"claimed\"");

        String s2id = s2.replaceAll(".*?\"id\":(\\d+),.*", "$1");
        reponses.add(stream("On reprend.", ROUND));
        send("POST", "/api/sessions/" + s2id + "/messages", "{\"texte\":\"Reprenons\"}");
        assertThat(requetes.getLast()).contains("prend la suite", "Question 1 [grilling, claimed] Gros sujet",
                "Brouillard : Zone floue", "D-1 : Choix X retenu", "Question à résoudre dans cette Session : Gros sujet");
        assertThat(requetes.getLast()).doesNotContain("Historique à ne pas reprendre");
    }

    @Test
    void researchEnTacheDeFondAvecRechercheWeb() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"R\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"API Jira\",\"corps\":\"Comment créer un Epic ?\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        reponses.add(stream("Je cherche.\n\n# Rapport Jira\n\nUtiliser le champ parent.", """
                {"round":{"questions":[]},"decisions":[{"texte":"Lier les Stories par parent"}],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]}}""")
                .replace("\"web_search_requests\":0", "\"web_search_requests\":1"));

        String session = send("POST", p + "/questions/" + q + "/sessions", null);
        assertThat(session).contains("\"methode\":\"research\"", "\"tache\":\"en_cours\"");
        String id = session.replaceAll(".*?\"id\":(\\d+),.*", "$1");

        String detail = "";
        for (int i = 0; i < 100 && !detail.contains("\"tache\":\"terminee\"") && !detail.contains("echec"); i++) {
            Thread.sleep(100);
            detail = send("GET", "/api/sessions/" + id, null);
        }
        assertThat(detail).contains("\"tache\":\"terminee\"", "1 recherche(s) web");
        assertThat(requetes).hasSize(1);
        assertThat(requetes.get(0)).contains("--tools\nWebSearch", "--allowedTools\nWebSearch", "sources primaires", "Comment créer un Epic ?");

        assertThat(send("GET", "/api/sessions/" + id + "/artefacts", null))
                .contains("\"type\":\"research_md\"", "# Rapport Jira", "champ parent").doesNotContain("Je cherche");
        assertThat(send("GET", "/api/sessions/" + id + "/decisions", null)).contains("Lier les Stories par parent");
    }

    @Test
    void prototypeEnregistreUnArtefactHtml() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Proto\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"Écran\",\"corps\":\"À quoi ressemble-t-il ?\",\"type\":\"prototype\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        String session = send("POST", p + "/questions/" + q + "/sessions", null);
        assertThat(session).contains("\"methode\":\"prototype\"");
        String id = session.replaceAll(".*?\"id\":(\\d+),.*", "$1");

        reponses.add(stream("Voici 3 variantes.", """
                {"round":{"questions":[]},"decisions":[],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]},
                 "prototype_html":"<!doctype html><h1>Variante A</h1>"}"""));
        send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Go\"}");
        assertThat(requetes.getLast()).contains("Un seul document HTML autonome", "\"prototype_html\"");
        assertThat(send("GET", "/api/sessions/" + id + "/artefacts", null))
                .contains("\"type\":\"prototype_html\"", "<h1>Variante A</h1>");
    }

    @Test
    void requeteDepuisUnAutreSiteRefusee() throws Exception {
        for (String origine : new String[] {"https://evil.example", "http://localhost.evil.example"}) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projets"))
                    .header("Origin", origine).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"nom\":\"csrf\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(origine).isEqualTo(403);
        }
        HttpResponse<String> local = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projets"))
                .header("Origin", "http://localhost:5173").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"nom\":\"vite\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(local.statusCode()).isEqualTo(201);
        assertThat(send("GET", "/api/projets", null)).doesNotContain("csrf");
    }

    @Test
    void requeteDepuisUneIframeSandboxRefusee() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projets"))
                .header("Origin", "null").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{\"nom\":\"pirate\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/projets", null)).doesNotContain("pirate");
    }

    @Test
    void genererLaSpecDepuisLaMapEtLesDecisions() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Spec\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        send("PUT", p + "/map", "{\"destination\":\"MVP prêt\",\"notes\":\"\"}");
        send("POST", p + "/brouillard", "{\"texte\":\"Synchro\"}");

        reponses.add(stream("# Spec : Spec\n\n## 1. Problème et Destination\nX\n\n## 5. Exigences fonctionnelles\n- **E-1** Faire [D-1]\n", null));
        String sse = send("POST", p + "/specs", null);
        assertThat(sse).contains("event:delta", "event:fin", "\"version\":1", "E-1 ne cite aucune Décision existante (D-1 inconnue(s)).");
        assertThat(requetes.getLast()).contains("le quoi, pas le comment", "Destination : MVP prêt", "Brouillard : Synchro");

        String versions = send("GET", p + "/specs", null);
        String specId = versions.replaceAll(".*?\"id\":(\\d+),.*", "$1");
        assertThat(send("GET", "/api/specs/" + specId, null)).contains("## 3. Périmètre / Hors périmètre\\n\\nNon décidé.");

        // Une 2e génération crée la version 2 ; la 1 reste consultable (E-22).
        reponses.add(stream("# Spec : Spec v2\n", null));
        assertThat(send("POST", p + "/specs", null)).contains("\"version\":2");
        assertThat(send("GET", p + "/specs", null)).contains("\"version\":2", "\"version\":1");
        assertThat(send("GET", "/api/specs/" + specId, null)).contains("\"version\":1", "- **E-1** Faire [D-1]");
        // Les générations de Spec comptent dans la consommation du Projet (2 appels à 5 tokens de sortie).
        assertThat(send("GET", "/api/projets/" + projetId + "/consommation", null)).contains("\"outputTokens\":10");
    }

    @Test
    void genererLaRoadmapDUneVersionDeSpec() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Roadmap\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        reponses.add(stream("# Spec\n\n## 5. Exigences fonctionnelles\n- **E-1** Créer [D-1]\n- **E-2** Lister [D-1]\n- **E-3** Exporter [D-1]\n", null));
        send("POST", "/api/projets/" + projetId + "/specs", null);
        String specId = send("GET", "/api/projets/" + projetId + "/specs", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");

        String roadmap = """
                {"jalons":[{"titre":"Socle","tickets":[
                  {"titre":"Créer une note","corps":"L'utilisateur crée une note.","criteres":["La note apparaît"],"exigences":["E-1"],"blockedBy":[]},
                  {"titre":"Lister les notes","corps":"Liste.","criteres":["Tri par date"],"exigences":["E-2","E-9"],"blockedBy":["Créer une note"]}
                ]}]}""".replace("\n", "");
        reponses.add(stream("Je découpe.", roadmap));
        String sse = send("POST", "/api/specs/" + specId + "/roadmap", null);
        assertThat(sse).contains("event:progression", "event:fin", "2 Tickets (attendu : 3 à 10)",
                "exigences absentes de la Spec : E-9", "Exigences non couvertes : E-3.");
        assertThat(requetes.getLast()).contains("--json-schema", "\"blockedBy\"", "vertical slice", "- **E-3** Exporter");

        String lue = send("GET", "/api/specs/" + specId + "/roadmap", null);
        assertThat(lue).contains("\"version\":1", "\"titre\":\"Socle\"", "\"titre\":\"Créer une note\"",
                "## Critères d'acceptation\\n\\n- [ ] La note apparaît", "\"exigences\":[\"E-2\",\"E-9\"]");
        String premier = lue.replaceAll(".*?\"tickets\":\\[\\{\"id\":(\\d+),.*", "$1");
        assertThat(lue).contains("\"blockedBy\":[" + premier + "]");

        // Régénérer remplace l'ensemble.
        reponses.add(stream("", roadmap.replace("Socle", "Socle v2")));
        send("POST", "/api/specs/" + specId + "/roadmap", null);
        assertThat(send("GET", "/api/specs/" + specId + "/roadmap", null)).contains("Socle v2").doesNotContain("\"titre\":\"Socle\"");
    }

    @Test
    void uneSeuleReponseALaFoisParSession() throws Exception {
        String id = nouvelleSession();
        reponses.add("LENT " + stream("Lente.", ROUND));
        var premier = HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/sessions/" + id + "/messages"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"texte\":\"Un\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(400);
        assertThat(send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Deux\"}")).contains("Une réponse est déjà en cours");
        assertThat(send("POST", "/api/sessions/" + id + "/clore", null)).contains("Une réponse est déjà en cours");
        assertThat(premier.get().body()).contains("event:record");
        // Verrou libéré : l'échange suivant passe, et « Deux » n'a jamais été enregistré.
        reponses.add(stream("Ok.", ROUND));
        assertThat(send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Trois\"}")).contains("event:record");
        assertThat(send("GET", "/api/sessions/" + id, null)).doesNotContain("Deux");
    }

    @Test
    void roadmapRegenereeEnEchecNeDetruitPasLAncienne() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Atomique\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        reponses.add(stream("# Spec\n", null));
        send("POST", "/api/projets/" + projetId + "/specs", null);
        String specId = send("GET", "/api/projets/" + projetId + "/specs", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        String ok = "{\"jalons\":[{\"titre\":\"Ancien\",\"tickets\":[{\"titre\":\"T\",\"corps\":\"c\",\"criteres\":[],\"exigences\":[],\"blockedBy\":[]}]}]}";
        reponses.add(stream("", ok));
        send("POST", "/api/specs/" + specId + "/roadmap", null);

        // 2e Jalon sans titre : NOT NULL violé à mi-parcours.
        String casse = "{\"jalons\":[{\"titre\":\"Nouveau\",\"tickets\":[]},{\"titre\":null,\"tickets\":[]}]}";
        reponses.add(stream("", casse));
        assertThat(send("POST", "/api/specs/" + specId + "/roadmap", null)).contains("event:erreur");
        assertThat(send("GET", "/api/specs/" + specId + "/roadmap", null)).contains("\"titre\":\"Ancien\"").doesNotContain("Nouveau");
    }

    @Test
    void relancerRefuseSiDesDecisionsSontConfirmees() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"RelanceDecision\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String carte = send("POST", "/api/projets/" + projetId + "/questions", "{\"titre\":\"F\",\"corps\":\"\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        reponses.add(stream("# Rapport\n\nFait.", """
                {"round":{"questions":[]},"decisions":[{"texte":"Retenu"}],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]},"prototype_html":""}"""));
        String id = send("POST", "/api/projets/" + projetId + "/questions/" + q + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        attendreFin(id);
        String messageId = send("GET", "/api/sessions/" + id + "/decisions", null).replaceAll(".*?\"messageId\":(\\d+).*", "$1");
        send("POST", "/api/sessions/" + id + "/decisions", "{\"messageId\":" + messageId + ",\"indice\":0,\"texte\":\"Retenu\"}");
        jdbc.sql("UPDATE session SET tache = 'echec' WHERE id = ?").param(id).update();
        assertThat(send("POST", "/api/sessions/" + id + "/relancer", null)).contains("Des Décisions de cette research sont déjà confirmées");
        assertThat(send("GET", "/api/sessions/" + id + "/decisions", null)).contains("Retenu");
    }

    @Test
    void reessayableCalculeSurLHistoriqueMasque() throws Exception {
        String id = nouvelleSession();
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("\"reessayable\":false");
        reponses.add(erreur("API Error: 500 boom"));
        assertThat(send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}")).contains("event:erreur", "boom", "\"reessayable\":true");
        // L'historique finit par le message de l'utilisateur, sans réponse.
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("\"texte\":\"Salut\"", "\"reessayable\":true");
        reponses.add(stream("Voilà.", ROUND));
        assertThat(send("POST", "/api/sessions/" + id + "/reessayer", null)).contains("event:record");
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("\"reessayable\":false");
    }

    @Test
    void recordAvecAccentsIntactDansLeSse() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Voilà.", """
                {"round":{"questions":[{"id":"Q1","titre":"Ingrédients structurés ?","corps":"Négociable, à voir ; « garçon » — ça ira.","recommandation":"Oui : structurés"}]},
                 "decisions":[{"texte":"Décidé : vérifié"}],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]},"prototype_html":""}"""));
        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(sse).contains("Ingrédients structurés ?", "Négociable, à voir ; « garçon » — ça ira.", "Décidé : vérifié");
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("Ingrédients structurés ?");

        // Gros record (comme en réel : plusieurs milliers de caractères accentués).
        String long_ = "Les ingrédients structurés réduisent la vérification à la caisse. ".repeat(80);
        reponses.add(stream("Voilà.", "{\"round\":{\"questions\":[{\"id\":\"Q1\",\"titre\":\"Négociable ?\",\"corps\":\"" + long_
                + "\",\"recommandation\":\"Oui : structurés\"}]},\"decisions\":[],\"questions\":[],\"map\":{\"destination\":\"\",\"brouillard\":[],\"hors_perimetre\":[]},\"prototype_html\":\"\"}"));
        String gros = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Q1: oui\"}");
        assertThat(gros).contains("Négociable ?", "Oui : structurés", long_.strip()).doesNotContain("ingr\\n");
    }

    @Autowired
    SessionController sessions;

    @Autowired
    JdbcClient jdbc;

    @Test
    void researchInterrompueParRedemarragePuisRelancee() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Redemarrage\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"Fait à trouver\",\"corps\":\"\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        reponses.add(stream("Encore une pause ?", null));
        String id = send("POST", p + "/questions/" + q + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        attendreFin(id);

        // Simule un arrêt pendant la research : statut resté en_cours, puis démarrage.
        jdbc.sql("UPDATE session SET tache = 'en_cours' WHERE id = ?").param(id).update();
        sessions.interrompreResearches();
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("\"tache\":\"echec\"", "redémarrage");

        requetes.clear();
        reponses.add(stream("# Rapport\n\nTrouvé.", ROUND));
        assertThat(send("POST", "/api/sessions/" + id + "/relancer", null)).contains("\"tache\":\"en_cours\"");
        assertThat(attendreFin(id)).contains("\"tache\":\"terminee\"");
        // Repart de zéro : seule la consigne, sans les messages de la tentative ratée.
        assertThat(requetes.getFirst()).contains(SessionController.RESEARCH).doesNotContain("Encore une pause");
        assertThat(send("POST", "/api/sessions/" + id + "/relancer", null)).contains("Seule une research en échec");
        assertThat(jdbc.sql("SELECT count(*) FROM artefact WHERE session_id = ?").param(id).query(Integer.class).single()).isEqualTo(1);
    }

    String attendreFin(String id) throws Exception {
        String detail = "";
        for (int i = 0; i < 100; i++) {
            detail = send("GET", "/api/sessions/" + id, null);
            if (!detail.contains("\"tache\":\"en_cours\"")) {
                break;
            }
            Thread.sleep(100);
        }
        return detail;
    }

    @Test
    void consommationParSessionEtParProjet() throws Exception {
        String projet = send("POST", "/api/projets", "{\"nom\":\"Conso\"}").replaceAll(".*\"id\":(\\d+).*", "$1");
        for (int i = 0; i < 2; i++) {
            String session = send("POST", "/api/projets/" + projet + "/sessions", "{\"methode\":\"grilling\"}")
                    .replaceAll(".*\"id\":(\\d+).*", "$1");
            reponses.add(stream("Voilà.", ROUND));
            send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Salut\"}");
            // Faux serveur : 10 tokens en entrée, 5 en sortie ; Opus 5.5 à 4 $ / 20 $ par million.
            assertThat(send("GET", "/api/sessions/" + session + "/consommation", null))
                    .contains("\"inputTokens\":10", "\"outputTokens\":5", "\"cout\":0.000140");
        }
        assertThat(send("GET", "/api/projets/" + projet + "/consommation", null))
                .contains("\"inputTokens\":20", "\"outputTokens\":10", "\"cout\":0.000280");
    }

    @Test
    void streamCoupeGardeLePartielPuisReessayerSansLui() throws Exception {
        String id = nouvelleSession();
        String complet = stream("Début de réponse, puis la suite.", ROUND);
        reponses.add("COUPE " + complet.substring(0, complet.indexOf("{\"type\":\"result\"")));

        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(sse).contains("event:delta", "event:erreur", "Réponse interrompue", "\"reessayable\":true");
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("\"texte\":\"Début de réponse, puis la suite.\"", "\"incomplet\":true");

        reponses.add(stream("Réponse complète.", ROUND));
        assertThat(send("POST", "/api/sessions/" + id + "/reessayer", null)).contains("event:record", "event:fin");
        String rejoue = requetes.getLast();
        assertThat(rejoue).contains("Salut").doesNotContain("Début de réponse");
        assertThat(send("GET", "/api/sessions/" + id, null)).contains("Réponse complète.");
        assertThat(send("POST", "/api/sessions/" + id + "/reessayer", null)).contains("Rien à réessayer");
    }

    @Test
    void nonAuthentifieMessageClair() throws Exception {
        String id = nouvelleSession();
        reponses.add(erreur("Not logged in · Please run /login"));
        assertThat(send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}"))
                .contains("event:erreur", "Claude Code n'est pas authentifié", "claude setup-token");
        assertThat(requetes).hasSize(1);
    }

    @Test
    void sansRecordAvertissement() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Texte seul.", null));

        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(requetes).hasSize(1);
        assertThat(sse).contains("event:avertissement", "Réponse non structurée", "event:fin");
    }

}
