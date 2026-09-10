/**
 * Every endpoint this system exposes, as data.
 *
 * The API page renders this rather than hardcoding markup per endpoint, so
 * adding a route is one entry here. It deliberately duplicates nothing that the
 * OpenAPI document already holds: `/swagger-ui.html` is generated from the
 * controllers and is the authority on schemas, and it is linked from the page.
 * This exists for what a schema cannot say — which call to make first, what a
 * sensible value looks like, and why a parameter is there at all.
 *
 * `fill` names a live lookup for a path parameter. An id-shaped placeholder is
 * the single most common reason a copied request 404s, so the page can go and
 * fetch a real one from the running system instead of leaving `{id}` in the URL.
 */
export const CATALOGUE = [
  // ---- Alerts --------------------------------------------------------------
  {
    group: 'Alerts',
    method: 'GET',
    path: '/api/alerts',
    summary: 'Alerts, newest first, filtered and paged.',
    why: 'The brief\'s "retrieval of this data via an API". Paged because the '
       + 'store grows without bound and an unbounded list of alerts is a '
       + 'denial-of-service against your own dashboard.',
    params: [
      { in: 'query', name: 'severity', value: '', hint: 'One severity band.',
        options: ['', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] },
      { in: 'query', name: 'status', value: '', hint: 'Where the alert is in triage.',
        options: ['', 'OPEN', 'REVIEWING', 'CONFIRMED', 'CLEARED'] },
      { in: 'query', name: 'hours', value: '24', hint: 'How far back to look.' },
      { in: 'query', name: 'page', value: '0', hint: 'Zero-based page number.' },
      { in: 'query', name: 'size', value: '10', hint: 'Rows per page.' },
    ],
  },
  {
    group: 'Alerts',
    method: 'GET',
    path: '/api/alerts/{id}',
    summary: 'One alert, with every rule that contributed to it.',
    why: 'The list omits the rule hits; this is where the score is broken down. '
       + 'An alert that says only "0.85 CRITICAL" tells an analyst something is '
       + 'wrong without saying what.',
    params: [
      { in: 'path', name: 'id', value: '', hint: 'Alert UUID.', fill: 'alert' },
    ],
  },
  {
    group: 'Alerts',
    method: 'PATCH',
    path: '/api/alerts/{id}/status',
    summary: 'Move an alert through triage.',
    why: 'The only write on the alert: the detection itself is immutable. What a '
       + 'human decided about it is not part of what the engine found.',
    params: [
      { in: 'path', name: 'id', value: '', hint: 'Alert UUID.', fill: 'alert' },
    ],
    body: JSON.stringify({ status: 'REVIEWING' }, null, 2),
  },

  // ---- Transactions --------------------------------------------------------
  {
    group: 'Transactions',
    method: 'GET',
    path: '/api/transactions',
    summary: 'The transaction stream as stored, filtered and paged.',
    why: 'Everything that arrived, not only what was flagged — the denominator '
       + 'for every rate on the dashboard.',
    params: [
      { in: 'query', name: 'category', value: '', hint: 'Merchant category, e.g. crypto.' },
      { in: 'query', name: 'cardId', value: '', hint: 'One card\'s transactions.' },
      { in: 'query', name: 'hours', value: '24', hint: 'How far back to look.' },
      { in: 'query', name: 'page', value: '0', hint: 'Zero-based page number.' },
      { in: 'query', name: 'size', value: '10', hint: 'Rows per page.' },
    ],
  },
  {
    group: 'Transactions',
    method: 'GET',
    path: '/api/transactions/{id}',
    summary: 'One transaction.',
    params: [
      { in: 'path', name: 'id', value: '', hint: 'Transaction UUID.', fill: 'transaction' },
    ],
  },

  // ---- Rules ---------------------------------------------------------------
  {
    group: 'Rules',
    method: 'GET',
    path: '/api/rules',
    summary: 'The rule set, with each rule\'s weight, state and parameters.',
    why: 'Rules are data, not code. This is the configuration the engine '
       + 're-reads every 30 seconds, so what you see here is what is running.',
    params: [],
  },
  {
    group: 'Rules',
    method: 'GET',
    path: '/api/rules/performance',
    summary: 'How much work each rule is actually doing.',
    why: 'A rule that has never fired and a rule that fires on everything are '
       + 'both broken, in opposite directions. This is the number that tells '
       + 'you which.',
    params: [],
  },
  {
    group: 'Rules',
    method: 'PATCH',
    path: '/api/rules/{id}/state',
    summary: 'Enable a rule, disable it, or put it in shadow.',
    why: 'SHADOW is the interesting one: the rule evaluates and its hits are '
       + 'recorded, but it contributes nothing to the score and raises no '
       + 'alert. That is how a candidate rule earns its place before it can '
       + 'wake anybody up.',
    params: [
      { in: 'path', name: 'id', value: '', hint: 'Numeric rule id.', fill: 'rule' },
    ],
    body: JSON.stringify({ state: 'SHADOW' }, null, 2),
  },
  {
    group: 'Rules',
    method: 'PATCH',
    path: '/api/rules/{id}/weight',
    summary: 'Change how much a rule contributes to the composite score.',
    params: [
      { in: 'path', name: 'id', value: '', hint: 'Numeric rule id.', fill: 'rule' },
    ],
    body: JSON.stringify({ weight: 0.45 }, null, 2),
  },

  // ---- Replay --------------------------------------------------------------
  {
    group: 'Replay',
    method: 'GET',
    path: '/api/replay/rule-types',
    summary: 'Rule types available to replay.',
    params: [],
  },
  {
    group: 'Replay',
    method: 'POST',
    path: '/api/replay',
    summary: 'What would this configuration have caught?',
    why: 'Evaluates stored history against a candidate configuration. Nothing '
       + 'is written and no rule changes, so a threshold decision becomes a '
       + 'measurement instead of an argument.',
    params: [],
    body: JSON.stringify({ ruleType: 'HIGH_VALUE', hours: 24, parameters: { threshold: 30000 } }, null, 2),
  },
  {
    group: 'Replay',
    method: 'GET',
    path: '/api/replay/sweepable',
    summary: 'What each rule can be swept on, and a ladder to start from.',
    why: 'Includes the rules that cannot be swept, each with the reason — a '
       + 'country list is not a threshold, and a rule that counts a card\'s '
       + 'recent history has nothing to count during a replay.',
    params: [],
  },
  {
    group: 'Replay',
    method: 'POST',
    path: '/api/replay/sweep',
    summary: 'Many candidate values for one parameter, in a single pass.',
    why: 'Replay answers "what would this threshold have caught". This answers '
       + '"where should it sit", which is the question actually being asked. One '
       + 'pass over the history evaluates every candidate, so seven thresholds '
       + 'cost one trip through the data rather than seven.',
    params: [],
    body: JSON.stringify({
      ruleType: 'HIGH_VALUE',
      parameter: 'threshold',
      values: [10000, 20000, 30000, 50000, 75000, 100000, 150000],
      hours: 168,
    }, null, 2),
  },

  // ---- Statistics ----------------------------------------------------------
  {
    group: 'Statistics',
    method: 'GET',
    path: '/api/stats/dashboard',
    summary: 'Everything the dashboard\'s landing page needs, in one call.',
    why: 'One request rather than six. The dashboard polls, and six polling '
       + 'endpoints is six times the load for a view that is always rendered '
       + 'together.',
    params: [
      { in: 'query', name: 'rangeMinutes', value: '1440',
        hint: 'Window for the time series. 5 to 10080; clamped, not rejected.' },
    ],
  },

  // ---- Streams -------------------------------------------------------------
  {
    group: 'Streams',
    method: 'GET',
    path: '/api/streams/topics',
    summary: 'Topics, partitions and offsets.',
    params: [],
  },
  {
    group: 'Streams',
    method: 'GET',
    path: '/api/streams/groups',
    summary: 'Consumer groups, with per-partition lag.',
    why: 'Lag is the end offset minus the group\'s committed offset — computed, '
       + 'because the broker has no such field.',
    params: [],
  },
  {
    group: 'Streams',
    method: 'GET',
    path: '/api/streams/topics/{topic}/messages',
    summary: 'The tail of a topic, newest first.',
    why: 'Read by explicit assignment and seek, never by subscribing — so '
       + 'looking at a topic cannot move the engine\'s position in it.',
    params: [
      { in: 'path', name: 'topic', value: 'transactions', hint: 'transactions or fraud-alerts',
        options: ['transactions', 'fraud-alerts'] },
      { in: 'query', name: 'limit', value: '5', hint: 'Capped at 100.' },
    ],
  },
  {
    group: 'Streams',
    method: 'POST',
    path: '/api/streams/redeliver',
    summary: 'Re-publish recent messages, to prove redelivery is handled.',
    why: 'Kafka delivers at least once. This makes the duplicates happen on '
       + 'purpose: the engine skips each one on its primary key, '
       + 'transactions_redelivered_total rises, and no totals move.',
    params: [
      { in: 'query', name: 'count', value: '5', hint: 'How many to re-publish. Capped at 100.' },
    ],
    danger: true,
  },

  // ---- Simulator -----------------------------------------------------------
  {
    group: 'Simulator',
    method: 'GET',
    path: '/api/simulator/status',
    summary: 'Is the stream running, how fast, and how much has it produced.',
    why: 'Proxied through this service. The simulator publishes no host port, so '
       + 'the browser stays on one origin and nothing needs CORS.',
    params: [],
  },
  {
    group: 'Simulator',
    method: 'POST',
    path: '/api/simulator/inject/{pattern}',
    summary: 'Inject a transaction shaped to trip a specific rule.',
    why: 'The demo\'s shortcut past waiting. COMPOUND is the one that reaches '
       + 'CRITICAL — large, round, foreign, small-hours and crypto together.',
    params: [
      { in: 'path', name: 'pattern', value: 'COMPOUND', hint: 'Which pattern to inject.',
        options: ['HIGH_VALUE', 'VELOCITY_BURST', 'LATE_NIGHT', 'ROUND_AMOUNT',
                  'CROSS_BORDER', 'HIGH_RISK_CATEGORY', 'COMPOUND'] },
    ],
    danger: true,
  },

  // ---- Admin ---------------------------------------------------------------
  {
    group: 'Admin',
    method: 'POST',
    path: '/api/admin/reset',
    summary: 'Clear transactions, alerts and rule hits.',
    why: 'Destroys data, and nothing in this system authenticates — so it sits '
       + 'behind overwatch.api.allow-reset. Rule configuration survives: rules '
       + 'are configuration, not history. Prometheus counters survive too, '
       + 'because they are monotonic by contract.',
    params: [],
    danger: true,
  },
];

/** The groups, in the order the catalogue declares them. */
export const GROUPS = [...new Set(CATALOGUE.map((e) => e.group))];

/** A stable key for an endpoint — method plus path is unique across the API. */
export function endpointKey(endpoint) {
  return `${endpoint.method} ${endpoint.path}`;
}
