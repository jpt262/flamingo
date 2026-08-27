# ADR-0004: Trust tiers

- Status: Accepted
- Date: 2026-2026-08-27
- Source: build order §5 evidence_refs DDL (T1–T4 constraint).

## Context

Court filings must never silently rank equal to press releases downstream. Provenance
without authority-grading lets weak sources masquerade as strong ones through joins.

## Decision

`evidence_refs.trust_tier CHAR(2)` ∈ {T1..T4} on every provenance row; conclusions
(facts, gap_flags.evidence_ids, manifest input_evidence_ids) must cite existing rows.
Tier assignment rules per source_kind are data-driven config (T-08 family), not
scattered conditionals.

## Consequences

- Downstream products may floor acceptable tiers per artifact class (packet claims ≥ T2, e.g.)
- Grading errors are corrected by superseding evidence rows, never editing history.
