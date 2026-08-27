-- FLAMINGO story population (idempotent — re-run = no-op)
-- Tells the full product story in the cockpit: three synthetic issuers spanning
-- the healthy → delinquent → going-concern spectrum, engine evaluations with
-- verbatim citations, instruments awaiting human confirmation, restoration
-- journeys mid-flight. Real AAPL ingest data remains untouched alongside.

-- ── issuers ─────────────────────────────────────────────────────────────
INSERT INTO companies (cik, entity_name) VALUES
  ('0009000001', 'Synthetic Current Corp'),
  ('0009000002', 'Synthetic Delinquent Holdings'),
  ('0009000003', 'Synthetic GoingConcern Labs')
ON CONFLICT (cik) DO NOTHING;

-- ── evidence snapshots (T1/T2 graded) ──────────────────────────────────
INSERT INTO evidence_refs (source_kind, locator_uri, trust_tier, note)
SELECT 'edgar_filing', 'story://submissions/0009000001', 'T1', 'synthetic story data'
WHERE NOT EXISTS (SELECT 1 FROM evidence_refs WHERE locator_uri = 'story://submissions/0009000001');
INSERT INTO evidence_refs (source_kind, locator_uri, trust_tier, note)
SELECT 'edgar_filing', 'story://submissions/0009000002', 'T1', 'synthetic story data'
WHERE NOT EXISTS (SELECT 1 FROM evidence_refs WHERE locator_uri = 'story://submissions/0009000002');
INSERT INTO evidence_refs (source_kind, locator_uri, trust_tier, note)
SELECT 'edgar_filing', 'story://submissions/0009000003', 'T1', 'synthetic story data'
WHERE NOT EXISTS (SELECT 1 FROM evidence_refs WHERE locator_uri = 'story://submissions/0009000003');
INSERT INTO evidence_refs (source_kind, locator_uri, trust_tier, note)
SELECT 'desk_upload', 'story://exhibits/0009000002', 'T2', 'synthetic exhibit snippets'
WHERE NOT EXISTS (SELECT 1 FROM evidence_refs WHERE locator_uri = 'story://exhibits/0009000002');

-- ── filings ─────────────────────────────────────────────────────────────
INSERT INTO filings (company_id, accession, form_type, filed_at, period_of_report, raw_object_key, raw_sha256)
SELECT id, '0000900001-26-000001', '10-K', '2026-03-15', '2025-12-31', 'story://0000900001-26-000001', repeat('a', 64)
FROM companies WHERE cik = '0009000001'
ON CONFLICT (accession) DO NOTHING;
INSERT INTO filings (company_id, accession, form_type, filed_at, period_of_report, raw_object_key, raw_sha256)
SELECT id, '0000900001-26-000002', '10-Q', '2026-08-01', '2026-06-30', 'story://0000900001-26-000002', repeat('a', 64)
FROM companies WHERE cik = '0009000001'
ON CONFLICT (accession) DO NOTHING;
INSERT INTO filings (company_id, accession, form_type, filed_at, period_of_report, raw_object_key, raw_sha256)
SELECT id, '0000900002-25-000007', '10-K', '2025-05-01', '2024-12-31', 'story://0000900002-25-000007', repeat('b', 64)
FROM companies WHERE cik = '0009000002'
ON CONFLICT (accession) DO NOTHING;
INSERT INTO filings (company_id, accession, form_type, filed_at, period_of_report, raw_object_key, raw_sha256)
SELECT id, '0000900002-25-000019', '8-K', '2025-11-12', NULL, 'story://0000900002-25-000019', repeat('b', 64)
FROM companies WHERE cik = '0009000002'
ON CONFLICT (accession) DO NOTHING;
INSERT INTO filings (company_id, accession, form_type, filed_at, period_of_report, raw_object_key, raw_sha256)
SELECT id, '0000900003-26-000004', '10-K', '2026-03-02', '2025-12-31', 'story://0000900003-26-000004', repeat('c', 64)
FROM companies WHERE cik = '0009000003'
ON CONFLICT (accession) DO NOTHING;
INSERT INTO filings (company_id, accession, form_type, filed_at, period_of_report, raw_object_key, raw_sha256)
SELECT id, '0000900003-26-000011', '10-Q', '2026-05-09', '2026-03-31', 'story://0000900003-26-000011', repeat('c', 64)
FROM companies WHERE cik = '0009000003'
ON CONFLICT (accession) DO NOTHING;

-- ── facts (canonical concepts, evidence-bound) ──────────────────────────
INSERT INTO facts (filing_id, company_id, taxonomy, tag, value, unit, period_end, instant, fy, fp, xbrl_ref, evidence_id)
SELECT f.id, f.company_id, 'us-gaap', v.tag, v.val, 'shares', '2025-12-31', true, 2025, 'FY', '0000900001-26-000001', e.id
FROM companies c
JOIN filings f ON f.company_id = c.id AND f.accession = '0000900001-26-000001'
JOIN evidence_refs e ON e.locator_uri = 'story://submissions/0009000001'
CROSS JOIN (VALUES
  ('SharesOutstanding', 250000000),
  ('SharesAuthorized', 500000000),
  ('SharesIssued', 240000000)
) AS v(tag, val)
WHERE c.cik = '0009000001'
  AND NOT EXISTS (SELECT 1 FROM facts x WHERE x.company_id = f.company_id AND x.xbrl_ref = '0000900001-26-000001' AND x.tag = v.tag);

INSERT INTO facts (filing_id, company_id, taxonomy, tag, value, unit, period_end, instant, fy, fp, xbrl_ref, evidence_id)
SELECT f.id, f.company_id, 'us-gaap', v.tag, v.val, 'USD', '2025-12-31', true, 2025, 'FY', '0000900001-26-000001', e.id
FROM companies c
JOIN filings f ON f.company_id = c.id AND f.accession = '0000900001-26-000001'
JOIN evidence_refs e ON e.locator_uri = 'story://submissions/0009000001'
CROSS JOIN (VALUES
  ('StockholdersEquity', 88000000),
  ('Cash', 42500000),
  ('LongTermDebt', 5000000),
  ('AssetsCurrent', 61000000),
  ('LiabilitiesCurrent', 12300000),
  ('PublicFloat', 310000000)
) AS v(tag, val)
WHERE c.cik = '0009000001'
  AND NOT EXISTS (SELECT 1 FROM facts x WHERE x.company_id = f.company_id AND x.xbrl_ref = '0000900001-26-000001' AND x.tag = v.tag);

INSERT INTO facts (filing_id, company_id, taxonomy, tag, value, unit, period_start, period_end, instant, fy, fp, xbrl_ref, evidence_id)
SELECT f.id, f.company_id, 'us-gaap', v.tag, v.val, 'USD', '2025-01-01', '2025-12-31', false, 2025, 'FY', '0000900001-26-000001', e.id
FROM companies c
JOIN filings f ON f.company_id = c.id AND f.accession = '0000900001-26-000001'
JOIN evidence_refs e ON e.locator_uri = 'story://submissions/0009000001'
CROSS JOIN (VALUES
  ('Revenues', 48000000),
  ('NetIncomeLoss', 3100000)
) AS v(tag, val)
WHERE c.cik = '0009000001'
  AND NOT EXISTS (SELECT 1 FROM facts x WHERE x.company_id = f.company_id AND x.xbrl_ref = '0000900001-26-000001' AND x.tag = v.tag);

-- delinquent: impossible capitalization (1.15B outstanding vs 900M authorized)
INSERT INTO facts (filing_id, company_id, taxonomy, tag, value, unit, period_end, instant, fy, fp, xbrl_ref, evidence_id)
SELECT f.id, f.company_id, v.tax, v.tag, v.val, v.unit, '2024-12-31', true, 2024, 'FY', '0000900002-25-000007', e.id
FROM companies c
JOIN filings f ON f.company_id = c.id AND f.accession = '0000900002-25-000007'
JOIN evidence_refs e ON e.locator_uri = 'story://submissions/0009000002'
CROSS JOIN (VALUES
  ('us-gaap', 'SharesOutstanding', 1150000000::numeric, 'shares'),
  ('us-gaap', 'SharesAuthorized', 900000000::numeric, 'shares'),
  ('us-gaap', 'StockholdersEquity', -3200000::numeric, 'USD')
) AS v(tax, tag, val, unit)
WHERE c.cik = '0009000002'
  AND NOT EXISTS (SELECT 1 FROM facts x WHERE x.company_id = f.company_id AND x.xbrl_ref = '0000900002-25-000007' AND x.tag = v.tag);

-- going-concern: thin equity
INSERT INTO facts (filing_id, company_id, taxonomy, tag, value, unit, period_end, instant, fy, fp, xbrl_ref, evidence_id)
SELECT f.id, f.company_id, 'us-gaap', v.tag, v.val, v.unit, '2025-12-31', true, 2025, 'FY', '0000900003-26-000004', e.id
FROM companies c
JOIN filings f ON f.company_id = c.id AND f.accession = '0000900003-26-000004'
JOIN evidence_refs e ON e.locator_uri = 'story://submissions/0009000003'
CROSS JOIN (VALUES
  ('SharesOutstanding', 45000000::numeric, 'shares'),
  ('SharesAuthorized', 100000000::numeric, 'shares'),
  ('StockholdersEquity', 1800000::numeric, 'USD'),
  ('Cash', 640000::numeric, 'USD'),
  ('AssetsCurrent', 2100000::numeric, 'USD'),
  ('LiabilitiesCurrent', 3400000::numeric, 'USD')
) AS v(tag, val, unit)
WHERE c.cik = '0009000003'
  AND NOT EXISTS (SELECT 1 FROM facts x WHERE x.company_id = f.company_id AND x.xbrl_ref = '0000900003-26-000004' AND x.tag = v.tag);

-- ── evaluations + gap flags (citations verbatim from pack v0) ───────────
-- current issuer: clean evaluation, no flags
INSERT INTO evaluations (company_id, rule_pack_version, state_hash)
SELECT id, 'v0', repeat('1', 64) FROM companies WHERE cik = '0009000001'
  AND NOT EXISTS (SELECT 1 FROM evaluations e JOIN companies c ON c.id = e.company_id
                  WHERE c.cik = '0009000001' AND e.state_hash = repeat('1', 64));

-- delinquent: 4 flags (DISC-001, DISC-002, FIN-001, FIN-002)
INSERT INTO evaluations (company_id, rule_pack_version, state_hash)
SELECT id, 'v0', repeat('2', 64) FROM companies WHERE cik = '0009000002'
  AND NOT EXISTS (SELECT 1 FROM evaluations e JOIN companies c ON c.id = e.company_id
                  WHERE c.cik = '0009000002' AND e.state_hash = repeat('2', 64));
INSERT INTO gap_flags (evaluation_id, company_id, rule_id, dimension, severity, observed, citation, remediation, evidence_ids)
SELECT e.id, e.company_id, v.rule_id, v.dim, v.sev, v.obs::jsonb, v.cit, v.rem, ARRAY[]::BIGINT[]
FROM companies c
JOIN evaluations e ON e.company_id = c.id AND e.state_hash = repeat('2', 64)
CROSS JOIN (VALUES
  ('DISC-001', 'disclosure_timeliness', 'blocking',
   '{"days_since": 467, "form": "latest periodic filing", "expr": "days_since_last_form([''10-K'',''10-Q'',''20-F'',''40-F'']) > 365"}'::text,
   'Exchange Act Rule 15c2-11(a): adequate current public information',
   'File outstanding periodic reports; re-engage PCAOB auditor.'),
  ('DISC-002', 'xbrl_completeness', 'warn',
   '{"coverage": 0.273, "present": 3, "total": 11, "expr": "xbrl_coverage_ratio < 0.60"}'::text,
   'Data-quality indicator',
   'Review tagging coverage in recent filings.'),
  ('FIN-001', 'auditor_presence', 'blocking',
   '{"auditor_on_record": false, "going_concern_language": false, "expr": "no_auditor_on_record OR going_concern_language_present"}'::text,
   '15c2-11(a)(4)-(6)-adjacent diligence factor',
   'Confirm auditor of record; capture opinion language.'),
  ('FIN-002', 'capital_sanity', 'warn',
   '{"shares_outstanding": "1150000000", "authorized_shares": "900000000", "expr": "shares_outstanding > authorized_shares"}'::text,
   'Impossible capitalization; stale/conflicting data',
   'Resolve conflicting share counts; check DISCREPANCY banner.')
) AS v(rule_id, dim, sev, obs, cit, rem)
WHERE c.cik = '0009000002'
  AND NOT EXISTS (SELECT 1 FROM gap_flags g WHERE g.evaluation_id = e.id AND g.rule_id = v.rule_id);

-- going-concern: FIN-001 only
INSERT INTO evaluations (company_id, rule_pack_version, state_hash)
SELECT id, 'v0', repeat('3', 64) FROM companies WHERE cik = '0009000003'
  AND NOT EXISTS (SELECT 1 FROM evaluations e JOIN companies c ON c.id = e.company_id
                  WHERE c.cik = '0009000003' AND e.state_hash = repeat('3', 64));
INSERT INTO gap_flags (evaluation_id, company_id, rule_id, dimension, severity, observed, citation, remediation, evidence_ids)
SELECT e.id, e.company_id, 'FIN-001', 'auditor_presence', 'blocking',
       '{"auditor_on_record": true, "going_concern_language": true, "expr": "no_auditor_on_record OR going_concern_language_present"}'::jsonb,
       '15c2-11(a)(4)-(6)-adjacent diligence factor',
       'Confirm auditor of record; capture opinion language.',
       ARRAY[]::BIGINT[]
FROM companies c
JOIN evaluations e ON e.company_id = c.id AND e.state_hash = repeat('3', 64)
WHERE c.cik = '0009000003'
  AND NOT EXISTS (SELECT 1 FROM gap_flags g WHERE g.evaluation_id = e.id AND g.rule_id = 'FIN-001');

-- ── instruments awaiting human confirmation (delinquent issuer) ─────────
INSERT INTO instruments (company_id, kind, terms, extraction_status, evidence_id)
SELECT c.id, v.kind, v.terms::jsonb, 'needs_confirmation', e.id
FROM companies c
JOIN evidence_refs e ON e.locator_uri = 'story://exhibits/0009000002'
CROSS JOIN (VALUES
  ('convertible_note', '{"principal_amount": 4500000, "conversion_price": 1.85, "confidence": 0.8}'::text),
  ('convertible_note', '{"principal_amount": 1200000, "conversion_price": 0.42, "confidence": 0.72}'::text),
  ('warrant', '{"shares": 2500000, "exercise_price": 0.75, "expiration": "March 3, 2031", "confidence": 0.75}'::text),
  ('warrant', '{"shares": 600000, "exercise_price": 2.10, "confidence": 0.68}'::text)
) AS v(kind, terms)
WHERE c.cik = '0009000002'
  AND NOT EXISTS (SELECT 1 FROM instruments i WHERE i.company_id = c.id);

-- ── restoration journeys ────────────────────────────────────────────────
-- delinquent issuer: mid-remediation journey (currently at CatchUp)
INSERT INTO restoration_cases (company_id, fee_tier_metadata)
SELECT id, 'tier:standard' FROM companies WHERE cik = '0009000002'
ON CONFLICT (company_id) DO NOTHING;
INSERT INTO restoration_case_events (case_id, from_state, to_state, actor, note)
SELECT rc.id, v.frm, v.to_st, v.actor, v.note
FROM companies c
JOIN restoration_cases rc ON rc.company_id = c.id
CROSS JOIN (VALUES
  (NULL, 'Engaged', 'analyst-1', 'trapped-issuer counsel intro'),
  ('Engaged', 'Diagnosed', 'analyst-1', 'gap report priced: 4 blocking/warn flags'),
  ('Diagnosed', 'Remediation', 'analyst-1', 'remediation plan approved by counsel'),
  ('Remediation', 'CatchUp', 'analyst-2', 'filing catch-up engagements signed')
) AS v(frm, to_st, actor, note)
WHERE c.cik = '0009000002'
  AND NOT EXISTS (SELECT 1 FROM restoration_case_events ev WHERE ev.case_id = rc.id
                    AND ev.to_state = v.to_st AND COALESCE(ev.note,'') = COALESCE(v.note,''));

-- going-concern issuer: fully monitored, with one drift-back (the recurring-revenue story)
INSERT INTO restoration_cases (company_id, fee_tier_metadata)
SELECT id, 'tier:monitor-only' FROM companies WHERE cik = '0009000003'
ON CONFLICT (company_id) DO NOTHING;
INSERT INTO restoration_case_events (case_id, from_state, to_state, actor, note)
SELECT rc.id, v.frm, v.to_st, v.actor, v.note
FROM companies c
JOIN restoration_cases rc ON rc.company_id = c.id
CROSS JOIN (VALUES
  (NULL, 'Engaged', 'analyst-1', 'FIN-001 going-concern flag intake'),
  ('Engaged', 'Diagnosed', 'analyst-1', 'auditor re-engaged; opinion captured'),
  ('Diagnosed', 'Remediation', 'analyst-1', NULL),
  ('Remediation', 'CatchUp', 'analyst-2', NULL),
  ('CatchUp', 'CurrentInfo', 'analyst-2', 'all delinquencies cured'),
  ('CurrentInfo', 'ReadyFor211', 'principal-1', 'exhibits assembled; TA verified'),
  ('ReadyFor211', 'Quoted', 'principal-1', 'FINRA 6432 symbol process cleared'),
  ('Quoted', 'Monitored', 'principal-1', 'trunk watch enrolled'),
  ('Monitored', 'CatchUp', 'system-staleness', 'DRIFT-BACK: filing staleness breach detected pre-delinquency')
) AS v(frm, to_st, actor, note)
WHERE c.cik = '0009000003'
  AND NOT EXISTS (SELECT 1 FROM restoration_case_events ev WHERE ev.case_id = rc.id
                    AND ev.to_state = v.to_st AND COALESCE(ev.note,'') = COALESCE(v.note,''));

-- current issuer: healthy, freshly engaged for monitoring upsell
INSERT INTO restoration_cases (company_id, fee_tier_metadata)
SELECT id, 'tier:monitor-only' FROM companies WHERE cik = '0009000001'
ON CONFLICT (company_id) DO NOTHING;
INSERT INTO restoration_case_events (case_id, from_state, to_state, actor, note)
SELECT rc.id, NULL, 'Engaged', 'analyst-1', 'clean issuer — monitoring subscription pitch'
FROM companies c
JOIN restoration_cases rc ON rc.company_id = c.id
WHERE c.cik = '0009000001'
  AND NOT EXISTS (SELECT 1 FROM restoration_case_events ev WHERE ev.case_id = rc.id);
