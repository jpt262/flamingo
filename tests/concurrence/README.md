# Concurrence fixtures (T-06)

Blind-parallel comparison inputs for the T-20 concurrence CLI and the rule
engine (T-11/T-12). Fixtures encode a synthetic WORLD — filings, facts,
document-level signals — plus two independent flag opinions:

| Field | Meaning |
|---|---|
| `state` | ground truth the engine will evaluate over (deterministic; `as_of` fixed) |
| `expected_flags` | what the rule pack v0 SHOULD emit (engine-side oracle) |
| `analyst_judgment.flags` | what a human analyst concluded (deliberately imperfect) |

T-20 computes concurrence % between engine output and `analyst_judgment`;
`issuer-delinquent-400d.json` seeds a disagreement (analyst missed
FIN-001, FIN-002 and DISC-002; engine concurrence 1/4) so the CLI's math is provable against a <100%
case, not just a perfect one.

## Format contract (v1, validated by TrunkConcurrenceFixtureTest)

- `fixture_version` = 1 · `scenario` ∈ {current, delinquent-400d, going-concern, …}
- `as_of` ISO date — every engine evaluation of these fixtures pins this instant (R4 determinism)
- `state.signals` = document-level booleans (`auditor_on_record`, `going_concern_language`) the
  future text-extraction lanes will derive from real filings; fixtures state them directly
- `expected_flags[].rule_id` ∈ pack v0 registry:
  DISC-001 (disclosure_timeliness, blocking) · DISC-002 (xbrl_completeness, warn) ·
  FIN-001 (auditor_presence, blocking) · FIN-002 (capital_sanity, warn)
- severity ∈ {blocking, warn, info}; dimensions must match the registry

New scenarios append files; the harness validates anything matching `issuer-*.json`.
