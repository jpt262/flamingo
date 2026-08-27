# ADR-0007: Outcome vocabulary (label harvest)

- Status: Accepted (vocabulary drafted; ratification T-24)
- Date: 2026-08-27
- Source: build order §14.

## Context

P7 trains later on operational outcomes attached to gap-flag dispositions. Labels
must exist from day one or history arrives unharvestable. Premature modeling is
forbidden; vocabulary is not modeling.

## Decision

Reserved outcome tags applied to gap_flag dispositions:
`delinquency_resolved` · `severe_dilution` (>50%/18mo) · `bankruptcy` ·
`shell_transition_indicator` · `acquired`. Applied at disposition-time as plain data.
Every historical evaluation retained append-only so training later sees full lineage.
Model design itself remains owner-gated post-GATE-2; nothing predicts anything tonight.

## Consequences

- Zero-cost now: vocabulary is documentation + enum strings, no code beyond dispositions.
- Tripwire: evaluating predictive quality on labels is Gate-2 territory — reject early attempts.
