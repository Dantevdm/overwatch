/**
 * API client.
 *
 * Everything goes through /api, which Vite proxies to the BFF in development and
 * which is same-origin in Docker — so the browser never needs to know where the
 * API lives, and CORS never enters the picture.
 */
const BASE = '/api';

async function get(path, params = {}) {
  const query = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&');

  const res = await fetch(`${BASE}${path}${query ? `?${query}` : ''}`);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.json();
}

async function send(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} on ${path}`);
  return res.status === 204 ? null : res.json();
}

export const api = {
  dashboard: () => get('/stats/dashboard'),
  alerts: (p) => get('/alerts', p),
  alert: (id) => get(`/alerts/${id}`),
  setAlertStatus: (id, status) => send('PATCH', `/alerts/${id}/status`, { status }),
  transactions: (p) => get('/transactions', p),
  rules: () => get('/rules'),
  rulePerformance: () => get('/rules/performance'),
  setRuleState: (id, state) => send('PATCH', `/rules/${id}/state`, { state }),
  setRuleWeight: (id, weight) => send('PATCH', `/rules/${id}/weight`, { weight }),
  replay: (body) => send('POST', '/replay', body),
  replayRuleTypes: () => get('/replay/rule-types'),

  // Simulator control. Proxied by the API, so the browser never needs to know
  // that the simulator is a separate service on a port nobody publishes.
  simulatorStatus: () => get('/simulator/status'),
  simulatorStart: () => send('POST', '/simulator/start'),
  simulatorPause: () => send('POST', '/simulator/pause'),
  simulatorRate: (perSecond) => send('POST', `/simulator/rate?perSecond=${perSecond}`),
  simulatorFraudRate: (rate) => send('POST', `/simulator/fraud-rate?rate=${rate}`),
  simulatorInject: (pattern) => send('POST', `/simulator/inject/${pattern}`),
};

/**
 * What each injectable pattern is for. The simulator advertises the names; this
 * says what a reviewer will see when they press the button, which is the part
 * that makes the control panel a demonstration rather than a set of levers.
 */
export const PATTERN_COPY = {
  HIGH_VALUE:         { label: 'High value',        rule: 'HIGH_VALUE',         blurb: 'One very large amount on a single card.' },
  VELOCITY_BURST:     { label: 'Velocity burst',    rule: 'VELOCITY',           blurb: 'Several transactions on one card within minutes.' },
  LATE_NIGHT:         { label: 'Late night',        rule: 'LATE_NIGHT',         blurb: 'Timestamped in the small hours, SAST.' },
  ROUND_AMOUNT:       { label: 'Round amount',      rule: 'ROUND_AMOUNT',       blurb: 'An exact multiple of R1 000 above the floor.' },
  CROSS_BORDER:       { label: 'Cross border',      rule: 'CROSS_BORDER',       blurb: 'Acquired outside South Africa.' },
  HIGH_RISK_CATEGORY: { label: 'High-risk category', rule: 'CATEGORY_WATCHLIST', blurb: 'A crypto, gambling or forex merchant.' },
  COMPOUND:           { label: 'Compound',          rule: 'five rules at once',  blurb: 'Large, round, foreign, small-hours and crypto together — the one that reaches CRITICAL.' },
};

/** "R52 340.00" — space as thousands separator, the South African convention. */
export function zar(amount) {
  const n = Number(amount ?? 0);
  return `R${n.toLocaleString('en-ZA', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
  }).replace(/,/g, ' ')}`;
}

export function shortTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  if (mins < 1440) return `${Math.round(mins / 60)}h ago`;
  return d.toLocaleDateString('en-ZA');
}

export const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];

export const SEVERITY_COLOR = {
  LOW: 'var(--sev-low)',
  MEDIUM: 'var(--sev-medium)',
  HIGH: 'var(--sev-high)',
  CRITICAL: 'var(--sev-critical)',
};
