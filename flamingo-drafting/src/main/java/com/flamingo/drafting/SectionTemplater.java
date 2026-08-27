package com.flamingo.drafting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic section templater (§10): renders Capitalization/Dilution/UoP
 * scaffolds from resolved fact rows + deal parameters. NO LLM involved —
 * output is 100% reproducible from inputs (R4), and every generated sentence
 * carries exact value text so the ProvenanceLinker can bind it.
 */
public class SectionTemplater {

    public record DealParams(BigDecimal offerPrice, BigDecimal sharesOffered,
                             String primaryUseOfProceeds) {}

    /** Renders candidate sentences for the Capitalization section. */
    public List<ProvenanceLinker.Sentence> capitalization(Map<String, BigDecimal> facts,
                                                          DealParams p) {
        List<ProvenanceLinker.Sentence> out = new ArrayList<>();
        BigDecimal out0 = facts.get("SharesOutstanding");
        if (out0 != null) {
            out.add(new ProvenanceLinker.Sentence(
                    "Immediately prior to this offering, there were "
                            + out0.toPlainString() + " shares of common stock outstanding.",
                    Set.of("SharesOutstanding")));
        }
        BigDecimal auth = facts.get("SharesAuthorized");
        if (auth != null && out0 != null) {
            out.add(new ProvenanceLinker.Sentence(
                    "Of " + auth.toPlainString() + " shares authorized, "
                            + out0.toPlainString() + " were outstanding.",
                    Set.of("SharesOutstanding", "SharesAuthorized")));
        }
        BigDecimal afterOffer = out0 == null || p == null
                ? null : out0.add(p.sharesOffered());
        if (afterOffer != null) {
            out.add(new ProvenanceLinker.Sentence(
                    "After giving effect to this offering, there will be "
                            + afterOffer.toPlainString() + " shares outstanding, consisting of the "
                            + out0.toPlainString() + " shares currently outstanding plus "
                            + p.sharesOffered().toPlainString() + " shares offered hereby.",
                    Set.of("SharesOutstanding")));
        }
        return out;
    }

    /** Dilution computation: per-share net tangible book value before/after. */
    public List<ProvenanceLinker.Sentence> dilution(Map<String, BigDecimal> facts, DealParams p) {
        List<ProvenanceLinker.Sentence> out = new ArrayList<>();
        BigDecimal out0 = facts.get("SharesOutstanding");
        BigDecimal cash = facts.get("Cash");
        if (out0 == null || cash == null || p == null) {
            return out;
        }
        BigDecimal ntvBefore = cash.divide(out0, 6, RoundingMode.HALF_UP);
        out.add(new ProvenanceLinker.Sentence(
                "As of the latest balance sheet date, cash and cash equivalents were $"
                        + cash.toPlainString() + ". Pro forma net tangible book value per share "
                        + "before the offering is $" + ntvBefore.toPlainString()
                        + " based on " + out0.toPlainString() + " shares outstanding.",
                Set.of("SharesOutstanding", "Cash", "NTV_BEFORE")));
        BigDecimal sharesAfter = out0.add(p.sharesOffered());
        BigDecimal proceeds = p.offerPrice().multiply(p.sharesOffered());
        BigDecimal ntvAfter = cash.add(proceeds).divide(sharesAfter, 6, RoundingMode.HALF_UP);
        out.add(new ProvenanceLinker.Sentence(
                "After the offering, net tangible book value per share is $"
                        + ntvAfter.toPlainString() + " based on " + sharesAfter.toPlainString()
                        + " shares outstanding and gross proceeds of $"
                        + proceeds.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + " from the sale of " + p.sharesOffered().toPlainString()
                        + " shares at $" + p.offerPrice().toPlainString()
                        + " per share, reflecting existing cash of $" + cash.toPlainString() + ".",
                Set.of("SharesOutstanding", "Cash", "NTV_AFTER")));
        return out;
    }
}
