// HTTP wrappers for every AML service endpoint.
// Each function records a per-service Trend, runs k6 checks, and returns
// the parsed response body (or null on failure) so callers can branch on
// alerted / caseId without parsing JSON twice.

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

// ── Per-service latency trends (exported so load-test files can add thresholds) ──
export const kycDuration  = new Trend('kyc_request_duration',  true);
export const txnDuration  = new Trend('txn_request_duration',  true);
export const caseDuration = new Trend('case_request_duration', true);

// ── Business-level counters ───────────────────────────────────────────────────
export const alertsTotal = new Counter('aml_alerts_total');
export const casesOpened = new Counter('aml_cases_opened');
export const errorRate   = new Rate('aml_error_rate');

// ── Service base URLs (override via k6 -e flags) ─────────────────────────────
const KYC_URL  = __ENV.KYC_URL  || 'http://localhost:8082';
const TXN_URL  = __ENV.TXN_URL  || 'http://localhost:8081';
const CASE_URL = __ENV.CASE_URL || 'http://localhost:8080';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function post(url, body, tags) {
  return http.post(url, JSON.stringify(body), { headers: JSON_HEADERS, tags });
}

function safeJson(res) {
  try { return JSON.parse(res.body); } catch (_) { return null; }
}

// ── customer-kyc endpoints ────────────────────────────────────────────────────

export function onboardCustomer(customerData) {
  const res = post(
    `${KYC_URL}/api/v1/customers`,
    customerData,
    { service: 'kyc', operation: 'onboard' }
  );
  kycDuration.add(res.timings.duration);
  const ok = check(res, {
    'kyc onboard → 201':    r => r.status === 201,
    'kyc onboard → has id': r => { const b = safeJson(r); return b && b.id; },
  });
  errorRate.add(!ok);
  if (!ok) return null;
  return safeJson(res).id;
}

export function verifyCustomer(customerId) {
  const res = post(
    `${KYC_URL}/api/v1/customers/${customerId}/verify`,
    { verifiedBy: 'k6-load-test' },
    { service: 'kyc', operation: 'verify' }
  );
  kycDuration.add(res.timings.duration);
  const ok = check(res, { 'kyc verify → 204': r => r.status === 204 });
  errorRate.add(!ok);
  return ok;
}

export function updateRisk(customerId, tier) {
  const res = post(
    `${KYC_URL}/api/v1/customers/${customerId}/risk`,
    { newTier: tier, politicallyExposed: false, sanctioned: false, reason: 'k6-risk-update' },
    { service: 'kyc', operation: 'update-risk' }
  );
  kycDuration.add(res.timings.duration);
  const ok = check(res, { 'kyc risk-update → 204': r => r.status === 204 });
  errorRate.add(!ok);
  return ok;
}

// ── transaction-monitoring endpoint ──────────────────────────────────────────

export function evaluateTransaction(txnData) {
  const res = post(
    `${TXN_URL}/api/v1/transactions/evaluate`,
    txnData,
    { service: 'txn', operation: 'evaluate' }
  );
  txnDuration.add(res.timings.duration);
  const ok = check(res, {
    'txn evaluate → 200|201': r => r.status === 200 || r.status === 201,
  });
  errorRate.add(!ok);
  if (!ok) return null;
  const body = safeJson(res);
  if (body && body.alerted) alertsTotal.add(1);
  return body;
}

// ── case-management endpoints ─────────────────────────────────────────────────

export function openCase(alertId, customerId, riskScore) {
  const res = post(
    `${CASE_URL}/api/v1/cases`,
    { alertId, customerId, riskScore },
    { service: 'case', operation: 'open' }
  );
  caseDuration.add(res.timings.duration);
  const ok = check(res, {
    'case open → 201':    r => r.status === 201,
    'case open → has id': r => { const b = safeJson(r); return b && b.id; },
  });
  errorRate.add(!ok);
  if (!ok) return null;
  casesOpened.add(1);
  return safeJson(res).id;
}

export function assignCase(caseId, investigatorId) {
  const res = post(
    `${CASE_URL}/api/v1/cases/${caseId}/assign`,
    { investigatorId: investigatorId || `inv-${__VU}` },
    { service: 'case', operation: 'assign' }
  );
  caseDuration.add(res.timings.duration);
  const ok = check(res, { 'case assign → 204': r => r.status === 204 });
  errorRate.add(!ok);
  return ok;
}

export function escalateCase(caseId, reason) {
  const res = post(
    `${CASE_URL}/api/v1/cases/${caseId}/escalate`,
    { reason },
    { service: 'case', operation: 'escalate' }
  );
  caseDuration.add(res.timings.duration);
  const ok = check(res, { 'case escalate → 204': r => r.status === 204 });
  errorRate.add(!ok);
  return ok;
}

export function closeCase(caseId, resolution) {
  const res = post(
    `${CASE_URL}/api/v1/cases/${caseId}/close`,
    { resolution },
    { service: 'case', operation: 'close' }
  );
  caseDuration.add(res.timings.duration);
  const ok = check(res, { 'case close → 204': r => r.status === 204 });
  errorRate.add(!ok);
  return ok;
}
