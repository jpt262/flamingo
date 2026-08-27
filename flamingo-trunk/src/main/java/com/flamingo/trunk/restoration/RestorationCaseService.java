package com.flamingo.trunk.restoration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T-14 restoration workspace state machine (spec §9):
 * Engaged→Diagnosed→Remediation→CatchUp→CurrentInfo→ReadyFor211→Quoted→Monitored,
 * drift-back edge Monitored→CatchUp (staleness auto-reopen), terminal Abandoned.
 *
 * <p>Event-sourced (R8): transitions are INSERTED events; current status is
 * derived. Illegal transitions fail LOUDLY. Fee tier = billing metadata only
 * (spec: "fee = billing, not software concern").</p>
 */
public class RestorationCaseService {

    public static final Set<String> STATES = Set.of(
            "Engaged", "Diagnosed", "Remediation", "CatchUp", "CurrentInfo",
            "ReadyFor211", "Quoted", "Monitored", "Abandoned");

    /** Legal edges of the §9 machine. */
    private static final Map<String, Set<String>> EDGES = Map.of(
            "Engaged", Set.of("Diagnosed", "Abandoned"),
            "Diagnosed", Set.of("Remediation", "Abandoned"),
            "Remediation", Set.of("CatchUp", "Abandoned"),
            "CatchUp", Set.of("CurrentInfo"),
            "CurrentInfo", Set.of("ReadyFor211"),
            "ReadyFor211", Set.of("Quoted"),
            "Quoted", Set.of("Monitored"),
            "Monitored", Set.of("CatchUp")); // staleness drift-back (auto-reopen)

    private final JdbcTemplate jdbc;

    public RestorationCaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long createCase(long companyId, String feeTierMetadata) {
        jdbc.update("""
                INSERT INTO restoration_cases (company_id, fee_tier_metadata)
                VALUES (?, ?)
                ON CONFLICT (company_id) DO NOTHING
                """, companyId, feeTierMetadata == null ? "" : feeTierMetadata);
        return jdbc.queryForObject(
                "SELECT id FROM restoration_cases WHERE company_id = ?",
                Long.class, companyId);
    }

    /** Records one transition; illegal moves throw loudly. Returns event id. */
    public long recordTransition(long caseId, String from, String to,
                                 String actor, String note) {
        if (!STATES.contains(from) || !STATES.contains(to)) {
            throw new IllegalArgumentException("unknown state: " + from + " → " + to);
        }
        String current = currentStatus(caseId);
        if (current != null && !current.equals(from)) {
            throw new IllegalStateException(
                    "case %d is at %s, transition asserted from %s".formatted(caseId, current, from));
        }
        if (current != null && !EDGES.getOrDefault(current, Set.of()).contains(to)) {
            throw new IllegalStateException(
                    "ILLEGAL transition %s → %s for case %d (spec §9 machine)".formatted(current, to, caseId));
        }
        jdbc.update("""
                INSERT INTO restoration_case_events (case_id, from_state, to_state, actor, note)
                VALUES (?, ?, ?, ?, ?)
                """, caseId, current, to, actor, note);
        Long id = jdbc.queryForObject(
                "SELECT id FROM restoration_case_events WHERE case_id=? ORDER BY id DESC LIMIT 1",
                Long.class, caseId);
        return id;
    }

    /** Derived current status = latest event's to_state (null if never transitioned). */
    public String currentStatus(long caseId) {
        List<String> l = jdbc.query(
                "SELECT to_state FROM restoration_case_events WHERE case_id=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), caseId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Full event history (oldest first) — feeds deterministic exports. */
    public List<Map<String, Object>> eventHistory(long caseId) {
        return jdbc.queryForList("""
                SELECT from_state, to_state, actor, note, occurred_at
                FROM restoration_case_events WHERE case_id=? ORDER BY id
                """, caseId);
    }
}
