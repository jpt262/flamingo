-- 0003_evidence.sql — build order §5, verbatim
CREATE TABLE evidence_refs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_kind VARCHAR(24) NOT NULL CHECK (source_kind IN
    ('edgar_filing','xbrl_fact','otc_feed','finra_directory','pacer',
     'web_archive','manual','desk_upload')),
  locator_uri TEXT NOT NULL,
  sha256 CHAR(64),
  trust_tier CHAR(2) NOT NULL CHECK (trust_tier IN ('T1','T2','T3','T4')),
  retrieved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  note TEXT
);
