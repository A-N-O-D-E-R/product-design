package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Sessions de bout en bout contre un faux serveur Anthropic qui rejoue des streams préparés. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionTest {

    static HttpServer fauxAnthropic;
    static final List<String> requetes = new CopyOnWriteArrayList<>();
    static final Queue<String> reponses = new ConcurrentLinkedQueue<>();

    static String event(String type, String json) {
        return "event: " + type + "\ndata: " + json + "\n\n";
    }

    static String json(String texte) {
        return texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Stream d'un message : un bloc texte, puis éventuellement un appel record. */
    static String stream(String texte, String recordInput) {
        StringBuilder sb = new StringBuilder(event("message_start", """
                {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}"""));
        sb.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        int moitie = texte.length() / 2;
        for (String morceau : List.of(texte.substring(0, moitie), texte.substring(moitie))) {
            sb.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + json(morceau) + "\"}}"));
        }
        sb.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"));
        if (recordInput != null) {
            sb.append(event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"record\",\"input\":{}}}"));
            String partial = recordInput.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            sb.append(event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"" + partial + "\"}}"));
            sb.append(event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"));
        }
        String stop = recordInput == null ? "end_turn" : "tool_use";
        sb.append(event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}"));
        sb.append(event("message_stop", "{\"type\":\"message_stop\"}"));
        return sb.toString();
    }

    static final String ROUND = """
            {"round":{"questions":[{"id":"Q1","titre":"Cible","corps":"Pour qui ?","recommandation":"Les devs"}]},"decisions":[],"questions":[]}""";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        fauxAnthropic = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fauxAnthropic.createContext("/v1/messages", exchange -> {
            requetes.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
            if (reponse.startsWith("HTTP ")) {
                // "HTTP <code> <corps JSON>" : erreur de l'API.
                String[] parts = reponse.split(" ", 3);
                byte[] body = parts[2].getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(Integer.parseInt(parts[1]), body.length);
                exchange.getResponseBody().write(body);
            } else {
                // "COUPE <sse>" : stream tronqué, la connexion se ferme avant message_stop.
                boolean coupe = reponse.startsWith("COUPE ");
                byte[] body = (coupe ? reponse.substring(6) : reponse).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, coupe ? 0 : body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        fauxAnthropic.start();
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
        registry.add("productdesigner.anthropic.api-key", () -> "test");
        // Faux serveur : 10 tokens en entrée, soit 10 % d'une fenêtre de 100 ; seuil abaissé à 5 %.
        registry.add("productdesigner.contexte.fenetre", () -> "100");
        registry.add("productdesigner.contexte.seuil", () -> "0.05");
        registry.add("productdesigner.anthropic.base-url", () -> "http://localhost:" + fauxAnthropic.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fauxAnthropic.stop(0);
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
    void roundStreamePuisReponseRenvoieLeToolResult() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Bonjour, voici Q1.", ROUND));

        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(sse).contains("event:delta", "Bonjour, ", "voici Q1.", "event:record", "\"recommandation\":\"Les devs\"", "event:fin");
        assertThat(sse).doesNotContain("avertissement");
        assertThat(requetes).hasSize(1);
        assertThat(requetes.get(0)).contains("\"name\":\"record\"", "\"strict\":true", "arbre de décisions",
                "Champs utilisés par cette Méthode : round, decision", "\"cache_control\"", "Une idée");
        assertThat(requetes.get(0)).doesNotContain("Matt Pocock");

        assertThat(send("GET", "/api/sessions/" + id, null))
                .contains("\"role\":\"user\",\"texte\":\"Salut\"", "\"texte\":\"Bonjour, voici Q1.\"", "\"titre\":\"Cible\"");

        reponses.add(stream("Noté.", ROUND));
        send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Q1: Les devs\"}");
        String second = requetes.get(1);
        // Historique rejoué dans l'ordre ; le message suivant un record commence par son tool_result.
        assertThat(second.indexOf("Salut")).isLessThan(second.indexOf("voici Q1."));
        assertThat(second).contains("{\"tool_use_id\":\"toolu_1\",\"type\":\"tool_result\"");
        assertThat(second.indexOf("\"tool_use_id\":\"toolu_1\"")).isLessThan(second.indexOf("Q1: Les devs"));
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

    /** Stream d'une recherche web serveur interrompue par pause_turn. */
    static String streamPause() {
        return event("message_start", """
                {"type":"message_start","message":{"id":"msg_p","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""")
                + event("content_block_start", """
                {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""")
                + event("content_block_delta", """
                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"query\\":\\"jira api\\"}"}}""")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}")
                + event("content_block_start", """
                {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://developer.atlassian.com","title":"Jira","encrypted_content":"ENC-SECRET-123","page_age":null}]}}""")
                + event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}")
                + event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}")
                + event("message_stop", "{\"type\":\"message_stop\"}");
    }

    @Test
    void researchEnTacheDeFondAvecPauseTurn() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"R\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"API Jira\",\"corps\":\"Comment créer un Epic ?\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        reponses.add(streamPause());
        reponses.add(stream("# Rapport Jira\n\nUtiliser le champ parent.", """
                {"round":{"questions":[]},"decisions":[{"texte":"Lier les Stories par parent"}],"questions":[],"map":{"destination":"","brouillard":[],"hors_perimetre":[]}}"""));

        String session = send("POST", p + "/questions/" + q + "/sessions", null);
        assertThat(session).contains("\"methode\":\"research\"", "\"tache\":\"en_cours\"");
        String id = session.replaceAll(".*?\"id\":(\\d+),.*", "$1");

        String detail = "";
        for (int i = 0; i < 100 && !detail.contains("\"tache\":\"terminee\"") && !detail.contains("echec"); i++) {
            Thread.sleep(100);
            detail = send("GET", "/api/sessions/" + id, null);
        }
        assertThat(detail).contains("\"tache\":\"terminee\"", "1 recherche(s) web");
        assertThat(requetes).hasSize(2);
        assertThat(requetes.get(0)).contains("\"type\":\"web_search_20260209\"", "sources primaires", "Comment créer un Epic ?");
        // Le tour relancé après pause_turn renvoie le résultat chiffré tel quel.
        assertThat(requetes.get(1)).contains("\"encrypted_content\":\"ENC-SECRET-123\"", "srvtoolu_1");

        assertThat(send("GET", "/api/sessions/" + id + "/artefacts", null))
                .contains("\"type\":\"research_md\"", "# Rapport Jira", "champ parent");
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
        reponses.add(stream(roadmap, null));
        String sse = send("POST", "/api/specs/" + specId + "/roadmap", null);
        assertThat(sse).contains("event:progression", "event:fin", "2 Tickets (attendu : 3 à 10)",
                "exigences absentes de la Spec : E-9", "Exigences non couvertes : E-3.");
        assertThat(requetes.getLast()).contains("\"json_schema\"", "\"effort\":\"high\"", "vertical slice", "- **E-3** Exporter");

        String lue = send("GET", "/api/specs/" + specId + "/roadmap", null);
        assertThat(lue).contains("\"version\":1", "\"titre\":\"Socle\"", "\"titre\":\"Créer une note\"",
                "## Critères d'acceptation\\n\\n- [ ] La note apparaît", "\"exigences\":[\"E-2\",\"E-9\"]");
        String premier = lue.replaceAll(".*?\"tickets\":\\[\\{\"id\":(\\d+),.*", "$1");
        assertThat(lue).contains("\"blockedBy\":[" + premier + "]");

        // Régénérer remplace l'ensemble.
        reponses.add(stream(roadmap.replace("Socle", "Socle v2"), null));
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
    void pauseTurnGereeDansUneSessionInteractive() throws Exception {
        String projet = send("POST", "/api/projets", "{\"nom\":\"Pause\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String id = send("POST", "/api/projets/" + projet + "/sessions", "{\"methode\":\"research\"}").replaceAll(".*?\"id\":(\\d+),.*", "$1");
        reponses.add(streamPause());
        reponses.add(stream("Voilà la suite.", ROUND));
        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Cherche encore\"}");
        assertThat(requetes).hasSize(2);
        assertThat(requetes.get(1)).contains("ENC-SECRET-123");
        assertThat(sse).contains("event:record").doesNotContain("avertissement");
    }

    @Test
    void roadmapRegenereeEnEchecNeDetruitPasLAncienne() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"Atomique\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        reponses.add(stream("# Spec\n", null));
        send("POST", "/api/projets/" + projetId + "/specs", null);
        String specId = send("GET", "/api/projets/" + projetId + "/specs", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        String ok = "{\"jalons\":[{\"titre\":\"Ancien\",\"tickets\":[{\"titre\":\"T\",\"corps\":\"c\",\"criteres\":[],\"exigences\":[],\"blockedBy\":[]}]}]}";
        reponses.add(stream(ok, null));
        send("POST", "/api/specs/" + specId + "/roadmap", null);

        // 2e Jalon sans titre : NOT NULL violé à mi-parcours.
        String casse = "{\"jalons\":[{\"titre\":\"Nouveau\",\"tickets\":[]},{\"titre\":null,\"tickets\":[]}]}";
        reponses.add(stream(casse, null));
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
        reponses.add(streamPause());
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
        assertThat(requetes.getFirst()).contains(SessionController.RESEARCH).doesNotContain("ENC-SECRET-123", "Encore une pause");
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
        reponses.add("COUPE " + complet.substring(0, complet.indexOf("event: content_block_stop")));

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
    void cleInvalideMessageClair() throws Exception {
        String id = nouvelleSession();
        reponses.add("HTTP 401 {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        assertThat(send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}"))
                .contains("event:erreur", "Clé Anthropic invalide");
        assertThat(requetes).hasSize(1);
    }

    @Test
    void surchargeRetenteeParLeSdk() throws Exception {
        String id = nouvelleSession();
        reponses.add("HTTP 529 {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
        reponses.add(stream("Ça passe.", ROUND));
        assertThat(send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}"))
                .contains("asse.", "event:record").doesNotContain("event:erreur");
        assertThat(requetes).hasSize(2);
    }

    @Test
    void sansRecordUneRelanceMasqueePuisAvertissement() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Texte seul.", null));
        reponses.add(stream("Toujours rien.", null));

        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(requetes).hasSize(2);
        assertThat(requetes.get(1)).contains(Record.RELANCE);
        assertThat(sse).contains("event:avertissement", "Réponse non structurée", "event:fin");
        assertThat(send("GET", "/api/sessions/" + id, null)).doesNotContain("Appelle maintenant");
    }

    @Test
    void relanceReussieDonneLeRound() throws Exception {
        String id = nouvelleSession();
        reponses.add(stream("Texte seul.", null));
        reponses.add(stream("Voilà.", ROUND));

        String sse = send("POST", "/api/sessions/" + id + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(sse).contains("event:record", "\"titre\":\"Cible\"").doesNotContain("avertissement");
    }
}
