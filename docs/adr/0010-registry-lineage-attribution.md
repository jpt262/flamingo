# ADR-0010: Registry lineage attribution

- Status: Accepted
- Date: 2026-08-27
- Source: STACK RULING §4.

## Context

Flamingo's manifests/hash-chain mechanics derive from `loom/loom/registry.py`
(content-addressed immutable versions, prev→own hash chain, genesis row,
verify-chain failing loudly; proven 19-green in its own suite). Attribution keeps
lineage honest and lets loom evolve without silent divergence.

## Decision

Mechanism provenance: LOOM registry (MIT-family household codebase,
`Documents/code/loom`, commit `cdd7177` era) → ported to Java as
`com.flamingo.trunk.evidence.ManifestWriter`. Differences by design:
field-separator-delimited canonical byte encoding (0x1F), Postgres-backed table per
build order §5, generator_build + rule_pack_version folded into each link. Binding
semantics (evidence_refs FKs, trust tiers, Object Lock interplay) are hand-built
Flamingo code — net-new, not attributed (R3 applies).

Divergence protocol: changes to hashing semantics require updating this ADR and
note-taking which side changed first.
