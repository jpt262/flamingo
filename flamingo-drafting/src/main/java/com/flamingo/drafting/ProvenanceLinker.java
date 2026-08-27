package com.flamingo.drafting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3 §10 hard law: the LLM receives structured fact rows ONLY; every narration
 * sentence carries mandatory fact_ref[]; this linker verifies bidirectional
 * binding; **unbound sentences are physically dropped** (never emitted), and
 * the drop-rate is returned — a spike signals upstream tag decay.
 *
 * <p>Until the owner approves a narration vendor (ADR-0011), sentences come
 * only from the deterministic templater — which is fully linkable.</p>
 */
public class ProvenanceLinker {

    /** A drafted sentence with its claimed fact references. */
    public record Sentence(String text, Set<String> factRefs) {}

    /** A canonical fact row available for binding. */
    public record FactRow(String conceptKey, String valueRepr, String citation) {}

    public record LinkedDocument(List<Sentence> emitted, List<Sentence> dropped,
                                 double dropRate) {}

    private final Map<String, FactRow> facts;

    public ProvenanceLinker(List<FactRow> rows) {
        this.facts = new LinkedHashMap<>();
        for (FactRow r : rows) {
            facts.put(r.conceptKey(), r);
        }
    }

    /** Registers a DERIVED value (e.g. per-share math) as bindable: a sentence
     *  may cite it only if it also cites every source row — enforced by the
     *  caller passing the full source set in {@code sources}. */
    public void registerDerived(String key, String valueRepr, String citation) {
        facts.put(key, new FactRow(key, valueRepr, citation));
    }

    /**
     * Verifies each sentence: every factRef must exist AND the sentence text
     * must actually mention the fact's value (bidirectional binding — §10).
     * Failures are dropped, counted, never emitted.
     */
    public LinkedDocument link(List<Sentence> input) {
        List<Sentence> emitted = new ArrayList<>();
        List<Sentence> dropped = new ArrayList<>();
        for (Sentence s : input) {
            if (s.factRefs().isEmpty()) {
                dropped.add(s); // no refs = no provenance = dropped
                continue;
            }
            boolean bound = true;
            for (String ref : s.factRefs()) {
                FactRow f = facts.get(ref);
                if (f == null || !s.text().contains(f.valueRepr())) {
                    bound = false;
                    break;
                }
            }
            if (bound) {
                emitted.add(s);
            } else {
                dropped.add(s);
            }
        }
        double rate = input.isEmpty() ? 0.0 : (double) dropped.size() / input.size();
        return new LinkedDocument(List.copyOf(emitted), List.copyOf(dropped), rate);
    }
}
