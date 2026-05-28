// Test data generators for the AML load test suite.
// All country and currency codes are valid ISO standards.

export const COUNTRIES        = ['GB', 'US', 'DE', 'FR', 'NL', 'BE', 'SE', 'NO', 'DK', 'CH'];
export const HIGH_RISK_COUNTRIES = ['IR', 'KP', 'RU', 'BY', 'SY', 'CU'];
export const CURRENCIES       = ['GBP', 'USD', 'EUR', 'CHF', 'SEK', 'NOK', 'DKK'];
export const CHANNELS         = ['SEPA', 'FASTER_PAYMENTS', 'SWIFT', 'CARD', 'CASH_DEPOSIT', 'CRYPTO_OFFRAMP'];
export const STANDARD_CHANNELS = ['SEPA', 'FASTER_PAYMENTS', 'CARD'];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function randInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

// Returns a customer payload with a unique ID per (VU, iteration).
export function randomCustomer(vu, iter) {
  return {
    customerId:       `CUST-${vu}-${iter}-${Date.now()}`,
    legalName:        `Test Customer ${vu}-${iter}`,
    residencyCountry: pick(COUNTRIES),
  };
}

// Returns a transaction payload shaped for the given scenario.
//
// Scenarios map to AML rules:
//   high_value   → AML-101 (amount > £10 000)
//   high_risk    → AML-404 (high-risk destination corridor)
//   structuring  → AML-303 (just under £10 000 threshold)
//   crypto       → AML-404 (CRYPTO_OFFRAMP channel)
//   clean        → no rule triggered
export function randomTransaction(customerId, scenario) {
  switch (scenario) {
    case 'high_value':
      return {
        customerId,
        amount:             String(randInt(15000, 100000)),
        currency:           pick(CURRENCIES),
        originCountry:      pick(COUNTRIES),
        destinationCountry: pick(COUNTRIES),
        channel:            pick(STANDARD_CHANNELS),
      };
    case 'high_risk':
      return {
        customerId,
        amount:             String(randInt(500, 8000)),
        currency:           pick(CURRENCIES),
        originCountry:      pick(COUNTRIES),
        destinationCountry: pick(HIGH_RISK_COUNTRIES),
        channel:            'SWIFT',
      };
    case 'structuring':
      return {
        customerId,
        amount:             String(randInt(9000, 9999)),
        currency:           pick(CURRENCIES),
        originCountry:      pick(COUNTRIES),
        destinationCountry: pick(COUNTRIES),
        channel:            pick(STANDARD_CHANNELS),
      };
    case 'crypto':
      return {
        customerId,
        amount:             String(randInt(1000, 10000)),
        currency:           pick(CURRENCIES),
        originCountry:      pick(COUNTRIES),
        destinationCountry: pick(COUNTRIES),
        channel:            'CRYPTO_OFFRAMP',
      };
    default: // clean
      return {
        customerId,
        amount:             String(randInt(10, 2000)),
        currency:           pick(CURRENCIES),
        originCountry:      pick(COUNTRIES),
        destinationCountry: pick(COUNTRIES),
        channel:            pick(STANDARD_CHANNELS),
      };
  }
}

// Weighted scenario distribution that mirrors realistic AML transaction mix.
// 55 % clean · 20 % high-value · 10 % high-risk corridor · 10 % structuring · 5 % crypto
export function pickScenario() {
  const r = Math.random();
  if (r < 0.55) return 'clean';
  if (r < 0.75) return 'high_value';
  if (r < 0.85) return 'high_risk';
  if (r < 0.95) return 'structuring';
  return 'crypto';
}
