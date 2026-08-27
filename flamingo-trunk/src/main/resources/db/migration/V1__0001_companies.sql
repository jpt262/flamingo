-- 0001_companies.sql — build order §5, verbatim
CREATE TABLE companies (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  cik VARCHAR(10) UNIQUE NOT NULL,
  entity_name TEXT NOT NULL,
  former_names JSONB NOT NULL DEFAULT '[]',
  jurisdiction TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
