-- 0006_gap.sql — build order §5, verbatim
CREATE TABLE evaluations (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  rule_pack_version TEXT NOT NULL,
  state_hash CHAR(64) NOT NULL
);
CREATE TABLE gap_flags (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  evaluation_id BIGINT NOT NULL REFERENCES evaluations(id),
  company_id BIGINT NOT NULL REFERENCES companies(id),
  rule_id TEXT NOT NULL,
  dimension TEXT NOT NULL,
  severity VARCHAR(10) NOT NULL CHECK (severity IN ('blocking','warn','info')),
  observed JSONB NOT NULL,
  citation TEXT NOT NULL,
  remediation TEXT,
  disposition VARCHAR(12) NOT NULL DEFAULT 'open'
    CHECK (disposition IN ('open','acknowledged','resolved','suppressed')),
  evidence_ids BIGINT[] NOT NULL
);
