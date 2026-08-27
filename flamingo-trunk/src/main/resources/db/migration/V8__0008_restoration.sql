-- 0008_restoration.sql — ADDITIVE migration per spec §5 (T-14)
-- Event-sourced cure workflow: state transitions are INSERTED events; current
-- status is DERIVED (latest event per case). Never updated or deleted (R8).

CREATE TABLE restoration_cases (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  fee_tier_metadata TEXT NOT NULL DEFAULT '',
  opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (company_id)
);

CREATE TABLE restoration_case_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  case_id BIGINT NOT NULL REFERENCES restoration_cases(id),
  from_state VARCHAR(24),
  to_state VARCHAR(24) NOT NULL,
  actor TEXT NOT NULL,
  note TEXT,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON restoration_case_events(case_id, id);

-- states: Engaged, Diagnosed, Remediation, CatchUp, CurrentInfo,
--         ReadyFor211, Quoted, Monitored, Abandoned
