-- 0005_securities_instruments.sql — build order §5, verbatim
CREATE TABLE securities (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  cusip CHAR(9) UNIQUE, isin VARCHAR(12), figi VARCHAR(12),
  symbol_current VARCHAR(12), symbol_history JSONB NOT NULL DEFAULT '[]',
  kind VARCHAR(16) NOT NULL DEFAULT 'common'
);
CREATE TABLE instruments (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  kind VARCHAR(16) NOT NULL,
  terms JSONB NOT NULL,
  extraction_status VARCHAR(24) NOT NULL DEFAULT 'auto'
    -- DEVIATION-NOTE: spec §5 says VARCHAR(12), but the spec's own CHECK list
    -- contains 'needs_confirmation' (18 chars) — physically impossible. CHECK
    -- constraint preserved verbatim; length widened. Logged in ops ledger.
    CHECK (extraction_status IN ('auto','needs_confirmation','confirmed','rejected')),
  confirmed_by TEXT,
  evidence_id BIGINT NOT NULL REFERENCES evidence_refs(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
