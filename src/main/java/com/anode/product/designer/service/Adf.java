package com.anode.product.designer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown des Tickets vers Atlassian Document Format (D-10), le seul format de description accepté par l'API v3.
 * ponytail: couvre ce que produit l'app (titres, paragraphes, listes, cases à cocher, gras, code) ; un vrai parseur
 * markdown si les corps deviennent plus riches.
 */
public final class Adf {

    private static final Pattern TITRE = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern PUCE = Pattern.compile("^\\s*[-*]\\s+(\\[[ xX]\\]\\s+)?(.*)$");
    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`");

    private Adf() {}

    public static Map<String, Object> document(String markdown) {
        List<Object> contenu = new ArrayList<>();
        List<Object> puces = null;
        StringBuilder paragraphe = new StringBuilder();
        for (String ligne : (markdown == null ? "" : markdown).split("\\R")) {
            Matcher titre = TITRE.matcher(ligne);
            Matcher puce = PUCE.matcher(ligne);
            if (titre.matches() || puce.matches() || ligne.isBlank()) {
                fermer(contenu, paragraphe);
            }
            if (titre.matches()) {
                puces = null;
                contenu.add(Map.of("type", "heading", "attrs", Map.of("level", titre.group(1).length()), "content", inline(titre.group(2))));
            } else if (puce.matches()) {
                if (puces == null) {
                    puces = new ArrayList<>();
                    contenu.add(Map.of("type", "bulletList", "content", puces));
                }
                String texte = (puce.group(1) == null ? "" : puce.group(1).toLowerCase().contains("x") ? "☑ " : "☐ ") + puce.group(2);
                puces.add(Map.of("type", "listItem", "content", List.of(Map.of("type", "paragraph", "content", inline(texte)))));
            } else if (ligne.isBlank()) {
                puces = null;
            } else {
                puces = null;
                paragraphe.append(paragraphe.isEmpty() ? "" : " ").append(ligne.strip());
            }
        }
        fermer(contenu, paragraphe);
        return Map.of("type", "doc", "version", 1, "content", contenu);
    }

    private static void fermer(List<Object> contenu, StringBuilder paragraphe) {
        if (!paragraphe.isEmpty()) {
            contenu.add(Map.of("type", "paragraph", "content", inline(paragraphe.toString())));
            paragraphe.setLength(0);
        }
    }

    /** Texte avec **gras** et `code` ; ADF refuse un nœud texte vide. */
    private static List<Object> inline(String texte) {
        List<Object> noeuds = new ArrayList<>();
        Matcher m = INLINE.matcher(texte);
        int i = 0;
        while (m.find()) {
            if (m.start() > i) {
                noeuds.add(Map.of("type", "text", "text", texte.substring(i, m.start())));
            }
            String marque = m.group(1) != null ? "strong" : "code";
            noeuds.add(Map.of("type", "text", "text", m.group(1) != null ? m.group(1) : m.group(2),
                    "marks", List.of(Map.of("type", marque))));
            i = m.end();
        }
        if (i < texte.length()) {
            noeuds.add(Map.of("type", "text", "text", texte.substring(i)));
        }
        return noeuds.isEmpty() ? List.of(Map.of("type", "text", "text", " ")) : noeuds;
    }
}
