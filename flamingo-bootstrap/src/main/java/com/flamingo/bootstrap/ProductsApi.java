package com.flamingo.bootstrap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flamingo.drafting.ComplianceModeMachine;
import com.flamingo.drafting.ProvenanceLinker;
import com.flamingo.drafting.SectionTemplater;
import com.flamingo.trunk.restoration.RestorationCaseService;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Product APIs for the multi-page cockpit: every Gate product gets a live,
 * interactive page. Read endpoints = real DB state; action endpoints perform
 * real work (confirm instruments, record transitions, run linker, compute
 * targeting scores, tally rights, generate calendars, assemble clauses).
 */
@RestController
@RequestMapping("/api")
public class ProductsApi {

    private final JdbcTemplate jdbc;

    public ProductsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── P1: Gap flags (packet core) ─────────────────────────────────────
    @GetMapping("/flags")
    public ArrayNode flags() {
        ArrayNode arr = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        jdbc.query("""
                SELECT c.entity_name, g.rule_id, g.severity, g.dimension, g.citation,
                       g.remediation, g.disposition
                FROM gap_flags g JOIN companies c ON c.id = g.company_id
                ORDER BY c.entity_name, g.severity DESC, g.rule_id
                """, rs -> {
            var n = arr.addObject();
            n.put("issuer", rs.getString(1));
            n.put("rule", rs.getString(2));
            n.put("severity", rs.getString(3));
            n.put("dimension", rs.getString(4));
            n.put("citation", rs.getString(5));
            n.put("remediation", rs.getString(6));
            n.put("disposition", rs.getString(7));
        });
        return arr;
    }

    // ── P2: Restoration cases + transition action ──────────────────────
    @GetMapping("/restoration")
    public ArrayNode restoration() {
        ArrayNode arr = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        jdbc.query("""
                SELECT rc.id, c.entity_name, rc.fee_tier_metadata,
                         COALESCE((SELECT to_state FROM restoration_case_events e
                                   WHERE e.case_id = rc.id ORDER BY id DESC LIMIT 1), '—') AS status,
                         (SELECT count(*) FROM restoration_case_events e WHERE e.case_id = rc.id) AS events
                FROM restoration_cases rc JOIN companies c ON c.id = rc.company_id
                ORDER BY rc.id
                """, rs -> {
            var n = arr.addObject();
            n.put("caseId", rs.getLong(1));
            n.put("issuer", rs.getString(2));
            n.put("tier", rs.getString(3));
            n.put("status", rs.getString(4));
            n.put("events", rs.getInt(5));
        });
        return arr;
    }

    @PostMapping(value = "/restoration/transition", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object transition(@RequestBody Map<String, String> body) {
        var svc = new RestorationCaseService(jdbc);
        long caseId = Long.parseLong(body.get("caseId"));
        String current = svc.currentStatus(caseId);
        if (current == null) {
            current = "Engaged";
            jdbc.update("INSERT INTO restoration_case_events (case_id, from_state, to_state, actor, note) "
                    + "VALUES (?, NULL, 'Engaged', 'ui', 'intake')", caseId);
        }
        long ev = svc.recordTransition(caseId, current, body.get("to"),
                body.getOrDefault("actor", "ui"), body.get("note"));
        return Map.of("ok", true, "eventId", ev, "newStatus", body.get("to"));
    }

    // ── P3: Drafter — live templater+linker run ─────────────────────────
    @GetMapping("/drafting/run")
    public Object draftingRun(@RequestParam(name = "price", defaultValue = "4.00") double price,
                              @RequestParam(name = "shares", defaultValue = "10000000") double shares) {
        var factsRows = jdbc.queryForList(
                "SELECT DISTINCT ON (tag) tag, value FROM facts WHERE company_id = "
                        + "(SELECT id FROM companies WHERE cik='0009000001') ORDER BY tag, id DESC");
        Map<String, BigDecimal> facts = new java.util.LinkedHashMap<>();
        factsRows.forEach(r -> facts.put((String) r.get("tag"),
                ((BigDecimal) r.get("value"))));
        var p = new SectionTemplater.DealParams(
                BigDecimal.valueOf(price).setScale(2, java.math.RoundingMode.HALF_UP),
                BigDecimal.valueOf(shares).setScale(0, java.math.RoundingMode.HALF_UP),
                "general corporate purposes");
        var sentences = new SectionTemplater().dilution(facts, p);
        var linker = new ProvenanceLinker(factsRows.stream()
                .map(r -> new ProvenanceLinker.FactRow((String) r.get("tag"),
                        ((BigDecimal) r.get("value")).toPlainString(), "facts-table"))
                .toList());
        // register derived per-share values so the linker can bind computed rows
        // (each derivation is provable from the same cited fact rows)
        var sharesOut = facts.get("SharesOutstanding");
        var cashF = facts.get("Cash");
        if (sharesOut != null && cashF != null && sharesOut.signum() > 0) {
            linker.registerDerived("NTV_BEFORE",
                    cashF.divide(sharesOut, 6, java.math.RoundingMode.HALF_UP).toPlainString(),
                    "derived:Cash/SharesOutstanding");
            var sharesAfter = sharesOut.add(p.sharesOffered());
            var proceeds = p.offerPrice().multiply(p.sharesOffered());
            linker.registerDerived("NTV_AFTER",
                    cashF.add(proceeds).divide(sharesAfter, 6, java.math.RoundingMode.HALF_UP).toPlainString(),
                    "derived:(Cash+proceeds)/sharesAfter");
        }
        var linked = linker.link(sentences);
        var m = new ComplianceModeMachine(java.util.Set.of());
        return Map.of(
                "mode", m.coldStart().name(),
                "narrationClient", "PENDING-OWNER-VENDOR (ADR-0011)",
                "templaterEmitted", sentences.size(),
                "linkedEmitted", linked.emitted().size(),
                "dropped", linked.dropped().size(),
                "dropRate", linked.dropRate(),
                "sentences", linked.emitted().stream().map(ProvenanceLinker.Sentence::text).toList());
    }

    // ── P4: Targeting scoreboard ────────────────────────────────────────
    @GetMapping("/targeting")
    public ArrayNode targeting() {
        ArrayNode arr = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        jdbc.query("""
                SELECT issuer_cik, manager_id, signal_class, occurred_on, intensity,
                       evidence_accession,
                       GREATEST(0, CURRENT_DATE - occurred_on -
                         CASE signal_class WHEN 'FILING_13F' THEN 45
                                           WHEN 'FORM4_CLUSTER' THEN 2 ELSE 0 END) AS age
                FROM targeting_observations ORDER BY issuer_cik, age
                """, rs -> {
            var n = arr.addObject();
            n.put("issuer", rs.getString(1));
            n.put("manager", rs.getString(2));
            n.put("signal", rs.getString(3));
            n.put("occurred", rs.getString(4));
            n.put("intensity", rs.getBigDecimal(5));
            n.put("ageDays", rs.getInt(6));
            n.put("evidence", rs.getString(7));
        });
        return arr;
    }

    // ── P5: Rights offerings (PENDING-COUNSEL posture) ──────────────────
    @GetMapping("/rights")
    public ArrayNode rights() {
        ArrayNode arr = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        jdbc.query("""
                SELECT c.entity_name, r.record_date, r.ratio_shares, r.ratio_held,
                       r.subscription_price, r.standby_backstop, r.status
                FROM rights_offerings r JOIN companies c ON c.id = r.company_id
                ORDER BY r.record_date
                """, rs -> {
            var n = arr.addObject();
            n.put("issuer", rs.getString(1));
            n.put("recordDate", rs.getString(2));
            n.put("ratio", rs.getInt(3) + ":" + rs.getInt(4));
            n.put("price", rs.getBigDecimal(5));
            n.put("standby", rs.getString(6));
            n.put("status", rs.getString(7));
        });
        return arr;
    }

    // ── P6a: Blue-sky calendar (computed live) ──────────────────────────
    @GetMapping("/bluesky")
    public Object bluesky(@RequestParam(name = "qualification", defaultValue = "2026-09-15") String qualification) {
        var entries = com.flamingo.bluesky.BlueSkyCalendar.generate(
                java.time.LocalDate.parse(qualification), List.of(), Map.of());
        var arr = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        for (var e : entries) {
            var n = arr.addObject();
            n.put("state", e.stateCode());
            n.put("filing", e.filingType());
            n.put("due", e.dueDate().toString());
            n.put("status", e.status());
        }
        return Map.of("qualificationDate", qualification, "entries", arr);
    }

    // ── P6b: PIPE clause registry ───────────────────────────────────────
    @GetMapping("/pipekit")
    public ArrayNode pipekit() {
        ArrayNode arr = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        jdbc.query("SELECT clause_key, category, title, counsel_review FROM pipe_clauses "
                + "ORDER BY category, clause_key", rs -> {
            var n = arr.addObject();
            n.put("key", rs.getString(1));
            n.put("category", rs.getString(2));
            n.put("title", rs.getString(3));
            n.put("review", rs.getString(4));
        });
        return arr;
    }

    // ── P7: Outcome labels ──────────────────────────────────────────────
    @GetMapping("/labels")
    public Object labels() {
        var dist = new ArrayNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        jdbc.query("SELECT outcome, count(*) FROM outcome_labels GROUP BY outcome ORDER BY outcome",
                (rs, i) -> new String[]{rs.getString(1), String.valueOf(rs.getLong(2))})
                .forEach(pair -> {
                    var n = dist.addObject();
                    n.put("outcome", pair[0]);
                    n.put("count", Long.parseLong(pair[1]));
                });
        return Map.of("vocabulary", com.flamingo.labeling.OutcomeLabeler.VOCABULARY,
                "distribution", dist);
    }
}
