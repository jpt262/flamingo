package com.flamingo.trunk.tags;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T-08 tag dictionary: canonical concepts → candidate raw tags per namespace.
 * Loaded from classpath YAML ({@code flamingo-tags/dictionary.yaml}) so adding a
 * concept is a DATA change only (build order §7; per-namespace candidates per
 * owner ruling §3: dei, us-gaap, ifrs-full).
 *
 * <p>Pure-JVM, offline, deterministic. No Spring dependency.</p>
 */
public final class TagDictionary {

    /** Namespace identifiers (taxonomy keys in companyfacts JSON). */
    public static final String DEI = "dei";
    public static final String US_GAAP = "us-gaap";
    public static final String IFRS_FULL = "ifrs-full";

    public record Concept(String canonical, String kind,
                          Map<String, List<String>> candidatesByNamespace) {}

    private final Map<String, Concept> concepts;
    private static final String RESOURCE = "/flamingo-tags/dictionary.yaml";

    private TagDictionary(Map<String, Concept> concepts) {
        this.concepts = Map.copyOf(concepts);
    }

    public static TagDictionary loadDefault() {
        try (InputStream in = TagDictionary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE);
            }
            return load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + RESOURCE, e);
        }
    }

    /** Visible for tests: load from arbitrary stream. */
    public static TagDictionary load(InputStream yaml) throws IOException {
        ObjectMapper y = new ObjectMapper(new YAMLFactory());
        JsonNode root = y.readTree(yaml);
        Map<String, Concept> out = new LinkedHashMap<>();
        root.path("concepts").fields().forEachRemaining(e -> {
            JsonNode c = e.getValue();
            Map<String, List<String>> byNs = new LinkedHashMap<>();
            c.path("candidates").fields().forEachRemaining(ns -> {
                java.util.List<String> tags = new java.util.ArrayList<>();
                ns.getValue().forEach(t -> tags.add(t.asText()));
                byNs.put(ns.getKey(), List.copyOf(tags));
            });
            out.put(e.getKey(), new Concept(e.getKey(),
                    c.path("kind").asText("instant"), Map.copyOf(byNs)));
        });
        if (out.isEmpty()) {
            throw new IllegalStateException("tag dictionary empty — refusing to start");
        }
        return new TagDictionary(out);
    }

    public Optional<Concept> concept(String canonical) {
        return Optional.ofNullable(concepts.get(canonical));
    }

    /** Candidate raw tags for a concept under one namespace, in priority order. */
    public List<String> candidatesFor(String canonical, String namespace) {
        Concept c = concepts.get(canonical);
        if (c == null) {
            return List.of();
        }
        return c.candidatesByNamespace().getOrDefault(namespace, List.of());
    }

    public java.util.Set<String> canonicalNames() {
        return concepts.keySet();
    }

    public String kindOf(String canonical) {
        Concept c = concepts.get(canonical);
        return c == null ? "instant" : c.kind();
    }
}
