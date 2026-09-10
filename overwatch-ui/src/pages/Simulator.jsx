import { useCallback, useEffect, useRef, useState } from 'react';
import { api, PATTERN_COPY } from '../api.js';
import { Button, Card, StatTile, Empty } from '../components/Primitives.jsx';

/**
 * Runtime control over the transaction stream.
 *
 * The stack produces its own traffic, which means a reviewer normally has to wait
 * for something interesting to happen. This screen removes the waiting: slow the
 * stream to a trickle and every alert is one you can follow to its rule, or inject
 * a specific pattern and watch that rule fire within a second.
 */
export default function Simulator() {
  const [status, setStatus] = useState(null);
  const [error, setError] = useState(null);
  const [note, setNote] = useState(null);
  const [busy, setBusy] = useState(false);

  // Slider positions are held locally while the pointer is down, so a poll
  // landing mid-drag cannot yank the handle back to the server's value.
  const [rate, setRate] = useState(0);
  const [fraud, setFraud] = useState(0);
  const dragging = useRef(false);

  const apply = useCallback((next) => {
    setStatus(next);
    setError(null);
    if (!dragging.current) {
      setRate(next.transactionsPerSecond ?? 0);
      setFraud(Math.round((next.fraudInjectionRate ?? 0) * 100));
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    const load = () => api.simulatorStatus()
      .then((s) => { if (!cancelled) apply(s); })
      .catch((e) => { if (!cancelled) setError(e.message); });
    load();
    const timer = setInterval(load, 2000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [apply]);

  const run = async (action, message) => {
    setBusy(true);
    try {
      const next = await action();
      if (next && next.running !== undefined) apply(next);
      if (message) flash(message);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const flashTimer = useRef(null);
  const flash = (message) => {
    setNote(message);
    clearTimeout(flashTimer.current);
    flashTimer.current = setTimeout(() => setNote(null), 4000);
  };
  useEffect(() => () => clearTimeout(flashTimer.current), []);

  if (!status && error) {
    return <Empty>Could not reach the simulator — {error}</Empty>;
  }
  if (!status) {
    return <Empty>Loading…</Empty>;
  }

  const running = Boolean(status.running);
  const published = Number(status.published ?? 0);
  const fraudPublished = Number(status.fraudPublished ?? 0);
  const patterns = status.availablePatterns ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      <Card
        title="Stream"
        action={
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)' }}>
            <RunPill running={running} />
            <Button
              tone={running ? 'neutral' : 'primary'}
              disabled={busy}
              onClick={() => run(running ? api.simulatorPause : api.simulatorStart,
                                running ? 'Stream paused.' : 'Stream running.')}
            >
              {running ? 'Pause' : 'Start'}
            </Button>
          </div>
        }
      >
        <div style={{ display: 'grid', gap: 'var(--space-4)',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))' }}>
          <StatTile label="Published" value={published.toLocaleString('en-ZA')}
                    sub="transactions since start" />
          <StatTile label="Shaped as fraud" value={fraudPublished.toLocaleString('en-ZA')}
                    sub={published > 0
                      ? `${((fraudPublished / published) * 100).toFixed(1)}% of the stream`
                      : '—'} />
          <StatTile label="Throughput"
                    value={`${status.transactionsPerSecond ?? 0}/s`}
                    tone={running ? undefined : 'warning'}
                    sub={running ? 'currently publishing' : 'paused — nothing is publishing'} />
          <StatTile label="Fraud injection"
                    value={`${Math.round((status.fraudInjectionRate ?? 0) * 100)}%`}
                    sub="of generated traffic" />
        </div>

        <div style={{ display: 'grid', gap: 'var(--space-6)', marginTop: 'var(--space-6)',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))' }}>
          <Slider
            label="Transactions per second"
            value={rate} min={0} max={500} step={1}
            display={`${rate}/s`}
            hint="Drop this to 1 or 2 to read individual alerts as they land; push it up to watch the pipeline under load."
            onDrag={(v) => { dragging.current = true; setRate(v); }}
            onCommit={(v) => { dragging.current = false; run(() => api.simulatorRate(v)); }}
          />
          <Slider
            label="Fraud injection"
            value={fraud} min={0} max={100} step={1}
            display={`${fraud}%`}
            hint="Real card fraud sits well under 1%. The default of 8% keeps the alert list interesting without making it meaningless."
            onDrag={(v) => { dragging.current = true; setFraud(v); }}
            onCommit={(v) => { dragging.current = false; run(() => api.simulatorFraudRate(v / 100)); }}
          />
        </div>
      </Card>

      <Card title="Inject a pattern">
        <p style={{ margin: '0 0 var(--space-5)', fontSize: 'var(--text-sm)',
                    color: 'var(--muted-fg)', maxWidth: '68ch' }}>
          Each pattern is shaped to trip a specific rule. Press one and the alert
          appears on the dashboard about a second later — publishing, consumption,
          evaluation, scoring and persistence, all of it, in the time it takes to
          switch tabs.
        </p>

        <div style={{ display: 'grid', gap: 'var(--space-4)',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))' }}>
          {patterns.map((p) => {
            const copy = PATTERN_COPY[p] ?? { label: p, rule: p, blurb: '' };
            const compound = p === 'COMPOUND';
            return (
              <div key={p} style={{
                border: `1px solid ${compound ? 'var(--brand-40)' : 'var(--border)'}`,
                background: compound ? 'var(--info-bg)' : 'var(--canvas)',
                borderRadius: 'var(--radius)', padding: 'var(--space-4)',
                display: 'flex', flexDirection: 'column', gap: 'var(--space-2)',
              }}>
                <div style={{ fontWeight: 600 }}>{copy.label}</div>
                <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                              flex: 1 }}>
                  {copy.blurb}
                </div>
                <div style={{ display: 'flex', alignItems: 'center',
                              justifyContent: 'space-between', gap: 'var(--space-3)',
                              marginTop: 'var(--space-2)' }}>
                  <code style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)',
                                 color: 'var(--muted-fg)' }}>
                    {copy.rule}
                  </code>
                  <Button
                    tone={compound ? 'primary' : 'neutral'}
                    disabled={busy}
                    onClick={() => run(
                      () => api.simulatorInject(p),
                      `Injected ${copy.label} — check Alerts in a moment.`)}
                  >
                    Inject
                  </Button>
                </div>
              </div>
            );
          })}
        </div>
      </Card>

      {(note || error) && (
        <div role="status" style={{
          padding: 'var(--space-3) var(--space-4)', borderRadius: 'var(--radius)',
          fontSize: 'var(--text-sm)',
          background: error ? 'var(--danger-bg)' : 'var(--success-bg)',
          color: error ? 'var(--danger-fg)' : 'var(--success-fg)',
          border: `1px solid ${error ? 'var(--danger-border)' : 'var(--success-border)'}`,
        }}>
          {error || note}
        </div>
      )}
    </div>
  );
}

/** Run state reads as shape and word, not colour alone. */
function RunPill({ running }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '2px 10px', borderRadius: 'var(--radius-full)',
      fontSize: 'var(--text-xs)', fontWeight: 600,
      background: running ? 'var(--success-bg)' : 'var(--surface)',
      color: running ? 'var(--success-fg)' : 'var(--muted-fg)',
      border: `1px solid ${running ? 'var(--success-border)' : 'var(--border)'}`,
    }}>
      <span aria-hidden="true" style={{
        width: 7, height: 7, borderRadius: 'var(--radius-full)',
        background: 'currentColor',
      }} />
      {running ? 'Running' : 'Paused'}
    </span>
  );
}

function Slider({ label, value, min, max, step, display, hint, onDrag, onCommit }) {
  // Pointer-up, key-up and blur all end a drag, and on a mouse all three can fire
  // for one gesture. Remembering the last committed value keeps that one gesture
  // to one request.
  const committed = useRef(value);
  const commit = (next) => {
    if (next === committed.current) return;
    committed.current = next;
    onCommit(next);
  };

  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
      <span style={{ display: 'flex', justifyContent: 'space-between',
                     alignItems: 'baseline', gap: 'var(--space-3)' }}>
        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                       textTransform: 'uppercase', letterSpacing: '.05em',
                       fontWeight: 600 }}>
          {label}
        </span>
        <span style={{ fontSize: 'var(--text-lg)', fontWeight: 700,
                       fontVariantNumeric: 'tabular-nums' }}>
          {display}
        </span>
      </span>
      <input
        type="range"
        min={min} max={max} step={step} value={value}
        onChange={(e) => onDrag(Number(e.target.value))}
        onPointerUp={(e) => commit(Number(e.currentTarget.value))}
        onKeyUp={(e) => commit(Number(e.currentTarget.value))}
        onBlur={(e) => commit(Number(e.currentTarget.value))}
        style={{ width: '100%', accentColor: 'var(--brand)' }}
      />
      {hint && (
        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>{hint}</span>
      )}
    </label>
  );
}
