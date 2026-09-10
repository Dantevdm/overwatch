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

    </div>
  );
}

const th = { padding: 'var(--row-pad)', fontWeight: 600 };
const td = { padding: 'var(--row-pad)', verticalAlign: 'top' };
const select = {
  height: 30, borderRadius: 'var(--radius-md)', border: '1px solid var(--input-border)',
  background: 'var(--bg)', color: 'var(--fg)', fontSize: 'var(--text-xs)', padding: '0 8px',
};
