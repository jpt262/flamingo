-- 0009_gate2_products.sql — ADDITIVE migration per spec §5 (Gate 2 products)

-- P4: presence observations (13F / Form 4 / anchor evidence)
CREATE TABLE targeting_observations (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  issuer_cik VARCHAR(10) NOT NULL,
  manager_id TEXT NOT NULL,
  signal_class VARCHAR(20) NOT NULL CHECK (signal_class IN
    ('FILING_13F','FORM4_CLUSTER','ANCHOR_TAKEDOWN')),
  occurred_on DATE NOT NULL,
  intensity NUMERIC NOT NULL CHECK (intensity > 0 AND intensity <= 1),
  evidence_accession TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON targeting_observations(issuer_cik, occurred_on);

-- P5: rights offerings (computation + workflow tracking ONLY; distribution is
-- executed exclusively by partner broker-dealer — R7 wall)
CREATE TABLE rights_offerings (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  record_date DATE NOT NULL,
  ratio_shares NUMERIC NOT NULL,       -- N new shares per M held
  ratio_held NUMERIC NOT NULL,
  subscription_price NUMERIC NOT NULL,
  standby_backstop TEXT,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING-COUNSEL'
    CHECK (status IN ('PENDING-COUNSEL','STRUCTURED','OPEN','CLOSED','CANCELLED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE rights_calculations (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  offering_id BIGINT NOT NULL REFERENCES rights_offerings(id),
  holder_shares NUMERIC NOT NULL,
  entitlement NUMERIC NOT NULL,
  oversubscription_requested NUMERIC,
  computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- P6a: blue-sky state notice-filing matrix (YAML-fed data)
CREATE TABLE bluesky_filings (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  state_code VARCHAR(2) NOT NULL,
  filing_type VARCHAR(24) NOT NULL,
  due_date DATE,
  filed_date DATE,
  status VARCHAR(16) NOT NULL DEFAULT 'planned'
    CHECK (status IN ('planned','filed','accepted','deficient')),
  note TEXT,
  UNIQUE (company_id, state_code, filing_type)
);

-- P6b: PIPE clause registry (content counsel-gated)
CREATE TABLE pipe_clauses (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  clause_key TEXT UNIQUE NOT NULL,
  category VARCHAR(24) NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  counsel_review VARCHAR(16) NOT NULL DEFAULT 'pending'
    CHECK (counsel_review IN ('pending','approved','rejected')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- P7: outcome labels over gap-flag evaluations (vocabulary per ADR-0007;
-- labeling ONLY — modeling/training is a separate owner-approved design)
CREATE TABLE outcome_labels (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  company_id BIGINT NOT NULL REFERENCES companies(id),
  evaluation_id BIGINT REFERENCES evaluations(id),
  outcome VARCHAR(32) NOT NULL CHECK (outcome IN
    ('delinquency_resolved','severe_dilution','bankruptcy',
     'shell_transition_indicator','acquired')),
  observed_on DATE NOT NULL,
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON outcome_labels(company_id, outcome);
