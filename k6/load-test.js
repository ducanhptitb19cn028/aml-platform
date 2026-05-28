// AML platform baseline load test — 50 virtual users (paper §4.1).
//
// Simulates the full AML compliance workflow per VU iteration:
//   onboard customer → verify → submit 3-5 transactions →
//   open / assign / (escalate) / close case when an alert fires.
//
// Run:
//   k6 run load-test.js
//   k6 run -e KYC_URL=http://customer-kyc.aml:8082 \
//           -e TXN_URL=http://transaction-monitoring.aml:8081 \
//           -e CASE_URL=http://case-management.aml:8080 \
//           load-test.js

import { sleep } from 'k6';
import { randomCustomer, randomTransaction, pickScenario } from './lib/data.js';
import {
  onboardCustomer, verifyCustomer, updateRisk,
  evaluateTransaction,
  openCase, assignCase, escalateCase, closeCase,
} from './lib/workflow.js';

// ── Options ───────────────────────────────────────────────────────────────────
export const options = {
  stages: [
    { duration: '30s', target: 50 },  // ramp-up to 50 VUs
    { duration: '5m',  target: 50 },  // steady-state (paper baseline)
    { duration: '30s', target: 0  },  // ramp-down
  ],
  thresholds: {
    // Paper SLO: p95 < 500 ms across the whole platform
    http_req_duration:                  ['p(95)<500'],
    'http_req_duration{service:kyc}':   ['p(95)<500'],
    'http_req_duration{service:txn}':   ['p(95)<500'],
    'http_req_duration{service:case}':  ['p(95)<500'],
    // Per-service Trend metrics (appear in --out json for analysis)
    kyc_request_duration:               ['p(95)<500'],
    txn_request_duration:               ['p(95)<500'],
    case_request_duration:              ['p(95)<500'],
    // Reliability gate
    aml_error_rate:                     ['rate<0.01'],
    http_req_failed:                    ['rate<0.01'],
  },
};

// ── VU scenario ───────────────────────────────────────────────────────────────
export default function () {
  // 1. Onboard a unique customer for this VU iteration.
  const customerData = randomCustomer(__VU, __ITER);
  const customerId   = onboardCustomer(customerData);
  if (!customerId) return;
  sleep(0.3);

  // 2. Complete KYC verification.
  verifyCustomer(customerId);
  sleep(0.2);

  // 3. 10 % of customers are elevated to HIGH risk to exercise that path.
  if (Math.random() < 0.10) {
    updateRisk(customerId, 'HIGH');
    sleep(0.2);
  }

  // 4. Submit 3–5 transactions with a realistic scenario mix.
  const txnCount = Math.floor(Math.random() * 3) + 3; // 3, 4, or 5
  for (let i = 0; i < txnCount; i++) {
    const result = evaluateTransaction(
      randomTransaction(customerId, pickScenario())
    );
    sleep(0.5);

    // 5. When a transaction is alerted, run the full case lifecycle.
    if (result && result.alerted && result.alertId) {
      const caseId = openCase(result.alertId, customerId, result.riskScore);
      if (!caseId) continue;
      sleep(0.3);

      assignCase(caseId);
      sleep(0.3);

      // 30 % of cases are escalated before closing.
      if (Math.random() < 0.30) {
        escalateCase(caseId, 'Suspicious pattern detected — escalated by k6');
        sleep(0.5);
      }

      closeCase(caseId, 'Reviewed and resolved — k6 load test');
      sleep(0.2);
    }
  }

  // Think time between workflow iterations: 0.5–2 s (realistic operator pace).
  sleep(Math.random() * 1.5 + 0.5);
}
