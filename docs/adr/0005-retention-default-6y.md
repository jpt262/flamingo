# ADR-0005: Retention default — today+6y pending map

- Status: Accepted
- Date: 2026-08-27
- Source: build order §8 (retention default today+6y, ADR-0005); §18 human queue.

## Context

Per-artifact-class retention mapping is owner-side human work (broker-dealer record
obligations differ by class); engineering needs *a* default now without pretending
the map exists.

## Decision

Default `retention_until = today + 6 years` on every archived artifact until the
owner-supplied per-class map lands. Mapping code reads config, not constants.
Manual overrides require org-scoped admin role.

## Consequences

- Over-retention risk accepted temporarily in exchange for never under-retaining.
- Quarterly WORM retrieval drill (<5 min requirement) validates the chain end-to-end.
