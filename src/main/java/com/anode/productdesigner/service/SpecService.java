package com.anode.productdesigner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.anode.productdesigner.repository.DecisionRepository.Decision;
import com.anode.productdesigner.repository.ProjetRepository;
import com.anode.productdesigner.repository.ProjetRepository.Projet;
import com.anode.productdesigner.repository.SpecRepository;
import com.anode.productdesigner.repository.SpecRepository.Creee;
import com.anode.productdesigner.repository.SpecRepository.Spec;
import com.anode.productdesigner.repository.SpecRepository.Version;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Spec d'un Projet (E-19 à E-22) : générée depuis la Map et les Décisions, au gabarit fixe (D-13), versionnée sans fusion. */
@Service
public class SpecService {

    /** Socle du gabarit (D-13), dans l'ordre. */
    public static final List<String> SOCLE = List.of(
            "1. Problème et Destination",
            "2. Utilisateurs et parcours clés",
            "3. Périmètre / Hors périmètre",
            "4. Modèle de domaine",
            "5. Exigences fonctionnelles",
            "6. Décisions structurantes",
            "7. Questions ouvertes / Brouillard restant");

    /** avertissements : écarts au gabarit ou aux règles (E-21). */
    public record Generee(long id, int version, List<String> avertissements) {}

    private final SpecRepository specs;
    private final ProjetRepository projets;
    private final MapService maps;
    private final DecisionService decisions;
    private final ConsommationService consommation;
    private final Llm llm;
    private final String prompt;

    public SpecService(SpecRepository specs, ProjetRepository projets, MapService maps, DecisionService decisions,
                       ConsommationService consommation, Llm llm) throws IOException {
        this.specs = specs;
        this.projets = projets;
        this.maps = maps;
        this.decisions = decisions;
        this.consommation = consommation;
        this.llm = llm;
        this.prompt = new ClassPathResource("prompts/spec.md").getContentAsString(StandardCharsets.UTF_8);
    }

    public List<Version> versions(long projetId) {
        return specs.versions(projetId);
    }

    public Spec spec(long id) {
        return specs.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
    }

    /** Ce qui doit échouer avant d'ouvrir le flux : Projet inconnu, LLM non configuré. */
    public void verifierGeneration(long projetId) {
        projet(projetId);
        llm.verifier();
    }

    /** Génère une nouvelle version, en relayant le texte streamé ; bloquant. */
    public Generee generer(long projetId, Llm.Deltas deltas) throws IOException {
        Projet projet = projet(projetId);
        String destination = maps.carte(projetId).destination();
        var messages = List.<Message>of(new SystemMessage(prompt), new UserMessage("Projet : " + projet.nom() + "\n\nDestination : "
                + (destination.isBlank() ? "non fixée" : destination) + "\n\n" + maps.resume(projetId)));
        Set<Integer> numeros = decisions.confirmees(projetId).stream().map(Decision::numero).collect(Collectors.toSet());

        Llm.Reponse r = llm.stream(messages, Llm.Effort.ELEVE, List.of(), null, false, deltas);
        consommation.enregistrer(projetId, "spec", r);
        String contenu = normaliser(r.texte());
        List<String> avertissements = verifier(contenu, numeros);
        Creee spec = specs.creer(projetId, contenu);
        return new Generee(spec.id(), spec.version(), avertissements);
    }

    private Projet projet(long id) {
        return projets.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
    }

    /**
     * Impose le gabarit : préambule, puis les 7 sections du socle dans l'ordre (« Non décidé. » si absente),
     * puis les sections optionnelles dans l'ordre où le modèle les a écrites.
     */
    public static String normaliser(String texte) {
        String preambule = texte;
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^## +(.+?)\\s*$").matcher(texte);
        List<int[]> positions = new ArrayList<>();
        List<String> titres = new ArrayList<>();
        while (m.find()) {
            positions.add(new int[] {m.start(), m.end()});
            titres.add(m.group(1));
        }
        if (!positions.isEmpty()) {
            preambule = texte.substring(0, positions.getFirst()[0]);
        }
        for (int i = 0; i < positions.size(); i++) {
            int fin = i + 1 < positions.size() ? positions.get(i + 1)[0] : texte.length();
            sections.putIfAbsent(titres.get(i), texte.substring(positions.get(i)[1], fin).strip());
        }
        StringBuilder sb = new StringBuilder(preambule.strip()).append("\n\n");
        for (String titre : SOCLE) {
            String corps = sections.remove(socle(sections, titre));
            sb.append("## ").append(titre).append("\n\n").append(corps == null || corps.isBlank() ? "Non décidé." : corps).append("\n\n");
        }
        sections.forEach((titre, corps) -> sb.append("## ").append(titre).append("\n\n").append(corps).append("\n\n"));
        return sb.toString().strip() + "\n";
    }

    /** Titre écrit par le modèle pour une section du socle : exact, ou au même numéro (« 5. Exigences »…). */
    private static String socle(Map<String, String> sections, String titre) {
        if (sections.containsKey(titre)) {
            return titre;
        }
        // « 1. Problème » mais pas « 10. Annexes » ni « 1.1 Détail ».
        Pattern numero = Pattern.compile("^" + Pattern.quote(titre.substring(0, titre.indexOf('.') + 1)) + "(\\s|$)");
        return sections.keySet().stream().filter(t -> numero.matcher(t).find()).findFirst().orElse(titre);
    }

    /** Chaque exigence E-n de la section 5 doit citer au moins une Décision D-n existante (E-21). */
    public static List<String> verifier(String contenu, Set<Integer> decisions) {
        int debut = contenu.indexOf("## " + SOCLE.get(4));
        int fin = contenu.indexOf("## " + SOCLE.get(5));
        String exigences = contenu.substring(debut, fin);
        List<String> avertissements = new ArrayList<>();
        Matcher ligne = Pattern.compile("(?m)^.*\\bE-(\\d+)\\b.*$").matcher(exigences);
        while (ligne.find()) {
            Matcher ref = Pattern.compile("\\bD-(\\d+)\\b").matcher(ligne.group());
            boolean citee = false;
            List<String> inconnues = new ArrayList<>();
            while (ref.find()) {
                if (decisions.contains(Integer.parseInt(ref.group(1)))) {
                    citee = true;
                } else {
                    inconnues.add("D-" + ref.group(1));
                }
            }
            String e = "E-" + ligne.group(1);
            if (!citee) {
                avertissements.add(e + " ne cite aucune Décision existante"
                        + (inconnues.isEmpty() ? "." : " (" + String.join(", ", inconnues) + " inconnue(s))."));
            } else if (!inconnues.isEmpty()) {
                avertissements.add(e + " cite " + String.join(", ", inconnues) + ", inconnue(s).");
            }
        }
        return avertissements;
    }
}
