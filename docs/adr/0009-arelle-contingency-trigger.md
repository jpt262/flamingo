# ADR-0009: Arelle contingency trigger

- Status: Accepted
- Date: 2026-08-27
- Source: STACK RULING §2.

## Context

Arelle is the reference XBRL implementation; owner demands it never enters casually,
and equally that we don't fear-monger our way into a hand-port when flattened JSON
suffices. Coverage, not vibes, decides.

## Decision

XBRL instance parsing stays OUT of Phase-0. Arelle re-enters ONLY IF T-05 golden-corpus
coverage measures <95% of required seed fields corpus-wide INCLUDING the FPI subset
(20-F/40-F filers, ifrs-full namespace). Crossing the trigger produces
`CONTINGENCY-ARELLE.md` — a scope memo for the owner proposing integration shape,
costs, and license posture (Apache-2). The agent NEVER integrates directly;
contingencies propose, owners dispose.

Interim mitigation already in effect: per-namespace candidate-tag maps (dei,
us-gaap, ifrs-full) live in T-08 config; FPI coverage reports numerically in T-05 DoD.
