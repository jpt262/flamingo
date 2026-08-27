# ADR-0003: Supersession, never deletion

- Status: Accepted
- Date: 2026-08-27
- Source: build order R8; Q1 adjudication (owner) enumerating conversion sites.

## Context

market-data's `db.py` precedent uses idempotent upserts / wipe-and-reload — correct
for current-state analytics, opposite of required regime. Owner enumerated every
collision site before conversion (facts overwrite, chunk delete-and-reload,
pre-persist dedupe).

## Decision

All trunk writers are append-only. Versioned rows close with `valid_to` +
`superseded_by`; INSERT … ON CONFLICT DO NOTHING replaces both upsert and
delete-reload patterns at ingest (verified by TrunkPersistenceContractTest).
Pre-persistence dedupe is forbidden; ambiguity flags, never discards. History is
immutable once written.

## Consequences

- Every R8-colliding legacy pattern found elsewhere stays quarantined there.
- Storage grows monotonically; retention mapping (§18 human queue) governs archives, not trunk tables.
