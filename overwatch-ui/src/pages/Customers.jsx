import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, zar, shortTime, SEVERITY_COLOR } from '../api.js';
import {
  Button, Card, Empty, SeverityBadge, StatTile, StatusBadge,
} from '../components/Primitives.jsx';

/**
 * The cardholder 360 view.
 *
 * Every rule in this system evaluates a single transaction, which is the right
 * unit for a real-time decision and the wrong one for an investigation. An
 * analyst handed an alert does not ask whether that transaction is odd — they
 * ask who this is, what their spending normally looks like, and whether this
 * fits. Nothing in the rest of the dashboard can answer that, because until the
 * cardholder existed the finest identity here was the card and a person with two
 * cards was two subjects.
 *
 * The risk panel is deliberately built as evidence rather than a verdict. A score
 * with a colour asks to be trusted; a score whose every line shows the
 * measurement behind it can be argued with, which is the only useful kind — the
 * whole job is being able to look at "24.6% of spend acquired abroad" and say
 * "they work in Nairobi", then know exactly which line to discount.
 */
export default function Customers() {
  const { id } = useParams();
  return id ? <Profile id={id} /> : <Directory />;
}

// ---- the directory ---------------------------------------------------------

function Directory() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState(null);

  const load = useCallback(() => api.customers({ query, page, size: 20 })
    .then((r) => { setRows(r.content); setTotal(r.totalElements); setError(null); })
    .catch((e) => setError(e.message)), [query, page]);

  // Debounced, so typing a surname is one request rather than one per keystroke.
  useEffect(() => {
    const timer = setTimeout(load, 250);
    return () => clearTimeout(timer);
  }, [load]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>
      <Card title="Cardholders"
            action={<span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
              {total.toLocaleString('en-ZA')} observed
            </span>}>
        <p style={{ marginTop: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)',
                    maxWidth: '80ch' }}>
          Built from the transactions that named them, not read from a customer
          table — this system watches a payment stream and does not own customer
          master data. A person with no transactions therefore does not appear
          here, which is the correct behaviour for a system that only knows what it
          has observed.
        </p>

        <input
          value={query}
          onChange={(e) => { setQuery(e.target.value); setPage(0); }}
          placeholder="Search by name or cardholder reference — try a surname"
          spellCheck={false}
          style={{
            width: '100%', height: 36, marginTop: 'var(--space-3)',
            padding: '0 var(--space-3)', borderRadius: 'var(--radius-md)',
            border: '1px solid var(--input-border)',
            background: 'var(--bg)', color: 'var(--fg)', fontSize: 'var(--text-base)',
          }} />

        {error && <p style={{ color: 'var(--danger-fg)', fontSize: 'var(--text-sm)' }}>{error}</p>}

        {rows === null ? <Empty>Loading…</Empty>
          : rows.length === 0 ? <Empty>Nobody matches “{query}”.</Empty> : (
          <table style={{ width: '100%', borderCollapse: 'collapse',
                          fontSize: 'var(--text-sm)', marginTop: 'var(--space-4)' }}>
            <thead>
              <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
                <th style={th}>Cardholder</th>
                <th style={th}>Reference</th>
                <th style={{ ...th, textAlign: 'right' }}>Cards</th>
                <th style={{ ...th, textAlign: 'right' }}>Transactions</th>
                <th style={{ ...th, textAlign: 'right' }}>Total spend</th>
                <th style={{ ...th, textAlign: 'right' }}>Alerts</th>
                <th style={th}>Last seen</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}
                    onClick={() => navigate(`/customers/${c.id}`)}
                    style={{ borderTop: '1px solid var(--border)', cursor: 'pointer' }}>
                  <td style={{ ...td, fontWeight: 600 }}>{c.name}</td>
                  <td style={{ ...td, fontFamily: 'var(--font-mono)',
                               color: 'var(--muted-fg)' }}>{c.id}</td>
                  <td style={num}>{c.cards}</td>
                  <td style={num}>{c.transactions.toLocaleString('en-ZA')}</td>
                  <td style={num}>{zar(c.totalSpend)}</td>
                  <td style={{ ...num, fontWeight: c.alerts > 0 ? 700 : 400,
                               color: c.alerts > 0 ? 'var(--danger-fg)' : 'var(--muted-fg)' }}>
                    {c.alerts}
                  </td>
                  <td style={{ ...td, color: 'var(--muted-fg)' }}>{shortTime(c.lastSeen)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div style={{ display: 'flex', gap: 'var(--space-3)', alignItems: 'center',
                      marginTop: 'var(--space-4)' }}>
          <Button disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            Previous
          </Button>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
            Page {page + 1} of {Math.max(1, Math.ceil(total / 20))}
          </span>
          <Button disabled={(page + 1) * 20 >= total} onClick={() => setPage((p) => p + 1)}>
            Next
          </Button>
        </div>
      </Card>
    </div>
  );
}

// ---- the profile -----------------------------------------------------------

function Profile({ id }) {
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    setProfile(null); setError(null);
    api.customer(id).then(setProfile).catch((e) => setError(e.message));
  }, [id]);

  if (error) return <Empty>{error}</Empty>;
  if (!profile) return <Empty>Loading…</Empty>;

  const p = profile;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between',
                      alignItems: 'flex-start', gap: 'var(--space-4)', flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, lineHeight: 1.2 }}>
              {p.name}
            </div>
            <div style={{ fontSize: 'var(--text-sm)', color: 'var(--muted-fg)',
                          marginTop: 4 }}>
              <span className="mono">{p.id}</span> · {p.homeCity} · {p.bank} ·{' '}
              {p.cards.length} card{p.cards.length === 1 ? '' : 's'}
            </div>
            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)', marginTop: 6 }}>
              {p.cards.map((c) => (
                <span key={c} className="mono" style={{
                  display: 'inline-block', marginRight: 6, padding: '1px 7px',
                  border: '1px solid var(--border)', borderRadius: 'var(--radius-full)',
                }}>{c}</span>
              ))}
            </div>
          </div>
          <Button onClick={() => navigate('/customers')}>← All cardholders</Button>
        </div>
      </Card>

      <div style={{ display: 'grid', gap: 'var(--space-4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))' }}>
        <StatTile label="Transactions" value={p.transactions.toLocaleString('en-ZA')}
                  sub={p.historyCapped ? 'aggregates over the most recent 5 000' : 'entire observed history'} />
        <StatTile label="Total spend" value={zar(p.totalSpend)} />
        <StatTile label="Typical amount" value={zar(p.averageAmount)}
                  sub={`largest ${zar(p.largestAmount)}`} />
        <StatTile label="Alerts raised" value={p.risk.alerts.toLocaleString('en-ZA')}
                  tone={p.risk.alerts > 0 ? 'warning' : 'success'}
                  sub={Object.entries(p.risk.alertsBySeverity)
                    .map(([k, v]) => `${v} ${k.toLowerCase()}`).join(', ') || 'none'} />
        <StatTile label="Observed since" value={new Date(p.firstSeen).toLocaleDateString('en-ZA')}
                  sub={`last seen ${shortTime(p.lastSeen)}`} />
      </div>

      <RiskPanel risk={p.risk} />

      <div style={{ display: 'grid', gap: 'var(--space-5)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))' }}>
        <Breakdown title="Where they spend" slices={p.byCategory} />
        <Breakdown title="Where it is acquired" slices={p.byCountry} highlight={(l) => l !== 'ZA'} />
        <Breakdown title="How the card is presented" slices={p.byChannel} />
      </div>

      <DayShape hours={p.byHour} />

      {p.alerts.length > 0 && <Alerts alerts={p.alerts} />}
      <Recent rows={p.recent} />
    </div>
  );
}

function RiskPanel({ risk }) {
  const tone = {
    ELEVATED: { bg: 'var(--danger-bg)', fg: 'var(--danger-fg)', bd: 'var(--danger-border)' },
    WATCH: { bg: 'var(--warning-bg)', fg: 'var(--warning-fg)', bd: 'var(--warning-border)' },
    NORMAL: { bg: 'var(--success-bg)', fg: 'var(--success-fg)', bd: 'var(--success-border)' },
  }[risk.band];

  return (
    <Card
      title="Behavioural profile"
      action={
        <span style={{
          padding: '3px 12px', borderRadius: 'var(--radius-full)',
          fontSize: 'var(--text-sm)', fontWeight: 700,
          background: tone.bg, color: tone.fg, border: `1px solid ${tone.bd}`,
        }}>
          {risk.band} · {risk.score}/100
        </span>
      }
    >
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
        <tbody>
          {risk.signals.map((s) => (
            <tr key={s.label} style={{ borderTop: '1px solid var(--border)' }}>
              <td style={{ ...td, width: 22 }}>
                {/* A marker, not colour alone: the dot says "above the population"
                    and the points column says what that was actually worth. */}
                <span aria-hidden="true" style={{
                  display: 'inline-block', width: 8, height: 8,
                  borderRadius: 'var(--radius-full)',
                  background: s.elevated ? 'var(--warning-fg)' : 'var(--border)',
                }} />
              </td>
              <td style={{ ...td, fontWeight: 600, whiteSpace: 'nowrap' }}>{s.label}</td>
              <td style={{ ...td, color: 'var(--muted-fg)' }}>{s.detail}</td>
              <td style={{ ...num, width: 70, fontWeight: s.points > 0 ? 700 : 400,
                           color: s.points > 0 ? 'var(--warning-fg)' : 'var(--muted-fg)' }}>
                {s.points > 0 ? `+${s.points}` : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <p style={{ marginBottom: 0, marginTop: 'var(--space-4)', padding: 'var(--space-3)',
                  fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                  background: 'var(--surface)', borderRadius: 'var(--radius-md)',
                  maxWidth: '90ch' }}>
        {risk.caveat}
      </p>
    </Card>
  );
}

/**
 * A breakdown as labelled bars.
 *
 * Bars rather than a pie: comparing angles is measurably worse than comparing
 * lengths against a shared baseline, and every slice is directly labelled with
 * its own figure so nothing rests on reading the geometry at all.
 */
function Breakdown({ title, slices, highlight }) {
  const top = slices.slice(0, 8);
  const max = Math.max(...top.map((s) => s.share), 0.0001);

  return (
    <Card title={title}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-3)' }}>
        {top.map((s) => {
          const flagged = highlight ? highlight(s.label) : false;
          return (
            <div key={s.label}>
              <div style={{ display: 'flex', justifyContent: 'space-between',
                            fontSize: 'var(--text-xs)', marginBottom: 3 }}>
                <span style={{ fontWeight: 600,
                               color: flagged ? 'var(--warning-fg)' : 'var(--fg)' }}>
                  {s.label}
                </span>
                <span style={{ color: 'var(--muted-fg)', fontVariantNumeric: 'tabular-nums' }}>
                  {s.count} · {(s.share * 100).toFixed(1)}% · {zar(s.value)}
                </span>
              </div>
              <div style={{ height: 8, background: 'var(--surface)',
                            borderRadius: 'var(--radius-full)', overflow: 'hidden' }}>
                <div style={{
                  width: `${(s.share / max) * 100}%`, height: '100%',
                  background: flagged ? 'var(--warning-fg)' : 'var(--brand)',
                  borderRadius: 'var(--radius-full)',
                }} />
              </div>
            </div>
          );
        })}
      </div>
      {slices.length > top.length && (
        <p style={{ marginBottom: 0, marginTop: 'var(--space-3)',
                    fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
          and {slices.length - top.length} more
        </p>
      )}
    </Card>
  );
}

/**
 * The shape of this cardholder's day, in local time.
 *
 * SAST, not UTC. The question the chart answers is whether this person spends at
 * hours people do not, and South African spend read in UTC is shifted two hours —
 * which puts the evening peak at 16:00 and makes the small-hours band look busy
 * for everybody.
 */
function DayShape({ hours }) {
  const max = Math.max(...hours, 1);
  return (
    <Card title="When they spend">
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 120 }}>
        {hours.map((count, hour) => {
          const small = hour >= 1 && hour < 5;
          return (
            <div key={hour} style={{ flex: 1, display: 'flex', flexDirection: 'column',
                                     alignItems: 'center', gap: 4 }}>
              <div title={`${String(hour).padStart(2, '0')}:00 — ${count} transactions`}
                   style={{
                     width: '100%', height: `${(count / max) * 100}%`, minHeight: count > 0 ? 2 : 0,
                     // The small hours are the band the late-night rule reacts to,
                     // so they are marked on the axis rather than left to be
                     // counted off by eye.
                     background: small ? 'var(--warning-fg)' : 'var(--brand)',
                     borderRadius: '2px 2px 0 0',
                   }} />
              <span style={{ fontSize: 9, color: 'var(--muted-fg)' }}>
                {hour % 3 === 0 ? String(hour).padStart(2, '0') : ''}
              </span>
            </div>
          );
        })}
      </div>
      <p style={{ marginBottom: 0, marginTop: 'var(--space-3)',
                  fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
        Hour of day, SAST. The highlighted bars are 01:00–04:59, the window the
        late-night rule reacts to.
      </p>
    </Card>
  );
}

function Alerts({ alerts }) {
  return (
    <Card title={`Alerts raised against this cardholder (${alerts.length})`}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
        <thead>
          <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
            <th style={th}>Severity</th>
            <th style={th}>Status</th>
            <th style={{ ...th, textAlign: 'right' }}>Score</th>
            <th style={{ ...th, textAlign: 'right' }}>Amount</th>
            <th style={th}>Rules that fired</th>
            <th style={th}>When</th>
          </tr>
        </thead>
        <tbody>
          {alerts.map((a) => (
            <tr key={a.id} style={{ borderTop: '1px solid var(--border)' }}>
              <td style={td}><SeverityBadge severity={a.severity} /></td>
              <td style={td}><StatusBadge status={a.status} /></td>
              <td style={{ ...num, color: SEVERITY_COLOR[a.severity] }}>
                {a.riskScore.toFixed(2)}
              </td>
              <td style={num}>{zar(a.amount)}</td>
              <td style={{ ...td, fontSize: 'var(--text-xs)' }}>{a.rules.join(', ') || '—'}</td>
              <td style={{ ...td, color: 'var(--muted-fg)' }}>{shortTime(a.occurredAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

function Recent({ rows }) {
  return (
    <Card title="Most recent transactions">
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
        <thead>
          <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
            <th style={th}>When</th>
            <th style={th}>Merchant</th>
            <th style={th}>Category</th>
            <th style={th}>Card</th>
            <th style={th}>Country</th>
            <th style={th}>Channel</th>
            <th style={{ ...th, textAlign: 'right' }}>Amount</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.id} style={{
              borderTop: '1px solid var(--border)',
              // A flagged row is tinted and carries a marker in the amount cell —
              // never colour on its own.
              background: t.flagged ? 'var(--danger-bg)' : 'transparent',
            }}>
              <td style={{ ...td, color: 'var(--muted-fg)' }}>{shortTime(t.occurredAt)}</td>
              <td style={td}>{t.merchantName}</td>
              <td style={{ ...td, color: 'var(--muted-fg)' }}>{t.merchantCategory}</td>
              <td style={{ ...td, fontFamily: 'var(--font-mono)',
                           fontSize: 'var(--text-xs)' }}>{t.cardId}</td>
              <td style={{ ...td, fontWeight: t.countryCode !== 'ZA' ? 700 : 400,
                           color: t.countryCode !== 'ZA' ? 'var(--warning-fg)' : 'inherit' }}>
                {t.countryCode}
              </td>
              <td style={{ ...td, color: 'var(--muted-fg)' }}>{t.channel}</td>
              <td style={{ ...num, fontWeight: 600 }}>
                {t.flagged && <span title="This transaction raised an alert"
                                    style={{ color: 'var(--danger-fg)', marginRight: 6 }}>▲</span>}
                {zar(t.amount)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

const th = { padding: 'var(--row-pad)', fontWeight: 600, whiteSpace: 'nowrap' };
const td = { padding: 'var(--row-pad)', verticalAlign: 'middle' };
const num = { ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' };
