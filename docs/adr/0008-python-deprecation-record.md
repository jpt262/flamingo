# ADR-0008: Python deprecation record

- Status: Accepted
- Date: 2026-08-27
- Source: STACK RULING §1, §6.

## Context

The v2.1 stack froze Python tooling (FastAPI, procrastinate, structlog, polars,
WeasyPrint/python-docx, Arelle). Single-runtime Java replaced all of it. Deleting the
record would orphan future archaeology ("why does this repo speak Java?").

## Decision

Python components are formally deprecated in Flamingo scope — none were built in-repo,
so nothing is removed (R8 spirit applies to documentation too). Replacements:

| v2.1 Python part | Java successor |
|---|---|
| FastAPI services | Spring Boot modules |
| procrastinate jobs | Spring scheduling + outbox relay (T-13 pattern) |
| structlog redaction | logback `%replace` console pattern |
| polars transforms | core Java streams (+ polars-equivalent lib if ever justified via R5) |
| WeasyPrint / python-docx | openhtmltopdf / POI XWPF (T-15c spike) |
| Arelle | contingency only (ADR-0009) |

Verified-reusable knowledge moves forward regardless of language: SEC client contract,
canonical-tag candidate maps, endpoint/rate-limit/form-trap lessons (documented skill
references remain authoritative for domain semantics).
