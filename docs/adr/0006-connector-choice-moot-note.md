# ADR-0006: Connector thinness & dual choice — moot note

- Status: Moot-note (recorded, closed)
- Date: 2026-08-27
- Source: build order T-24 ("connector-thin-dual-choice-at-wk4").

## Context

v2.1 scheduled a week-4 decision between connector architectures. STACK RULING §1–2
removed the fork: single-runtime Java, normalization native over flattened JSON,
no second service boundary exists to be thin-or-fat across.

## Decision

No dual-choice decision occurs. The EDGAR client (flamingo-edgar) is the single
connector; "thin" survives only as its discipline: no domain logic inside the client —
parsing/normalizing lives trunk-side over stored raws.
