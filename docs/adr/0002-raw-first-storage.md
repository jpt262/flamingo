# ADR-0002: Raw-first storage

- Status: Accepted
- Date: 2026-08-27
- Source: build order R1 (verbatim obligation), preserved under Java ruling.

## Context

Parsers consuming live network responses make replay impossible and audit
arguments vacuous. Evidence chains are only as good as the bytes beneath them.

## Decision

Every network response persists BEFORE any parsing: `{sourceHost}/{sha256(url)}/{fetched_at}`
under the RawStore root (`FLAMINGO_RAW_STORE`, FS adapter in Phase-0 behind an interface;
S3/MinIO Object-Lock binding arrives with archive work). `EdgarClient` enforces the
ordering internally — parse consumes only the stored copy. Same-second refetches of a
URL land as distinct files; nothing ever overwrites.

## Consequences

- Every downstream product can prove what it saw and when.
- Replay/determinism (T-21) reconstructs artifacts purely from stored raws + pinned config.
- Failed HTTP attempts are not persisted; only completed 200 bodies enter the store.
