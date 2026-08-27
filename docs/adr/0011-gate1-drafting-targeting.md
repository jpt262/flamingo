# ADR-0011: Gate 1 opening — P3 drafter + P4 buyer-list activated

- Status: Accepted (owner-typed `GATE 1 PASSED`, 2026-08-27)
- Unlocks: reserved blocks T-26..T-33 (drafter), T-34..T-37 (buyer-list) per §16
- Structural: CI dir allowlist admits `flamingo-drafting/` + `flamingo-targeting/`;
  bluesky/pipekit/labeling remain locked pending GATE 2.

## Dependencies flagged at first ticket (per §10 mandate)

1. **LLM narration vendor/model**: §3 reserves LLM entry for the owner's explicit
   vendor+model approval. The narration CLIENT is therefore absent by design; the
   linker/templater/binder around it are complete and LLM-agnostic. Pending: owner
   names vendor + model (R5 review applies to that dependency when named).
2. **Counsel review of templates/clause library**: clause content ships as DATA
   flagged `counsel_review: pending` — production use requires the §18 human-queue
   review. Owner schedules.

## Decisions

- New Maven modules (not trunk packages): §15 licensing — drafter engines are
  AGPL-dual like trunk, but the narration layer/prompts (when they arrive) are
  never-open; a module boundary keeps that partition enforceable.
- Compliance-mode machine (§10) enforced in code: LOCKOUT refuses outbound-facing
  generation classes via config allowlist; cold start = conservative (LOCKOUT-
  adjacent) defaults.
- P4 structural no-transmission guarantee is a TEST, not a policy: the targeting
  module's classpath is scanned and fails if any transport surface (HTTP client,
  socket, mail) appears (§11 architecture audit question).
