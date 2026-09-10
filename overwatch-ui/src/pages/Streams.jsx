import { Fragment, useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { Button, Card, Code, CopyButton, Empty, StatTile } from '../components/Primitives.jsx';

/**
 * The stream, and two operations on it.
 *
 * The Redpanda Console does topic browsing better than this ever will, and it is
 * linked from the sidebar and from this page. This screen exists for the part
 * the console structurally cannot do: connect the stream to the pipeline reading
 * it, on one screen, and let you act on it.
 *
 * Two operations, and both are chosen because they demonstrate a property rather
 * than because they were easy to expose:
 *
 *  - Publish puts one transaction on the topic and it arrives in the pipeline
 *    within a second. That is the difference between claiming the streaming step
 *    is real and showing it.
 *  - Re-deliver republishes recent messages verbatim, which is the at-least-once
 *    guarantee made to happen on purpose. The engine skips every one on its
 *    primary key: the redelivery counter climbs and no total moves. A guard
 *    nobody has watched fail to double-count is a guard nobody believes.
 */
export default function Streams() {
  const [topics, setTopics] = useState(null);
  const [groups, setGroups] = useState([]);
  const [error, setError] = useState(null);
  const [topic, setTopic] = useState('transactions');
  const [messages, setMessages] = useState([]);
  const [limit, setLimit] = useState(20);

  const loadMeta = useCallback(() => Promise.all([api.streamTopics(), api.streamGroups()])
    .then(([t, g]) => { setTopics(t); setGroups(g); setError(null); })
    .catch((e) => setError(e.message)), []);

  const loadMessages = useCallback(() => api.streamMessages(topic, limit)
    .then(setMessages)
    .catch((e) => setError(e.message)), [topic, limit]);

  // Metadata polls; the message tail does not. Offsets and lag are the numbers
  // you watch move, and they are two cheap admin calls. Re-reading the tail on a
  // timer would instead keep replacing the payload somebody is in the middle of
  // reading, which is the opposite of useful.
  useEffect(() => {
    loadMeta();
    const timer = setInterval(loadMeta, 4000);
    return () => clearInterval(timer);
  }, [loadMeta]);

  useEffect(() => { loadMessages(); }, [loadMessages]);

  if (!topics && error) {
    return <Empty>Could not reach the broker — {error}</Empty>;
  }
  if (!topics) return <Empty>Loading…</Empty>;

  const totalLag = groups.reduce((sum, g) => sum + Math.max(0, g.totalLag), 0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      <div style={{ display: 'grid', gap: 'var(--space-4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))' }}>
        {topics.map((t) => (
          <StatTile key={t.name} label={t.name}
                    value={t.messages.toLocaleString('en-ZA')}
                    sub={`${t.partitions.length} partition${t.partitions.length === 1 ? '' : 's'} · kept ${t.retention}`} />
        ))}
        <StatTile label="Consumer lag" value={totalLag.toLocaleString('en-ZA')}
                  tone={totalLag > 1000 ? 'warning' : 'success'}
                  sub={totalLag === 0 ? 'the engine is keeping up' : 'messages behind'} />
      </div>

      <Topics topics={topics} />
      <Groups groups={groups} />
      <Operations onDone={() => { loadMeta(); loadMessages(); }} />
      <Tail topic={topic} topics={topics} messages={messages} limit={limit}
            onTopic={setTopic} onLimit={setLimit} onRefresh={loadMessages} />
    </div>
  );
}

// ---- topics ----------------------------------------------------------------

function Topics({ topics }) {
  return (
    <Card title="Topics">
      <div style={{ display: 'grid', gap: 'var(--space-5)' }}>
        {topics.map((t) => (
          <div key={t.name}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-3)',
                          flexWrap: 'wrap' }}>
              <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 700 }}>{t.name}</span>
              <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
                replication {t.replication} · cleanup {t.cleanupPolicy} · retention {t.retention}
              </span>
            </div>
            <p style={{ margin: '4px 0 var(--space-3)', fontSize: 'var(--text-sm)',
                        color: 'var(--muted-fg)', maxWidth: '80ch' }}>
              {t.role}
            </p>
            <table style={{ width: '100%', borderCollapse: 'collapse',
                            fontSize: 'var(--text-sm)' }}>
              <thead>
                <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
                  <th style={th}>Partition</th>
                  <th style={th}>Leader</th>
                  <th style={{ ...th, textAlign: 'right' }}>Earliest offset</th>
                  <th style={{ ...th, textAlign: 'right' }}>Next offset</th>
                  <th style={{ ...th, textAlign: 'right' }}>Readable</th>
                </tr>
              </thead>
              <tbody>
                {t.partitions.map((p) => (
                  <tr key={p.partition} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={td}>{p.partition}</td>
                    <td style={td}>{p.leader < 0 ? 'none' : p.leader}</td>
                    <td style={num}>{p.startOffset.toLocaleString('en-ZA')}</td>
                    <td style={num}>{p.endOffset.toLocaleString('en-ZA')}</td>
                    <td style={num}>{p.messages.toLocaleString('en-ZA')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ))}
      </div>
      <p style={{ marginBottom: 0, marginTop: 'var(--space-4)',
                  fontSize: 'var(--text-xs)', color: 'var(--muted-fg)', maxWidth: '80ch' }}>
        “Readable” is the next offset minus the earliest one — what is on the topic
        now, not what has ever been produced. Once retention deletes a segment the
        two stop being the same number, which is why both offsets are shown rather
        than a single count.
      </p>
    </Card>
  );
}

// ---- consumer groups -------------------------------------------------------

function Groups({ groups }) {
  if (groups.length === 0) {
    return (
      <Card title="Consumer groups">
        <Empty>No consumer group has committed an offset yet.</Empty>
      </Card>
    );
  }

  return (
    <Card title="Consumer groups">
      {groups.map((g) => (
        <div key={g.groupId} style={{ marginBottom: 'var(--space-5)' }}>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 'var(--space-3)',
                        flexWrap: 'wrap', marginBottom: 'var(--space-3)' }}>
            <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 700 }}>{g.groupId}</span>
            <span style={{
              fontSize: 'var(--text-xs)', fontWeight: 600, padding: '2px 9px',
              borderRadius: 'var(--radius-full)',
              // STABLE is the healthy state. EMPTY means nothing is connected,
              // which on this stack means the engine is down — a red state, not
              // a neutral one, however calm the word sounds.
              background: /stable/i.test(g.state) ? 'var(--success-bg)' : 'var(--danger-bg)',
              color: /stable/i.test(g.state) ? 'var(--success-fg)' : 'var(--danger-fg)',
              border: `1px solid ${/stable/i.test(g.state) ? 'var(--success-border)' : 'var(--danger-border)'}`,
            }}>
              {String(g.state).toUpperCase()}
            </span>
            <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
              {g.members} member{g.members === 1 ? '' : 's'}
              {g.assignor ? ` · ${g.assignor} assignor` : ''}
            </span>
          </div>

          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
            <thead>
              <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
                <th style={th}>Topic</th>
                <th style={th}>Partition</th>
                <th style={{ ...th, textAlign: 'right' }}>Committed</th>
                <th style={{ ...th, textAlign: 'right' }}>Next offset</th>
                <th style={{ ...th, textAlign: 'right' }}>Lag</th>
              </tr>
            </thead>
            <tbody>
              {g.partitions.map((p) => (
                <tr key={`${p.topic}-${p.partition}`} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={{ ...td, fontFamily: 'var(--font-mono)' }}>{p.topic}</td>
                  <td style={td}>{p.partition}</td>
                  <td style={num}>
                    {p.groupOffset < 0 ? 'never' : p.groupOffset.toLocaleString('en-ZA')}
                  </td>
                  <td style={num}>{p.endOffset.toLocaleString('en-ZA')}</td>
                  <td style={{ ...num, fontWeight: 600,
                               color: p.lag < 0 ? 'var(--danger-fg)'
                                 : p.lag > 1000 ? 'var(--warning-fg)' : 'var(--success-fg)' }}>
                    {p.lag < 0 ? 'not started' : p.lag.toLocaleString('en-ZA')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
      <p style={{ marginBottom: 0, fontSize: 'var(--text-xs)',
                  color: 'var(--muted-fg)', maxWidth: '80ch' }}>
        Lag is the next offset minus what the group has committed — computed here,
        because the broker has no such field. A partition the group has never
        committed on reads “not started” rather than zero: a consumer that never
        came up should not look caught up.
      </p>
    </Card>
  );
}

// ---- the two operations ----------------------------------------------------

function Operations({ onDone }) {
  return (
    <div style={{ display: 'grid', gap: 'var(--space-5)',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))' }}>
      <Publish onDone={onDone} />
      <Redeliver onDone={onDone} />
    </div>
  );
}

function Publish({ onDone }) {
  const [draft, setDraft] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  // The starting payload comes from the API, built from the record the pipeline
  // actually reads and carrying a fresh id and a current timestamp. A hardcoded
  // example here would be wrong the first time the contract changed.
  useEffect(() => {
    api.streamTemplate()
      .then((t) => setDraft(JSON.stringify(t, null, 2)))
      .catch((e) => setError(e.message));
  }, []);

  const publish = async () => {
    setBusy(true); setError(null); setResult(null);
    try {
      setResult(await api.streamPublish(draft));
      onDone();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const reset = () => api.streamTemplate()
    .then((t) => { setDraft(JSON.stringify(t, null, 2)); setResult(null); setError(null); })
    .catch((e) => setError(e.message));

  return (
    <Card title="Publish a transaction"
          action={<Button onClick={reset} style={{ fontSize: 'var(--text-xs)', padding: '3px 10px' }}>
            New template
          </Button>}>
      <p style={{ marginTop: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
        Puts one transaction straight onto <code>transactions</code>, keyed by its
        card. The engine picks it up within a second — watch it appear under
        Transactions, and as an alert if it trips anything. The template below is
        already shaped to trip several rules at once.
      </p>

      <textarea value={draft} onChange={(e) => setDraft(e.target.value)}
                spellCheck={false} rows={14}
                style={{
                  width: '100%', resize: 'vertical', marginTop: 'var(--space-3)',
                  padding: 'var(--space-3)', borderRadius: 'var(--radius-md)',
                  border: '1px solid var(--input-border)',
                  background: 'var(--bg)', color: 'var(--fg)',
                  fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)', lineHeight: 1.55,
                }} />

      <div style={{ display: 'flex', gap: 'var(--space-3)', alignItems: 'center',
                    marginTop: 'var(--space-3)', flexWrap: 'wrap' }}>
        <Button tone="primary" onClick={publish} disabled={busy || !draft}>
          {busy ? 'Publishing…' : 'Publish to transactions'}
        </Button>
        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
          Sent verbatim — what lands on the topic is this text.
        </span>
      </div>

      {error && <Problem>{error}</Problem>}
      {result && (
        <div style={{ marginTop: 'var(--space-3)' }}>
          <Note>
            Published under key <code>{result.key}</code> ({result.bytes} bytes).
            {result.transactionId
              ? <> Transaction id <code>{result.transactionId}</code>.</>
              : <> No id was supplied, so the engine will mint one — which also means
                   a redelivery of this message cannot be recognised as a repeat.</>}
          </Note>
        </div>
      )}
    </Card>
  );
}

function Redeliver({ onDone }) {
  const [count, setCount] = useState(10);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [armed, setArmed] = useState(false);
  const [before, setBefore] = useState(null);
  const [after, setAfter] = useState(null);

  const run = async () => {
    if (!armed) { setArmed(true); return; }
    setArmed(false); setBusy(true); setError(null); setResult(null); setAfter(null);
    try {
      // The totals either side are the whole evidence. Taken from the same
      // endpoint the dashboard uses, so this is not a special measurement made
      // to flatter the result.
      const start = await api.dashboard();
      setBefore(start);
      const outcome = await api.streamRedeliver(Number(count));
      setResult(outcome);
      // The engine has to consume the copies before the counts mean anything.
      // Two seconds is generous at any rate this stack runs at, and the numbers
      // are shown either way rather than being asserted.
      setTimeout(() => api.dashboard().then(setAfter).catch(() => {}), 2000);
      onDone();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card title="Re-deliver recent messages">
      <p style={{ marginTop: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
        Kafka delivers <em>at least</em> once. A rebalance, or a crash between
        processing a record and committing its offset, and the same transaction
        arrives twice. This makes that happen on purpose: recent messages are
        republished byte for byte under their original keys.
      </p>
      <p style={{ fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
        The engine should skip every one of them on its primary key. The totals
        below are read before and after, from the same endpoint the dashboard uses.
      </p>

      <div style={{ display: 'flex', gap: 'var(--space-3)', alignItems: 'flex-end',
                    flexWrap: 'wrap', marginTop: 'var(--space-4)' }}>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 4,
                        fontSize: 'var(--text-xs)', fontWeight: 600 }}>
          How many
          <input type="number" min={1} max={100} value={count}
                 onChange={(e) => setCount(e.target.value)}
                 style={{ ...control, width: 90 }} />
        </label>
        <Button tone={armed ? 'danger' : 'neutral'} onClick={run} disabled={busy}>
          {busy ? 'Re-delivering…' : armed ? `Yes — republish ${count}` : 'Re-deliver…'}
        </Button>
        {armed && <Button onClick={() => setArmed(false)}>Cancel</Button>}
      </div>

      {error && <Problem>{error}</Problem>}

      {result && (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <Note>{result.republished} messages republished. {result.expectation}</Note>
          {before && (
            <table style={{ width: '100%', borderCollapse: 'collapse',
                            fontSize: 'var(--text-sm)', marginTop: 'var(--space-3)' }}>
              <thead>
                <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
                  <th style={th} />
                  <th style={{ ...th, textAlign: 'right' }}>Before</th>
                  <th style={{ ...th, textAlign: 'right' }}>After</th>
                </tr>
              </thead>
              <tbody>
                <Delta label="Transactions, total" before={before.totalTransactions}
                       after={after?.totalTransactions} pending={!after} />
                <Delta label="Alerts, total" before={before.totalAlerts}
                       after={after?.totalAlerts} pending={!after} />
              </tbody>
            </table>
          )}
          <p style={{ marginBottom: 0, marginTop: 'var(--space-3)',
                      fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
            The simulator keeps producing while this runs, so “after” can be higher
            by whatever arrived in those two seconds. What would show a broken guard
            is a jump of about {result.republished} — and{' '}
            <code>transactions_redelivered_total</code> on the Metrics page rising by
            exactly that.
          </p>
        </div>
      )}
    </Card>
  );
}

function Delta({ label, before, after, pending }) {
  const moved = after != null && after !== before;
  return (
    <tr style={{ borderTop: '1px solid var(--border)' }}>
      <td style={td}>{label}</td>
      <td style={num}>{Number(before ?? 0).toLocaleString('en-ZA')}</td>
      <td style={{ ...num, fontWeight: 600, color: moved ? 'var(--warning-fg)' : 'var(--success-fg)' }}>
        {pending ? 'counting…' : Number(after ?? 0).toLocaleString('en-ZA')}
      </td>
    </tr>
  );
}

// ---- the message tail ------------------------------------------------------

function Tail({ topic, topics, messages, limit, onTopic, onLimit, onRefresh }) {
  const [open, setOpen] = useState(null);

  return (
    <Card
      title="What is on the topic"
      action={
        <div style={{ display: 'flex', gap: 'var(--space-3)', alignItems: 'center' }}>
          <select value={topic} onChange={(e) => onTopic(e.target.value)} style={control}>
            {topics.map((t) => <option key={t.name} value={t.name}>{t.name}</option>)}
          </select>
          <select value={limit} onChange={(e) => onLimit(Number(e.target.value))} style={control}>
            {[10, 20, 50, 100].map((n) => <option key={n} value={n}>last {n}</option>)}
          </select>
          <Button onClick={onRefresh} style={{ fontSize: 'var(--text-xs)', padding: '3px 10px' }}>
            Refresh
          </Button>
        </div>
      }
    >
      <p style={{ marginTop: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)',
                  maxWidth: '80ch' }}>
        Read by explicit partition assignment and seek, never by subscribing — no
        group is joined, so the engine sees no rebalance, and no offset is
        committed, so looking here cannot move anyone's position in the topic.
        Payloads are exactly as stored: note the timestamps, which are
        epoch-seconds with a nanosecond fraction on the wire, not the ISO-8601 the
        REST API returns.
      </p>

      {messages.length === 0 ? (
        <Empty>Nothing on this topic in the window read.</Empty>
      ) : (
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
          <thead>
            <tr style={{ background: 'var(--surface)', textAlign: 'left' }}>
              <th style={th}>Offset</th>
              <th style={th}>Part.</th>
              <th style={th}>Timestamp</th>
              <th style={th}>Key</th>
              <th style={{ ...th, textAlign: 'right' }}>Bytes</th>
              <th style={th} />
            </tr>
          </thead>
          <tbody>
            {messages.map((m) => {
              const id = `${m.partition}-${m.offset}`;
              return (
                <Fragment key={id}>
                  <tr style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={{ ...td, fontFamily: 'var(--font-mono)' }}>{m.offset}</td>
                    <td style={td}>{m.partition}</td>
                    <td style={td}>{new Date(m.timestamp).toLocaleTimeString('en-ZA')}</td>
                    <td style={{ ...td, fontFamily: 'var(--font-mono)' }}>{m.key ?? '—'}</td>
                    <td style={num}>{m.sizeBytes}</td>
                    <td style={{ ...td, textAlign: 'right' }}>
                      <Button onClick={() => setOpen(open === id ? null : id)}
                              style={{ fontSize: 'var(--text-xs)', padding: '2px 9px' }}>
                        {open === id ? 'Hide' : 'Payload'}
                      </Button>
                    </td>
                  </tr>
                  {open === id && (
                    <tr>
                      <td colSpan={6} style={{ padding: '0 var(--space-3) var(--space-3)' }}>
                        <div style={{ display: 'flex', justifyContent: 'flex-end',
                                      marginBottom: 6 }}>
                          <CopyButton text={m.value ?? ''} />
                        </div>
                        <Code maxHeight={260}>{prettyOrRaw(m.value)}</Code>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      )}
    </Card>
  );
}

/** Indent JSON for reading, and leave anything else untouched. */
function prettyOrRaw(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text ?? '(no payload)';
  }
}

function Note({ children }) {
  return (
    <p style={{ margin: 0, padding: 'var(--space-3)', fontSize: 'var(--text-sm)',
                background: 'var(--success-bg)', color: 'var(--success-fg)',
                border: '1px solid var(--success-border)', borderRadius: 'var(--radius-md)' }}>
      {children}
    </p>
  );
}

function Problem({ children }) {
  return (
    <p style={{ margin: 'var(--space-3) 0 0', padding: 'var(--space-3)',
                fontSize: 'var(--text-sm)',
                background: 'var(--danger-bg)', color: 'var(--danger-fg)',
                border: '1px solid var(--danger-border)', borderRadius: 'var(--radius-md)' }}>
      {children}
    </p>
  );
}

const th = { padding: 'var(--row-pad)', fontWeight: 600, whiteSpace: 'nowrap' };
const td = { padding: 'var(--row-pad)', verticalAlign: 'top' };
const num = { ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' };
const control = {
  height: 30, borderRadius: 'var(--radius-md)', border: '1px solid var(--input-border)',
  background: 'var(--bg)', color: 'var(--fg)', fontSize: 'var(--text-xs)', padding: '0 8px',
};
