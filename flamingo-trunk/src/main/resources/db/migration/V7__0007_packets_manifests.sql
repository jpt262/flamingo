-- 0007_packets_manifests.sql — build order §5, verbatim
CREATE TABLE packet_runs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  org_id BIGINT NOT NULL,
  symbols JSONB NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status VARCHAR(12) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','running','done','failed','blocked'))
);
CREATE TABLE archives (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id BIGINT REFERENCES packet_runs(id),
  org_id BIGINT NOT NULL,
  symbol VARCHAR(12),
  artifact_kind VARCHAR(16) NOT NULL,
  object_key TEXT NOT NULL,
  object_sha256 CHAR(64) NOT NULL,
  retention_until DATE NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE manifests (
  seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  artifact_key TEXT NOT NULL,
  artifact_sha256 CHAR(64) NOT NULL,
  input_evidence_ids BIGINT[] NOT NULL,
  generator_build TEXT NOT NULL,
  rule_pack_version TEXT,
  prev_hash CHAR(64) NOT NULL,
  own_hash CHAR(64) NOT NULL
);
