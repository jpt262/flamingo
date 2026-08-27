# ADR-0001: Single-runtime Java

- Status: Accepted (owner-typed STACK RULING)
- Date: 2026-08-27
- Supersedes: build order v2.1 §3 (Python stack list) where it conflicts
- Lineage: supersedes this decider's own prior hybrid carve-line recommendation,
  reversed on record when Q3 showed flattened JSON satisfies the seed dictionary.

## Context

Stack was frozen Python; owner pivoted to Java mid-kickoff ("In Java, if possible.
This is finance, of course."). The feasibility analysis initially proposed a hybrid
(Python normalize service wrapping Arelle) premised on XBRL instance parsing being
unavoidable for the seed tag dictionary. That premise fell: all seed tags are
undimensioned default-context facts served directly by SEC's flattened
`companyfacts`/`companyconcept` JSON APIs.

## Decision

Single runtime: Java 17 / Spring Boot multi-module monorepo at `Documents/code/flamingo`,
module layout following order-platform conventions. No second runtime. mvnw wrapper
is a first-commit prerequisite (T-00).

## Consequences

- Estate consolidation maximized; single JVM to operate.
- Arelle becomes a contingency, not a dependency (ADR-0009).
- Rendering determinism is owned by POI XWPF + openhtmltopdf with fonts pinned in-repo
  (`assets/fonts/`) — ticket T-15c spikes this early per ruling §5.
