# ADR-0012: Gate 2 opening — P5/P6a/P6b/P7 activated

- Status: Accepted (owner-typed `GATE 2 PASSED`, 2026-08-27)
- Unlocks: reserved blocks — rights rail (T-41..), blue-sky (T-38..), pipe kit
  (T-39..), evidence-engine labeling (T-40..) per §16.

## Decisions

- Four new modules admitted to the CI allowlist and the Maven reactor.
- **P5 rights rail** computes record-date/oversubscription/standby arithmetic as
  pure functions, but every surface carries `PENDING-COUNSEL` markers: production
  use requires the §18 counsel confirmation. Distribution remains outside the
  system entirely (partner BD executes; R7 wall intact).
- **P6a blue-sky**: state notice-filing matrix as YAML data (reuses the rule-pack
  pattern); filing-calendar generator over issuer states.
- **P6b pipe kit**: clause registry with category/positioning metadata; assembly
  composition UI-ready. Content remains counsel-review-gated.
- **P7 labeling**: outcome vocabulary (ADR-0007) applied to real evaluations via
  event table (V9). Modeling/training remains a separately owner-approved design —
  labels only, no predictors.
