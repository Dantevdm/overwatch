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

/**
 * Sends a body that is already a string, without re-encoding it.
 *
 * `send` above calls JSON.stringify, which is right for an object and wrong for
 * text that is already JSON — it would arrive at the server as a quoted string.
 * The stream publish endpoint promises to put the caller's exact bytes on the
 * topic, so it needs the bytes rather than a stringification of them.
 */
async function sendRaw(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  const text = await res.text();
  if (!res.ok) {
    // The API answers a rejected payload with the parser's own complaint, which
    // is the useful part. Surfacing "400 Bad Request" alone would throw it away.
    throw new Error(detail(text) || `${res.status} ${res.statusText} on ${path}`);
  }
  return text ? JSON.parse(text) : null;
}

/** The message out of a Spring error body, if it looks like one. */
function detail(text) {
  try {
    const body = JSON.parse(text);
    return body.detail || body.message || body.error || null;
  } catch {
    return null;
  }
}

export const api = {
  dashboard: (p) => get('/stats/dashboard', p),
  alerts: (p) => get('/alerts', p),
  alert: (id) => get(`/alerts/${id}`),
  setAlertStatus: (id, status) => send('PATCH', `/alerts/${id}/status`, { status }),
  transactions: (p) => get('/transactions', p),
  // The categories actually present in the data, most common first. Fetched
  // rather than hardcoded: the simulator's merchant list is the authority on
  // what exists, and a dropdown offering a category with no rows behind it is
  // a dropdown that lies.
  transactionCategories: () => get('/transactions/categories'),
  rules: () => get('/rules'),
  rulePerformance: () => get('/rules/performance'),
  setRuleState: (id, state) => send('PATCH', `/rules/${id}/state`, { state }),
  setRuleWeight: (id, weight) => send('PATCH', `/rules/${id}/weight`, { weight }),
  replay: (body) => send('POST', '/replay', body),

  // Clears transactions, alerts and rule hits — not rule configuration. Always
  // behind a confirmation in the UI: it is the one call here that destroys data.
  //
  // `metrics: true` additionally deletes this stack's series from Prometheus.
  // Separate and off by default, because it destroys something the data reset
  // does not: the record that the run ever happened.
  resetData: ({ metrics = false } = {}) =>
    send('POST', `/admin/reset${metrics ? '?metrics=true' : ''}`),
  replayRuleTypes: () => get('/replay/rule-types'),

  // Cardholders. Derived from observed transactions rather than a customer
  // table, because this system does not own customer master data — so a person
  // with no transactions correctly does not exist here.
  customers: (p) => get('/customers', p),
  customer: (id) => get(`/customers/${encodeURIComponent(id)}`),

  // The stream itself. Reads are safe to poll: the API peeks by explicit
  // partition assignment, so nothing here joins a consumer group or commits an
  // offset, and looking at a topic cannot move the engine's position in it.
  streamTopics: () => get('/streams/topics'),
  streamGroups: () => get('/streams/groups'),
  streamMessages: (topic, limit) => get(`/streams/topics/${topic}/messages`, { limit }),
  streamTemplate: () => get('/streams/template'),

  // Both of these publish to the pipeline's input topic, so both are behind
  // overwatch.api.allow-stream-writes and both are confirmed in the UI.
  streamPublish: (rawJson) => sendRaw('POST', '/streams/publish', rawJson),
  streamRedeliver: (count) => send('POST', `/streams/redeliver?count=${count}`),

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

/**
 * The windows the dashboard's time filter offers.
 *
 * `minutes` is what the API takes; `label` is what the control shows. The server
 * derives bucket width from the range and returns it, so this list carries no
 * opinion about bucketing — adding a range here needs no server change.
 */
export const RANGES = [
  { minutes: 5,     label: '5m' },
  { minutes: 15,    label: '15m' },
  { minutes: 30,    label: '30m' },
  { minutes: 60,    label: '1h' },
  { minutes: 720,   label: '12h' },
  { minutes: 1440,  label: '24h' },
  { minutes: 10080, label: '7d' },
];

export const DEFAULT_RANGE = 1440;

/**
 * Rows per page, and the default.
 *
 * Ten by default. A table is a thing you scan and then act on, and a screen that
 * opens with fifty rows makes the reader scroll past the paging controls to
 * discover they exist — so the first page fits inside the card, and the reader
 * asks for more if they want it. Matches the API's own default, so a request
 * from Postman and a request from this UI return the same page.
 */
export const PAGE_SIZES = [10, 25, 50];
export const DEFAULT_PAGE_SIZE = 10;

/**
 * The windows a table can be filtered to.
 *
 * Distinct from RANGES, which the dashboard uses: a chart's window decides how
 * the data is bucketed and is capped at seven days by the server, whereas a
 * table's window is just a lower bound and "everything" is a legitimate answer.
 * Sent as `hours`, with 0 meaning no bound at all.
 */
export const TABLE_WINDOWS = [
  { value: 24,   label: 'Last 24 hours' },
  { value: 72,   label: 'Last 3 days' },
  { value: 168,  label: 'Last 7 days' },
  { value: 720,  label: 'Last 30 days' },
  { value: 0,    label: 'All time' },
];

export const DEFAULT_WINDOW = 168;

/**
 * "10 seconds", "30 minutes", "6 hours" — a bucket width in words.
 *
 * The charts are titled from this rather than hardcoding "per hour", because the
 * same chart is per-10-seconds over five minutes and per-6-hours over a week, and
 * a chart that mislabels its own bucket is worse than one with no label at all.
 */
export function bucketLabel(seconds) {
  if (!seconds || seconds <= 0) return '';
  if (seconds % 3600 === 0) {
    const h = seconds / 3600;
    return h === 1 ? 'hour' : `${h} hours`;
  }
  if (seconds % 60 === 0) {
    const m = seconds / 60;
    return m === 1 ? 'minute' : `${m} minutes`;
  }
  return seconds === 1 ? 'second' : `${seconds} seconds`;
}

/** The label for a range, for prose like "over the last 15m". */
export function rangeLabel(minutes) {
  return RANGES.find((r) => r.minutes === minutes)?.label ?? `${minutes}m`;
}

/**
 * "R52 340.00" — space as thousands separator, the South African convention.
 *
 * Grouped by hand rather than by post-processing a locale string. The obvious
 * version, `toLocaleString('en-ZA', …).replace(/,/g, ' ')`, assumes en-ZA
 * groups with commas the way en-US does. It does not: en-ZA groups with a
 * non-breaking space and uses a comma as the *decimal* separator, so that
 * replace deleted the decimal point instead of the thousands separator and
 * R863.11 rendered as "R863 11" — every amount on every screen silently a
 * hundred times too large to read.
 *
 * The separator is a non-breaking space so an amount never wraps across two
 * lines mid-number.
 */
export function zar(amount) {
  const n = Number(amount ?? 0);
  if (!Number.isFinite(n)) return 'R0.00';
  const [whole, fraction] = Math.abs(n).toFixed(2).split('.');
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, '\u00A0');
  return `${n < 0 ? '-' : ''}R${grouped}.${fraction}`;
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
