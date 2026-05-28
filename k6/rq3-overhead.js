// RQ3: Distributed tracing overhead experiment.
//
// Measures request throughput, p95 latency, and error rate under identical
// 50-VU load at each of four trace sampling rates (0 %, 10 %, 50 %, 100 %).
//
// The TRACE_SAMPLE_RATE is set on the AML Kubernetes ConfigMap — not by k6.
// This script accepts SAMPLE_RATE only as a label so results can be compared.
//
// Use run-rq3.ps1 to execute all four runs automatically, or run manually:
//   k6 run -e SAMPLE_RATE=0.0   --out json=results/rq3_0_0.json   rq3-overhead.js
//   k6 run -e SAMPLE_RATE=0.1   --out json=results/rq3_0_1.json   rq3-overhead.js
//   k6 run -e SAMPLE_RATE=0.5   --out json=results/rq3_0_5.json   rq3-overhead.js
//   k6 run -e SAMPLE_RATE=1.0   --out json=results/rq3_1_0.json   rq3-overhead.js

import { sleep } from 'k6';
import { randomCustomer, randomTransaction, pickScenario } from './lib/data.js';
import {
  onboardCustomer, verifyCustomer,
  evaluateTransaction,
  openCase, assignCase, closeCase,
} from './lib/workflow.js';

const SAMPLE_RATE = __ENV.SAMPLE_RATE || '1.0';

// ── Options ───────────────────────────────────────────────────────────────────
export const options = {
  stages: [
    { duration: '20s', target: 50 },  // quick ramp — identical across all four runs
    { duration: '3m',  target: 50 },  // 3-minute measurement window
    { duration: '10s', target: 0  },  // drain
  ],
  thresholds: {
    // Relaxed — we are measuring overhead magnitude, not enforcing SLO here.
    http_req_duration:                  ['p(95)<2000'],
    'http_req_duration{service:kyc}':   ['p(95)<2000'],
    'http_req_duration{service:txn}':   ['p(95)<2000'],
    'http_req_duration{service:case}':  ['p(95)<2000'],
    kyc_request_duration:               ['p(95)<2000'],
    txn_request_duration:               ['p(95)<2000'],
    case_request_duration:              ['p(95)<2000'],
    aml_error_rate:                     ['rate<0.05'],
    http_req_failed:                    ['rate<0.05'],
  },
  // All metrics from this run are tagged with the current sampling rate so that
  // the four JSON output files can be post-processed and compared directly.
  tags: { sample_rate: SAMPLE_RATE },
};

// ── VU scenario ───────────────────────────────────────────────────────────────
// Fixed 3 transactions per iteration so throughput is comparable across runs.
export default function () {
  const customerData = randomCustomer(__VU, __ITER);
  const customerId   = onboardCustomer(customerData);
  if (!customerId) return;
  sleep(0.3);

  verifyCustomer(customerId);
  sleep(0.2);

  for (let i = 0; i < 3; i++) {
    const result = evaluateTransaction(
      randomTransaction(customerId, pickScenario())
    );
    sleep(0.5);

    if (result && result.alerted && result.alertId) {
      const caseId = openCase(result.alertId, customerId, result.riskScore);
      if (caseId) {
        sleep(0.2);
        assignCase(caseId);
        sleep(0.2);
        closeCase(caseId, 'RQ3 auto-closed by k6');
        sleep(0.2);
      }
    }
  }

  sleep(1);
}
