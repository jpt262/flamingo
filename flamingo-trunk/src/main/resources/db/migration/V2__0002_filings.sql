-- 0002_filings.sql — build order §5, verbatim
CREATE TABLE filings (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  accession VARCHAR(24) UNIQUE NOT NULL,
  form_type VARCHAR(16) NOT NULL,
  filed_at TIMESTAMPTZ NOT NULL,
  period_of_report DATE,
  raw_object_key TEXT NOT NULL,
  raw_sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON filings(company_id, filed_at DESC);
CREATE INDEX ON filings(form_type, filed_at DESC);
