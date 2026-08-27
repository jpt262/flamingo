# FLAMINGO — Operations & Product Map

Companion to README (portfolio story) and ADRs (decisions). This document is the
operator/engineer map: what runs, what each piece does, what lands next.

## 1. Running system map

```
EDGAR (data.sec.gov / efts.sec.gov / www.sec.gov)
   │  mandated UA · token bucket ≤5rps · backoff+jitter
   ▼
flamingo-edgar  ──R1──►  .data/rawstore/{host}/{sha256(url)}/{fetched_at}.bin
   │                      (568+ artifacts; parse consumes ONLY stored bytes)
   ▼
flamingo-trunk
   ├─ ingest/     EnsureFilingsService   → filings (append-only, sha-bound)
   ├─ tags/       TagDictionary          → canonical concepts via per-namespace candidate maps
   ├─ facts/      FactExtractionService  → facts (versioned rows, valid_to/superseded_by)
   ├─ rules/      GapRuleEngine          → evaluations + gap_flags (pack v0 as YAML data)
   ├─ capstruct/  CapitalStructureReconstructor → layered share-count walk + 0.5% DISCREPANCY guard
   ├─ instruments/ InstrumentsAutoExtractor → converts/warrants → needs_confirmation queue
   ├─ restoration/ RestorationCaseService → event-sourced cure workflow (T-14)
   ├─ concurrence/ FlagSetComparison + CLI → engine-vs-analyst agreement %
   ├─ golden/     GoldenCorpusJob        → 200×424B5 hashed corpus (resumable)
   └─ evidence/   ManifestWriter         → sha256 hash chain, tamper fails loudly
   ▼
flamingo-bootstrap  ──►  cockpit (:8177, live) · task modes (smoke/golden) · Flyway V1–V8
```

## 2. Ticket ledger (§16 v2.1 as amended by owner rulings)

| Ticket | Scope | State |
|---|---|---|
| T-00 | repo init, mvnw, layout guard | ✅ merged |
| T-01 | poms, compose, CI + dir allowlist | ✅ merged |
| T-02 | migrations V1–V7 converge fresh DB | ✅ merged, PG-proved |
| T-03 | EDGAR client offline fixtures | ✅ merged (8/8) |
| T-04 | ensure_filings + LIVE demo | ✅ merged, live-verified ×3 |
| T-05 | golden corpus 200×424B5 | ✅ merged (151 issuers, hash-verified) |
| T-06 | concurrence fixtures + harness | ✅ merged (6/6) |
| T-07lite/T-08/T-11/T-12 | facts, tag dict, rule engine, pack v0 | ✅ merged (delegation died on connection errors — built in-session) |
| T-09/T-10 | capstruct guard, instruments queue | ✅ merged |
| T-14/T-20v1 (+V8 migration) | restoration SM + exports, concurrence math | ✅ merged (aggregate concurrence 75% incl. seeded disagreement) |
| T-13 | nightly delinquent-universe screen (procrastinate→Spring scheduling) | queued next |
| T-15 (+T-15c spike FIRST per ruling) | packet renderer PDF/DOCX byte-stable | queued |
| T-16 | portal auth/roles/audit | cockpit-slice done; auth queued |
| T-17 | WORM writer + Object Lock bind | queued (chain mechanics exist) |
| T-18/T-19 | signoff events, digest dry-run | queued |
| T-21/T-22/T-23 | replay determinism, metrics, hardening | queued |
| T-24 | docs remainder + Apache headers | partially (ADRs done) |
| T-26..T-37 | P3 drafter + P4 buyer-list | 🔒 GATE 1-LOCKED |
| T-38..T-41 | P6a/P6b/P7-modeling/P5 | 🔒 GATE 2-LOCKED (+counsel for P5) |

## 3. Product→ticket coverage matrix (the "is EVERYTHING included?" answer)

| Product row | Needs | Covered by |
|---|---|---|
| TRUNK | T-00..T-13 | live + in flight |
| P1 Dealer Packets | trunk + T-09..T-20 + T-22 | capstruct/instruments/rules landing now; renderer/orchestrator queued |
| P2 Restoration | T-14 | landing now |
| P3 Offering Drafter | T-26..T-33 | GATE 1 string |
| P4 Buyer-List | T-34..T-37 | GATE 1 string |
| P5 Rights Rail | T-41.. | GATE 2 + counsel |
| P6a Blue-Sky | T-38.. | GATE 2 |
| P6b PIPE Kit | T-39.. | GATE 2 |
| P7 Evidence Engine | label harvest now (ADR-0007 vocab), modeling at GATE 2 | vocabulary live |

Nothing in the portfolio is unowned: every row is either LIVE, IN-FLIGHT (queued
with a ticket), or GATE-LOCKED with a pre-planned reserved block.

## 4. Operating notes

- Builds: `mvnw.cmd -B -ntp verify` (Windows) or `./mvnw verify` (macOS/Linux).
- DB tests: `set FLAMINGO_DB_TESTS=1&&` prefix; workers use Testcontainers isolation.
- Live tasks require `--livenet=true` + `--sec-user-agent=…`; everything else is offline fixtures.
- Cockpit: `java -jar flamingo-bootstrap/target/…jar --server.port=8177` → http://localhost:8177.
- Chain verify runs at every boot; `verifyChain()` returns broken seqs if any byte drifts.
- Golden corpus resumes: re-run `--task=golden`; existing verified entries are skipped.
- Git: scoped conventional commits; main tracks origin.
