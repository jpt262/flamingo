# FLAMINGO — The Story in One Screen

*Every number below is live data — real SEC filings plus three synthetic issuers
driven through the full engine. Nothing mocked at the UI layer; the cockpit
reads Postgres directly.*

![FLAMINGO Operator Cockpit](screenshots/cockpit-overview.png)

## What you're looking at

Three columns, one machine:

### Left — Trunk State (the intake ledger)

| Metric | Value | What it means |
|---|---|---|
| `companies` | **4** | 1 real (Apple, via live EDGAR smoke) + 3 synthetic issuers driving the demo story |
| `filings` | **1,007** | 1,001 real AAPL filings ingested from `data.sec.gov` + 6 synthetic |
| `evidence_refs` | **4** | Graded provenance anchors (T1 = EDGAR primary, T2 = desk uploads) |
| `facts` | **20** | Canonicalized financial concepts (SharesOutstanding, StockholdersEquity, …) extracted from filings, every row FK-bound to an evidence ref |
| `manifests` | **0** | Hash-chain artifact log — fills when packets start rendering (T-15+) |

### Center — Recent Filings (live from Postgres)

Real EDGAR feed data mixed with story issuers. Spot the Synthetic Current Corp
10-Q (2026-07-31) and Synthetic GoingConcern Labs 10-Q (2026-05-08) sitting
among Apple's real Form 4s and 144s — same pipeline, same schema, same audit
trail regardless of data source.

### Right — Golden Corpus + Actions

- **200/200 indexed** — the deterministic regression corpus: 200 real 424B5
  prospectus supplements from 151 issuers, every document sha256-hashed and
  bound in `tests/golden/index.json`. Re-running the harvester resumes/skips —
  idempotent by design.
- **chain ✓ SOUND** pill (top left) — the evidence hash chain verifies on every
  boot. Mutate any archived byte and the chain breaks loudly at that sequence
  number *and every successor*.

## The story the data tells

Three issuers, three fates — the whole Rule 15c2-11 spectrum:

**1. Synthetic Current Corp** — the healthy control. Current filings, auditor
on record, 11/11 concepts tagged, capitalization sane (250M outstanding vs
500M authorized). Engine output: **zero flags**. Analyst agrees. This is what
a clean dealer-quotation candidate looks like.

**2. Synthetic Delinquent Holdings** — the warning shot. Last periodic filing
467 days stale. The engine fires **four flags**:

| Rule | Severity | Dimension | Why |
|---|---|---|---|
| DISC-001 | **blocking** | disclosure_timeliness | `days_since > 365` — no adequate current public information |
| DISC-002 | warn | xbrl_completeness | coverage 3/11 concepts = 0.27 < 0.60 |
| FIN-001 | **blocking** | auditor_presence | no auditor on record |
| FIN-002 | warn | capital_sanity | 1.15B outstanding > 900M authorized — *impossible* numbers |

Citations are copied **verbatim** from the rule pack — the machine never
paraphrases the law. Meanwhile the (blind) analyst only caught DISC-001 —
**25% concurrence** — which is exactly the gap the concurrence harness (T-20)
exists to measure. This issuer's capital structure would also trip the
DISCREPANCY guard: layered reconstruction computes 8M shares issued via
S-8/424B5 deltas on top of a 102M base against a stated 102M — blocking any
packet generation until a human resolves it.

**3. Synthetic GoingConcern Labs** — the redemption arc. Caught by FIN-001
(going-concern language in the opinion), walked the full restoration state
machine: `Engaged → Diagnosed → Remediation → CatchUp → CurrentInfo →
ReadyFor211 → Quoted → Monitored` — nine events, three actors, then the
money moment: **`Monitored → CatchUp` drift-back** fired by system-staleness
watch *before* the issuer went delinquent again. That edge is the
recurring-revenue engine of P2 — monitoring converts the one-time cure
engagement into a standing subscription.

## The instruments queue (human-in-the-loop by design)

The delinquent issuer's exhibits yielded four auto-extracted instruments, all
parked at `needs_confirmation` — the machine never pretends to certainty about
legal obligations:

| Kind | Terms extracted | Confidence |
|---|---|---|
| convertible_note | $4.5M principal, conv. price $1.85 | 0.80 |
| convertible_note | $1.2M principal, conv. price $0.42 | 0.72 |
| warrant | 2.5M shares @ $0.75, exp. 2031-03-03 | 0.75 |
| warrant | 600K shares @ $2.10 | 0.68 |

A human principal confirms or rejects each via the portal; confirmations
become T2-grade evidence rows. An adversarial snippet (prose about "potential
future financing" with no actual instrument) correctly yields **zero** rows —
the extractor refuses to fabricate.

## Run it yourself

```bash
git clone https://github.com/jpt262/flamingo && cd flamingo
cd infra && docker compose up -d && cd ..
mvnw.cmd -B -ntp package -DskipTests   # Windows (./mvnw on macOS/Linux)
java -jar flamingo-bootstrap/target/flamingo-bootstrap-0.1.0-SNAPSHOT.jar --server.port=8177
# → http://localhost:8177

# then populate the same story:
docker exec -i flamingo-db psql -U flamingo -d flamingo < scripts/populate_story_data.sql
```

The cockpit refreshes every 3 seconds. Boot log verifies the evidence chain
before anything serves.

## More

The **[full gallery](GALLERY.md)** adds raw-API views, the concurrence
measurement, the instruments queue, the restoration journey, and the test
wall — every capability, with live output.
