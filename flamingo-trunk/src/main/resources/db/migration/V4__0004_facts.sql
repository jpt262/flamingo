-- 0004_facts.sql — build order §5, verbatim (R8: append-only versioned rows)
CREATE TABLE facts (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  filing_id BIGINT NOT NULL REFERENCES filings(id),
  company_id BIGINT NOT NULL REFERENCES companies(id),
  taxonomy VARCHAR(16) NOT NULL,
  tag TEXT NOT NULL,
  value NUMERIC,
  unit VARCHAR(16),
  period_start DATE, period_end DATE,
  instant BOOLEAN NOT NULL DEFAULT false,
  fy SMALLINT, fp VARCHAR(2),
  xbrl_ref TEXT,
  evidence_id BIGINT NOT NULL REFERENCES evidence_refs(id),
  valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid_to TIMESTAMPTZ,
  superseded_by BIGINT REFERENCES facts(id)
);
CREATE INDEX ON facts(company_id, tag, valid_from);
