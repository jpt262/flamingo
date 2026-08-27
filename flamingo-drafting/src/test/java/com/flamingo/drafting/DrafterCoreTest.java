package com.flamingo.drafting;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P3 core: linker drops unbound sentences physically; machine enforces LOCKOUT. */
class DrafterCoreTest {

    private final SectionTemplater t = new SectionTemplater();

    private List<ProvenanceLinker.FactRow> facts() {
        return List.of(
                new ProvenanceLinker.FactRow("SharesOutstanding", "250000000", "acc-1"),
                new ProvenanceLinker.FactRow("SharesAuthorized", "500000000", "acc-1"),
                new ProvenanceLinker.FactRow("Cash", "42500000", "acc-2"));
    }

    @Test
    void templaterSentences_allBind() {
        var doc = new ProvenanceLinker(facts()).link(
                t.capitalization(Map.of(
                                "SharesOutstanding", new BigDecimal("250000000"),
                                "SharesAuthorized", new BigDecimal("500000000")),
                        new SectionTemplater.DealParams(
                                new BigDecimal("4.00"), new BigDecimal("10000000"), null)));
        assertThat(doc.dropped()).isEmpty();
        assertThat(doc.dropRate()).isZero();
        assertThat(doc.emitted()).hasSize(3);
        assertThat(doc.emitted().get(0).text()).contains("250000000");
    }

    @Test
    void unboundSentences_physicallyDropped_dropRateEmitted() {
        var linker = new ProvenanceLinker(facts());
        var doc = linker.link(List.of(
                // bound:
                new ProvenanceLinker.Sentence("There were 250000000 shares outstanding.",
                        Set.of("SharesOutstanding")),
                // references a concept that doesn't exist:
                new ProvenanceLinker.Sentence("Wombat holdings were 42.", Set.of("WombatCount")),
                // value not actually present in text (bidirectional check fails):
                new ProvenanceLinker.Sentence("Nothing to see here.", Set.of("SharesOutstanding")),
                // no refs at all:
                new ProvenanceLinker.Sentence("Generic fluff.", Set.of())));
        assertThat(doc.emitted()).hasSize(1);
        assertThat(doc.dropped()).hasSize(3);
        assertThat(doc.dropRate()).isEqualTo(0.75);
    }

    @Test
    void complianceMachine_lockoutRefusesOutboundClasses() {
        var m = new ComplianceModeMachine(Set.of()); // nothing allowed when locked
        assertThat(m.mayGenerate(ComplianceModeMachine.Mode.DRAFTING,
                ComplianceModeMachine.GenerationClass.NARRATIVE_DRAFT)).isTrue();
        assertThat(m.mayGenerate(ComplianceModeMachine.Mode.LOCKOUT,
                ComplianceModeMachine.GenerationClass.NARRATIVE_DRAFT)).isFalse();
        // cited fact tables always allowed — pure data, not outbound
        assertThat(m.mayGenerate(ComplianceModeMachine.Mode.LOCKOUT,
                ComplianceModeMachine.GenerationClass.CITED_FACT_TABLE)).isTrue();
    }

    @Test
    void complianceMachine_legalPath_andIllegalThrows() {
        var m = new ComplianceModeMachine(Set.of());
        var mode = m.coldStart(); // DRAFTING
        mode = m.transition(mode, new ComplianceModeMachine.RegistrationDetected("0000900001-26-000042"));
        assertThat(mode).isEqualTo(ComplianceModeMachine.Mode.RUNWAY); // §10 auto-trigger
        mode = m.transition(mode, new ComplianceModeMachine.Advance(ComplianceModeMachine.Mode.LOCKOUT));
        mode = m.transition(mode, new ComplianceModeMachine.Advance(ComplianceModeMachine.Mode.PRICED));
        mode = m.transition(mode, new ComplianceModeMachine.Advance(ComplianceModeMachine.Mode.ALLOCATED_TO_CLOSE));
        mode = m.transition(mode, new ComplianceModeMachine.Reset());
        assertThat(mode).isEqualTo(ComplianceModeMachine.Mode.IDLE);

        assertThatThrownBy(() -> m.transition(Mode_IDLE(),
                new ComplianceModeMachine.Advance(ComplianceModeMachine.Mode.PRICED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ILLEGAL");
    }

    private static ComplianceModeMachine.Mode Mode_IDLE() {
        return ComplianceModeMachine.Mode.IDLE;
    }
}
