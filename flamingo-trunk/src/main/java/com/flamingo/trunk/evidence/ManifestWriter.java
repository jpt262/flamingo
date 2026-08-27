package com.flamingo.trunk.evidence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.List;

/**
 * Append-only hash-chain manifest writer over the §5 manifests table.
 *
 * <p>Mechanics derived from loom/loom/registry.py (ADR-0010 lineage):
 * each row's {@code own_hash} covers (prev_hash ‖ artifact_key ‖ artifact_sha256
 * ‖ generator_build ‖ rule_pack_version ‖ evidence ids); verification recomputes
 * the whole chain and fails loudly on any byte of drift. Rows are never updated
 * or deleted after append (R8).</p>
 *
 * <p>Evidence-id arrays are hashed through ONE canonical string form
 * ("1,2,3") regardless of Java-list vs PG-array representation — otherwise the
 * same logical chain verifies differently depending on which side serialized it.</p>
 */
public class ManifestWriter {

    public static final String GENESIS_PREV = "0".repeat(64);

    private final JdbcTemplate jdbc;

    public ManifestWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized long append(String artifactKey, String artifactSha256,
                                    List<Long> evidenceIds, String generatorBuild,
                                    String rulePackVersion) {
        // Chain tip = last row's OWN_HASH (not prev_hash) — each link covers its
        // predecessor's full commitment.
        String prev = jdbc.query(
                "SELECT own_hash FROM manifests ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : GENESIS_PREV);
        String own = hashOf(prev, artifactKey, artifactSha256, generatorBuild,
                rulePackVersion, canonIds(evidenceIds));
        jdbc.update("""
                INSERT INTO manifests (artifact_key, artifact_sha256, input_evidence_ids,
                                       generator_build, rule_pack_version, prev_hash, own_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                artifactKey, artifactSha256,
                evidenceIds.stream().mapToLong(Long::longValue).toArray(),
                generatorBuild, rulePackVersion, prev, own);
        return jdbc.queryForObject("SELECT seq FROM manifests ORDER BY seq DESC LIMIT 1", Long.class);
    }

    /** Recomputes every link; returns list of broken seq numbers (empty = sound). */
    public List<Long> verifyChain() {
        record Row(long seq, String key, String sha, String evIdsCanon, String build,
                   String packVersion, String prevHash, String ownHash) {}
        List<Row> rows = jdbc.query("SELECT seq, artifact_key, artifact_sha256, " +
                        "input_evidence_ids, generator_build, rule_pack_version, prev_hash, own_hash " +
                        "FROM manifests ORDER BY seq",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                        canonPgArray(rs.getArray(4)), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8)));

        List<Long> broken = new java.util.ArrayList<>();
        // chainHead tracks what the prior row's own_hash SHOULD be. On a healthy
        // row that equals its stored own_hash; on a tampered row we propagate the
        // RECOMPUTED value, so every successor inheriting a false commitment also
        // fails loudly (partial-forge attempts cannot hide behind later links).
        String chainHead = GENESIS_PREV;
        for (Row r : rows) {
            String recomputed = hashOf(r.prevHash(), r.key(), r.sha(),
                    r.build(), r.packVersion(), r.evIdsCanon());
            boolean ownOk = r.ownHash().equals(recomputed);
            boolean prevLinkOk = r.prevHash().equals(chainHead);
            if (!ownOk || !prevLinkOk) {
                broken.add(r.seq());
            }
            chainHead = ownOk ? r.ownHash() : recomputed;
        }
        return broken;
    }

    static String hashOf(String prevHash, String artifactKey, String artifactSha256,
                         String generatorBuild, String rulePackVersion, String evidenceIdsCanon) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prevHash.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F); // field separator
            md.update(artifactKey.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update(artifactSha256.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update((generatorBuild == null ? "" : generatorBuild).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update((rulePackVersion == null ? "" : rulePackVersion).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0x1F);
            md.update(evidenceIdsCanon.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String canonIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static String canonPgArray(java.sql.Array arr) {
        if (arr == null) {
            return "";
        }
        try {
            Object a = arr.getArray();
            if (a instanceof long[] l) {
                StringBuilder sb = new StringBuilder();
                for (long v : l) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(v);
                }
                return sb.toString();
            }
            if (a instanceof Long[] l) {
                StringBuilder sb = new StringBuilder();
                for (Long v : l) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(v);
                }
                return sb.toString();
            }
            return String.valueOf(a);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read manifest evidence-id array", e);
        }
    }
}
