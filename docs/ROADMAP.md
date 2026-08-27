# ROADMAP — what's built, what's next

Last updated: 2026-08-27 · Live at commit `2c1372c` era (post-Gate-1 wave).

## Shipped ✅

| Area | Capability |
|---|---|
| **Ingest spine** | SEC client (mandated UA, token bucket ≤5rps/≤3conc, backoff+jitter), R1 raw-first store (570+ artifacts), submissions + companyfacts + FTS + filing-index lanes |
| **Facts engine** | Canonical concepts via per-namespace candidate maps (dei/us-gaap/ifrs-full), evidence-bound rows, deterministic supersession closure, idempotent |
| **Gap rule engine** | Pack v0 as YAML data (DISC-001/002, FIN-001/002), verbatim citations, deterministic state-hash, fixture-reproduction tested |
| **Capital structure** | Layered reconstruction (cover→balance-sheet→deltas→instruments), 0.5% DISCREPANCY guard blocking packet generation |
| **Instruments** | Exhibit-snippet extraction → `needs_confirmation` human queue; adversarial-snippet refusal |
| **Restoration** | Full §9 state machine, event-sourced, drift-back auto-reopen, illegal transitions structurally impossible |
| **Concurrence** | Blind engine-vs-analyst comparison CLI + harness (75% aggregate on story data incl. seeded disagreement) |
| **Evidence chain** | Append-only sha256 manifest chain; tamper detection fails loudly through successors |
| **Golden corpus** | 200 real 424B5s / 151 issuers, resumable, index↔disk hash-verified |
| **P4 Buyer-List core** | Presence scorer with frozen decay constants (13F/Form-4/anchor), deterministic ranking, staleness banner; **no-transmission guarantee enforced as a build gate** |
| **P3 Drafter core** | Provenance linker (unbound sentences physically dropped, drop-rate telemetry), §10 compliance-mode machine (LOCKOUT refuses outbound classes), deterministic templater |
| **Platform** | 5-module Maven build, Flyway V1–V8, 39-test suite green (incl. per-run isolated DBs), operator cockpit, CI with structural gate enforcement |

## In flight 🔨

| Item | Notes |
|---|---|
| **Narration layer (P3)** | Awaiting owner approval of LLM vendor + model (R5 review). The linker/templater/binder around it are complete and vendor-agnostic — the client slots in without architecture change. |
| **Clause library content (P3)** | Ships as data flagged `counsel_review: pending`; production use requires counsel sign-off (owner-scheduled). |

## Next queue 📋

| Ticket | Scope | Gate |
|---|---|---|
| T-13 | Nightly delinquent-universe screen (scheduled job → evaluations) | open |
| T-15c | Byte-stable DOCX/PDF rendering spike (POI + openhtmltopdf, pinned fonts) — **before** renderer polish per ruling | open |
| T-15 | Packet renderer PDF+DOCX | open |
| T-16 | Portal auth/org/roles + audit log | open |
| T-17 | WORM writer (MinIO Object Lock COMPLIANCE) + chain bind | open |
| T-18–T-20 | Sign-off events, digest mailer dry-run, full concurrence harness | open |
| T-21–T-23 | Replay determinism end-to-end, metrics endpoints, hardening sweep | open |
| T-24 | Docs remainder + package headers | open |
| T-27–T-33 | P3 completion: cap-table hardening, scenario/dilution engine, DOCX/redline export, deal-sheet UI, golden-corpus regression harness | Gate 1 ✅ |
| T-35–T-37 | P4 completion: ingest-lag tables, comparables k-NN, workbook export, reproducibility harness | Gate 1 ✅ |

## Locked 🔒 (by design)

| Block | Requires |
|---|---|
| P5 Rights-Offering Rail | `GATE 2 PASSED` + counsel confirmation |
| P6a Blue-Sky / P6b PIPE Kit | `GATE 2 PASSED` |
| P7 Evidence Engine modeling | `GATE 2 PASSED` + owner-approved design |

The CI structural gate enforces these locks mechanically — their directories
cannot exist in the tree until unlocked.

## How to verify any claim here

Everything above is reproducible: `mvnw.cmd -B -ntp verify` runs the 39-test
suite (DB tests need `FLAMINGO_DB_TESTS=1` + the compose Postgres); the
[GALLERY](GALLERY.md) shows live output for every capability.
