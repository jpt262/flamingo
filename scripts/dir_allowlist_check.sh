#!/usr/bin/env bash
# Structural scope enforcement (STACK RULING, Wall 1): CI fails on ANY top-level
# directory outside the §4-allowlist. Reserved late-phase service dirs (drafting,
# targeting, bluesky, pipekit, labeling) may exist ONLY after their gate opens,
# which today they have not. Lexical gates stop typos; directory presence does not.
set -euo pipefail

ALLOWED="flamingo-edgar flamingo-trunk flamingo-bootstrap flamingo-drafting flamingo-targeting packages apps infra docs scripts tests assets .data"
# GATE 1 PASSED (owner, 2026-08-27): drafting + targeting dirs admitted per §0/§16.
# bluesky/pipekit/labeling remain LOCKED until GATE 2. .data = runtime R1 raw-store
# (created on first LIVE run); NOT a product module, stays gitignored.

fail=0
for entry in */ .*/; do
  d="${entry%/}"
  case "$d" in
    "."|".."|".git"|".github"|".mvn"|"") continue ;;
  esac
  ok=0
  for a in $ALLOWED; do
    [ "$d" = "$a" ] && ok=1 && break
  done
  if [ "$ok" -eq 0 ]; then
    echo "VIOLATION: top-level directory '$d/' is outside the §4 allowlist" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "dir-allowlist: FAIL (structural scope enforced; GATE advancement is owner-typed)" >&2
  exit 1
fi
echo "dir-allowlist: OK"
