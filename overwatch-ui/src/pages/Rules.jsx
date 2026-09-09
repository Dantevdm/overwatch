import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Card, StatusBadge, Empty } from '../components/Primitives.jsx';

const STATES = ['ENABLED', 'SHADOW', 'DISABLED'];

/**
 * Rule configuration and performance.
 *
 * This page is where "rules are data, not code" stops being a claim: changing a
 * state or weight here is an API call the engine picks up on its next refresh.
 */
export default function Rules() {
  const [rules, setRules] = useState(null);
  const [perf, setPerf] = useState([]);
  const [busy, setBusy] = useState(null);

  const load = async () => {
    const [r, p] = await Promise.all([api.rules(), api.rulePerformance()]);
    setRules(r); setPerf(p);
  };
  useEffect(() => { load().catch(() => setRules([])); }, []);

  const changeState = async (id, state) => {
    setBusy(id);
    try { await api.setRuleState(id, state); await load(); } finally { setBusy(null); }
  };

  const perfFor = (id) => perf.find((p) => p.ruleId === id);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>
      <Card title="Rules">
        {!rules ? <Empty>Loading…</Empty> : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
              <thead>
                <tr style={{ textAlign: 'left', color: 'var(--muted-fg)',
                             fontSize: 'var(--text-xs)', textTransform: 'uppercase' }}>
                  <th style={th}>Rule</th>
                  <th style={{ ...th, textAlign: 'right' }}>Weight</th>
                  <th style={{ ...th, textAlign: 'right' }}>Fired</th>
                  <th style={{ ...th, textAlign: 'right' }}>False positive</th>
                  <th style={th}>State</th>
                </tr>
              </thead>
              <tbody>
                {rules.map((r) => {
                  const p = perfFor(r.id);
                  return (
                    <tr key={r.id} style={{ borderTop: '1px solid var(--border)' }}>
                      <td style={td}>
                        <div style={{ fontWeight: 600 }}>{r.name}</div>
                        <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                      maxWidth: 520 }}>
                          {r.description}
                        </div>
                      </td>
                      <td style={{ ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                        {Number(r.weight).toFixed(2)}
                      </td>
                      <td style={{ ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                        {r.state === 'SHADOW'
                          ? <span title="Shadow rules record hits without raising alerts">
                              {(p?.shadowHits ?? 0).toLocaleString('en-ZA')}
                              <span style={{ color: 'var(--muted-fg)' }}> shadow</span>
                            </span>
                          : (p?.timesFired ?? 0).toLocaleString('en-ZA')}
                      </td>
                      <td style={{ ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums',
                                   color: 'var(--muted-fg)' }}>
                        {/* Null, not zero: an unreviewed rule reporting 0% would
                            read as a perfect rule. */}
                        {p?.falsePositiveRate == null
                          ? <span title="No alerts reviewed yet">not yet reviewed</span>
                          : `${(p.falsePositiveRate * 100).toFixed(0)}%`}
                      </td>
                      <td style={td}>
                        <select value={r.state} disabled={busy === r.id}
                                onChange={(e) => changeState(r.id, e.target.value)}
                                style={select}>
                          {STATES.map((s) => <option key={s} value={s}>{s}</option>)}
                        </select>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                        marginTop: 'var(--space-4)', marginBottom: 0 }}>
              <strong>SHADOW</strong> evaluates a rule against live traffic and records
              what it would have flagged, without raising alerts. That is how a rule is
              tuned before it is trusted. Changes take effect on the engine's next
              refresh — no redeploy.
            </p>
          </div>
        )}
      </Card>

      <Replay />
    </div>
  );
}

/** What-if analysis against stored history. */
function Replay() {
  const [types, setTypes] = useState([]);
  const [ruleType, setRuleType] = useState('HIGH_VALUE');
  const [threshold, setThreshold] = useState(30000);
  const [hours, setHours] = useState(24);
  const [result, setResult] = useState(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.replayRuleTypes().then((r) => setTypes(r.ruleTypes ?? [])).catch(() => {});
  }, []);

  const run = async () => {
    setRunning(true); setError(null);
    try {
      setResult(await api.replay({
        ruleType,
        parameters: { threshold: Number(threshold) },
        hours: Number(hours),
      }));
    } catch (e) {
      setError(e.message);
    } finally {
      setRunning(false);
    }
  };

  return (
    <Card title="What if we changed a threshold?">
      <p style={{ marginTop: 0, color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
        Replays stored transactions through a candidate configuration and reports what
        it <em>would</em> have caught. Nothing is written and no rule changes — so a
        threshold decision becomes a measurement rather than an argument.
      </p>

      <div style={{ display: 'flex', gap: 'var(--space-3)', flexWrap: 'wrap',
                    alignItems: 'flex-end' }}>
        <Field label="Rule">
          <select value={ruleType} onChange={(e) => setRuleType(e.target.value)} style={select}>
            {(types.length ? types : ['HIGH_VALUE']).map((t) =>
              <option key={t} value={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="Threshold (ZAR)">
          <input type="number" value={threshold} onChange={(e) => setThreshold(e.target.value)}
                 style={input} />
        </Field>
        <Field label="Window (hours)">
          <input type="number" value={hours} onChange={(e) => setHours(e.target.value)}
                 style={{ ...input, width: 90 }} />
        </Field>
        <button onClick={run} disabled={running} style={primaryBtn}>
          {running ? 'Replaying…' : 'Run replay'}
        </button>
      </div>

      {error && (
        <p style={{ color: 'var(--danger-fg)', fontSize: 'var(--text-sm)' }}>{error}</p>
      )}

      {result && (
        <div style={{ marginTop: 'var(--space-5)', paddingTop: 'var(--space-5)',
                      borderTop: '1px solid var(--border)' }}>
          <div style={{ display: 'flex', gap: 'var(--space-6)', flexWrap: 'wrap' }}>
            <Figure label="Evaluated" value={result.transactionsEvaluated.toLocaleString('en-ZA')} />
            <Figure label="Would have fired" value={result.wouldHaveFired.toLocaleString('en-ZA')} />
            <Figure label="Of traffic" value={`${result.firePercentage.toFixed(2)}%`} />
          </div>

          {result.samples.length > 0 && (
            <>
              <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600,
                            color: 'var(--muted-fg)', margin: 'var(--space-4) 0 var(--space-2)' }}>
                EXAMPLES
              </div>
              <ul style={{ margin: 0, paddingLeft: '1.1rem', fontSize: 'var(--text-sm)' }}>
                {result.samples.slice(0, 8).map((s) => (
                  <li key={s.transactionId} style={{ marginBottom: 4 }}>
                    <strong>{s.merchantName}</strong> — {s.reason}
                  </li>
                ))}
              </ul>
            </>
          )}
          {result.wouldHaveFired === 0 && (
            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
              Nothing in this window would have tripped that configuration. Rules that
              depend on card history report nothing here by design, rather than
              answering from a baseline that does not reflect the replayed window.
            </p>
          )}
        </div>
      )}
    </Card>
  );
}

function Field({ label, children }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4,
                    fontSize: 'var(--text-xs)', color: 'var(--muted-fg)', fontWeight: 600 }}>
      {label}
      {children}
    </label>
  );
}
function Figure({ label, value }) {
  return (
    <div>
      <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                    textTransform: 'uppercase', fontWeight: 600 }}>{label}</div>
      <div style={{ fontSize: 'var(--text-xl)', fontWeight: 700,
                    fontVariantNumeric: 'tabular-nums' }}>{value}</div>
    </div>
  );
}

const th = { padding: 'var(--row-pad)', fontWeight: 600 };
const td = { padding: 'var(--row-pad)', verticalAlign: 'top' };
const select = {
  height: 30, borderRadius: 'var(--radius-md)', border: '1px solid var(--input-border)',
  background: 'var(--bg)', color: 'var(--fg)', fontSize: 'var(--text-xs)', padding: '0 8px',
};
const input = { ...select, width: 130 };
const primaryBtn = {
  height: 30, padding: '0 14px', borderRadius: 'var(--radius-md)', border: 'none',
  background: 'var(--brand)', color: '#fff', fontSize: 'var(--text-xs)',
  fontWeight: 600, cursor: 'pointer',
};
