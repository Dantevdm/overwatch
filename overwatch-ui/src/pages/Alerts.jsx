import { Fragment, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, zar, shortTime, SEVERITIES } from '../api.js';
import { Card, SeverityBadge, StatusBadge, Empty } from '../components/Primitives.jsx';

export default function Alerts() {
  const [data, setData] = useState(null);
  const [severity, setSeverity] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState(null);
  const [detail, setDetail] = useState(null);

  // `?focus=<id>` — where the dashboard's live feed lands.
  //
  // Rendered as its own card above the table rather than by expanding a row,
  // because the alert linked to need not be on the current page: it arrived
  // under whatever filter and page the reader last left this screen on, and
  // hunting for it would be the opposite of a deep link. The table below stays
  // exactly as it was.
  const [params, setParams] = useSearchParams();
  const focusId = params.get('focus');
  const [focused, setFocused] = useState(null);

  useEffect(() => {
    api.alerts({ severity, status, page, size: 25 }).then(setData).catch(() => setData(null));
  }, [severity, status, page]);

  useEffect(() => {
    if (!focusId) { setFocused(null); return; }
    let cancelled = false;
    setFocused(null);
    api.alert(focusId)
      .then((a) => { if (!cancelled) setFocused(a); })
      .catch(() => { if (!cancelled) setFocused('missing'); });
    return () => { cancelled = true; };
  }, [focusId]);

  const clearFocus = () => {
    const next = new URLSearchParams(params);
    next.delete('focus');
    setParams(next, { replace: true });
  };

  const open = async (id) => {
    if (expanded === id) { setExpanded(null); return; }
    setExpanded(id);
    setDetail(null);
    setDetail(await api.alert(id));
  };

  const disposition = async (id, next) => {
    await api.setAlertStatus(id, next);
    setData(await api.alerts({ severity, status, page, size: 25 }));
    if (expanded === id) setDetail(await api.alert(id));
  };

  const table = (
    <Card
      title="Alerts"
      action={
        <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
          <Select value={severity} onChange={setSeverity} label="All severities"
                  options={SEVERITIES} />
          <Select value={status} onChange={setStatus} label="All statuses"
                  options={['OPEN', 'REVIEWING', 'CONFIRMED', 'CLEARED']} />
        </div>
      }
      style={{ overflow: 'hidden' }}
    >
      {!data ? <Empty>Loading…</Empty>
        : data.content.length === 0 ? <Empty>No alerts match these filters.</Empty>
        : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--muted-fg)',
                           fontSize: 'var(--text-xs)', textTransform: 'uppercase' }}>
                <Th>Severity</Th><Th>Score</Th><Th>Amount</Th>
                <Th>Status</Th><Th>Occurred</Th><Th />
              </tr>
            </thead>
            <tbody>
              {data.content.map((a) => (
                <Fragment key={a.id}>
                  <tr style={{ borderTop: '1px solid var(--border)' }}>
                    <Td><SeverityBadge severity={a.severity} /></Td>
                    <Td mono>{Number(a.riskScore).toFixed(2)}</Td>
                    <Td mono>{zar(a.amount)}</Td>
                    <Td><StatusBadge status={a.status} /></Td>
                    <Td muted>{shortTime(a.occurredAt)}</Td>
                    <Td>
                      <button onClick={() => open(a.id)} style={linkButton}>
                        {expanded === a.id ? 'Hide' : 'Why?'}
                      </button>
                    </Td>
                  </tr>
                  {expanded === a.id && (
                    <tr>
                      <td colSpan={6} style={{ background: 'var(--canvas)',
                                               padding: 'var(--space-4) var(--space-5)' }}>
                        {!detail ? 'Loading…' : (
                          <div>
                            <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600,
                                          color: 'var(--muted-fg)', marginBottom: 'var(--space-2)' }}>
                              CONTRIBUTING RULES
                            </div>
                            <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
                              {detail.hits.map((h, i) => (
                                <li key={i} style={{ marginBottom: 4 }}>
                                  <strong>{h.ruleType}</strong>
                                  <span style={{ color: 'var(--muted-fg)' }}> (+{Number(h.weight).toFixed(2)})</span>
                                  {' — '}{h.reason}
                                </li>
                              ))}
                            </ul>
                            <div style={{ display: 'flex', gap: 'var(--space-2)',
                                          marginTop: 'var(--space-4)' }}>
                              <button style={btn} onClick={() => disposition(a.id, 'CONFIRMED')}>
                                Confirm fraud
                              </button>
                              <button style={btn} onClick={() => disposition(a.id, 'CLEARED')}>
                                Clear — false positive
                              </button>
                            </div>
                            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                        marginTop: 'var(--space-3)', marginBottom: 0 }}>
                              Dispositioning alerts is what makes a per-rule false-positive
                              rate computable on the Rules page.
                            </p>
                          </div>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>

          <div style={{ display: 'flex', justifyContent: 'space-between',
                        alignItems: 'center', paddingTop: 'var(--space-4)',
                        fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
            <span>{data.totalElements.toLocaleString('en-ZA')} alerts</span>
            <span style={{ display: 'flex', gap: 6 }}>
              <button style={btn} disabled={page === 0}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</button>
              <button style={btn} disabled={page >= data.totalPages - 1}
                      onClick={() => setPage((p) => p + 1)}>Next</button>
            </span>
          </div>
        </div>
      )}
    </Card>
  );

  if (!focusId) return table;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>
      <Card title="Focused alert"
            action={<button onClick={clearFocus} style={linkButton}>Dismiss</button>}>
        {focused === null ? <Empty>Loading…</Empty>
          : focused === 'missing'
            ? <Empty>That alert no longer exists — it may have been cleared by a reset.</Empty>
            : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)',
                              flexWrap: 'wrap' }}>
                  <SeverityBadge severity={focused.severity} />
                  <StatusBadge status={focused.status} />
                  <span style={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>
                    {zar(focused.amount)}
                  </span>
                  <span style={{ color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
                    score {Number(focused.riskScore).toFixed(2)}
                  </span>
                  <span style={{ color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
                    occurred {shortTime(focused.occurredAt)}, detected {shortTime(focused.createdAt)}
                  </span>
                </div>
                <div>
                  <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600,
                                color: 'var(--muted-fg)', marginBottom: 'var(--space-2)' }}>
                    CONTRIBUTING RULES
                  </div>
                  <ul style={{ margin: 0, paddingLeft: '1.1rem' }}>
                    {focused.hits.map((h, i) => (
                      <li key={i} style={{ marginBottom: 4 }}>
                        <strong>{h.ruleType}</strong>
                        <span style={{ color: 'var(--muted-fg)' }}> (+{Number(h.weight).toFixed(2)})</span>
                        {' — '}{h.reason}
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            )}
      </Card>
      {table}
    </div>
  );
}

const linkButton = {
  background: 'none', border: 'none', color: 'var(--brand)',
  cursor: 'pointer', fontSize: 'var(--text-xs)', fontWeight: 600, padding: 0,
};
const btn = {
  height: 28, padding: '0 10px', borderRadius: 'var(--radius-md)',
  border: '1px solid var(--input-border)', background: 'var(--bg)',
  color: 'var(--fg)', fontSize: 'var(--text-xs)', cursor: 'pointer',
};

function Th({ children }) {
  return <th style={{ padding: 'var(--row-pad)', fontWeight: 600 }}>{children}</th>;
}
function Td({ children, mono, muted }) {
  return <td style={{
    padding: 'var(--row-pad)',
    fontFamily: mono ? 'var(--font-mono)' : undefined,
    fontVariantNumeric: mono ? 'tabular-nums' : undefined,
    color: muted ? 'var(--muted-fg)' : undefined,
  }}>{children}</td>;
}
function Select({ value, onChange, label, options }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}
            style={{ height: 30, borderRadius: 'var(--radius-md)',
                     border: '1px solid var(--input-border)', background: 'var(--bg)',
                     color: 'var(--fg)', fontSize: 'var(--text-xs)', padding: '0 8px' }}>
      <option value="">{label}</option>
      {options.map((o) => <option key={o} value={o}>{o}</option>)}
    </select>
  );
}
