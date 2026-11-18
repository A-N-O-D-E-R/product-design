package com.anode.productdesigner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.yaml.snakeyaml.Yaml;

/** Fichiers classpath:methodes/*.md : frontmatter YAML + prompt système. Chargés une fois au démarrage. */
@Component
public class Methodes {

    public record Methode(String nom, List<String> outputs, String prompt) {}

    private final Map<String, Methode> parNom = new HashMap<>();

    Methodes() throws IOException {
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:methodes/*.md")) {
            Methode m = parse(r.getContentAsString(StandardCharsets.UTF_8));
            parNom.put(m.nom(), m);
        }
    }

    static Methode parse(String fichier) {
        String[] parts = fichier.split("(?m)^---\\s*$", 3);
        if (parts.length < 3 || !parts[0].isBlank()) {
            throw new IllegalStateException("Méthode sans frontmatter YAML");
        }
        Map<String, Object> meta = new Yaml().load(parts[1]);
        @SuppressWarnings("unchecked")
        List<String> outputs = (List<String>) meta.getOrDefault("outputs", List.of());
        // Les commentaires HTML (notice de licence) ne partent pas au LLM.
        String prompt = parts[2].replaceAll("(?s)<!--.*?-->", "").strip();
        return new Methode((String) meta.get("nom"), outputs, prompt);
    }

    public Methode get(String nom) {
        Methode m = parNom.get(nom);
        if (m == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Méthode inconnue : " + nom);
        }
        return m;
    }
}
