package com.flamingo.trunk.tags;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T-08: config-driven dictionary — data-only extension + per-namespace candidates. */
class TagDictionaryTest {

    @Test
    void defaultDictionaryLoadsAllElevenConcepts() {
        TagDictionary d = TagDictionary.loadDefault();
        assertThat(d.canonicalNames()).containsExactlyInAnyOrder(
                "SharesOutstanding", "SharesAuthorized", "SharesIssued", "PublicFloat",
                "StockholdersEquity", "Cash", "LongTermDebt", "AssetsCurrent",
                "LiabilitiesCurrent", "Revenues", "NetIncomeLoss");
    }

    @Test
    void perNamespaceCandidates_firstPresentOrder() {
        TagDictionary d = TagDictionary.loadDefault();
        assertThat(d.candidatesFor("SharesOutstanding", TagDictionary.DEI))
                .containsExactly("EntityCommonStockSharesOutstanding");
        assertThat(d.candidatesFor("Revenues", TagDictionary.US_GAAP))
                .containsExactly("Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax",
                        "SalesRevenueNet");
        assertThat(d.candidatesFor("StockholdersEquity", TagDictionary.IFRS_FULL))
                .containsExactly("Equity");
        assertThat(d.candidatesFor("PublicFloat", TagDictionary.US_GAAP)).isEmpty();
    }

    @Test
    void addingAConceptIsADataChangeOnly() throws Exception {
        String yaml = """
                concepts:
                  WombatCount:
                    kind: instant
                    candidates:
                      us-gaap: [WombatsTotal]
                """;
        TagDictionary d = TagDictionary.load(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertThat(d.concept("WombatCount")).isPresent();
        assertThat(d.kindOf("WombatCount")).isEqualTo("instant");
    }
}
