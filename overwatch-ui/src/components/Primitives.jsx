import { useState } from 'react';

/** Small building blocks shared by every page. */

export function Card({ title, action, children, style }) {
  return (
    <section
      style={{
        background: 'var(--bg)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-xl)',
        boxShadow: 'var(--shadow-sm)',
        ...style,
      }}
    >
      {title && (
        <header
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: 'var(--space-4) var(--space-5)',
            borderBottom: '1px solid var(--border)',
          }}
        >
          <h2 style={{ margin: 0, fontSize: 'var(--text-lg)', fontWeight: 600 }}>{title}</h2>
          {action}
        </header>
      )}
      <div style={{ padding: 'var(--space-5)' }}>{children}</div>
    </section>
  );
}

/**
 * A segmented control for picking one option from a short list — the dashboard's
 * time range, and the Metrics page's.
 *
 * A row of buttons rather than a `<select>`: there are seven options, they are two
 * or three characters each, and the whole point of a time filter is that switching
 * between neighbouring windows is one click. A dropdown makes comparing 15m to 30m
 * a four-interaction round trip. Rendered as a radiogroup so the arrow keys work
 * and a screen reader announces which window is current.
 */
export function SegmentedControl({ options, value, onChange, label }) {
  return (
    <div role="radiogroup" aria-label={label}
         style={{
           display: 'inline-flex', gap: 2, padding: 2,
           background: 'var(--surface)', border: '1px solid var(--border)',
           borderRadius: 'var(--radius-md)',
         }}>
      {options.map((opt) => {
        const active = opt.value === value;
        return (
          <button key={opt.value} type="button" role="radio" aria-checked={active}
                  onClick={() => onChange(opt.value)}
                  title={opt.title}
                  style={{
                    appearance: 'none', cursor: 'pointer',
                    padding: '3px 10px', borderRadius: 'calc(var(--radius-md) - 2px)',
                    border: '1px solid transparent',
                    background: active ? 'var(--bg)' : 'transparent',
                    borderColor: active ? 'var(--border)' : 'transparent',
                    boxShadow: active ? 'var(--shadow-sm)' : 'none',
                    color: active ? 'var(--fg)' : 'var(--muted-fg)',
                    fontWeight: active ? 600 : 400,
                    fontSize: 'var(--text-sm)',
                    fontVariantNumeric: 'tabular-nums',
                  }}>
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}

/**
 * A stat tile carries a single figure. Per the form heuristic, one number is not
 * a chart — a tile says it faster and more accurately than any plot would.
 */
/**
 * Headline figures step down as they lengthen, so a tile shows the whole number
 * rather than a truncated one. Thresholds are character counts because the font
 * is tabular here — every digit is the same width, so length predicts width.
 */
function valueFontSize(value) {
  const len = String(value ?? '').length;
  if (len <= 9) return 'var(--text-2xl)';   // 28px — "R9 999.99", "27 638"
  if (len <= 12) return 'var(--text-xl)';   // 20px — "R1 234 567"
  return 'var(--text-lg)';                  // 16px — "R68 343 795.35"
}

export function StatTile({ label, value, sub, tone, chart }) {
  const toneColor = {
    danger: 'var(--danger-fg)',
    warning: 'var(--warning-fg)',
    success: 'var(--success-fg)',
  }[tone];

  return (
    <div
      style={{
        background: 'var(--bg)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-xl)',
        boxShadow: 'var(--shadow-sm)',
        padding: 'var(--space-5)',
      }}
    >
      <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                    textTransform: 'uppercase', letterSpacing: '.05em', fontWeight: 600 }}>
        {label}
      </div>
      {/* Long figures step down a size rather than overflowing the tile.
          "R68 343 795.35" is 14 characters and does not fit a 190px tile at 28px,
          and the group separator is a non-breaking space precisely so an amount
          never wraps mid-number — which left clipping as the failure mode. A
          clipped currency figure is worse than a smaller one: "R68 343 795.3" is
          not a wrong-looking number, it is a plausible one that is wrong. */}
      <div title={typeof value === 'string' ? value : undefined}
           style={{ fontSize: valueFontSize(value), fontWeight: 700, lineHeight: 1.15,
                    marginTop: 'var(--space-2)', color: toneColor || 'var(--fg)',
                    fontVariantNumeric: 'tabular-nums',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {value}
      </div>
      {sub && (
        <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)', marginTop: 4 }}>
          {sub}
        </div>
      )}
      {/* Optional trend, under the figure rather than behind it. A sparkline
          drawn as a tile background makes the number harder to read to say
          something the number does not; below it, both stay legible. */}
      {chart && <div style={{ marginTop: 'var(--space-3)' }}>{chart}</div>}
    </div>
  );
}

/** Severity always ships with its label — never colour alone. */
export function SeverityBadge({ severity }) {
  const bg = {
    LOW: 'var(--sev-low)', MEDIUM: 'var(--sev-medium)',
    HIGH: 'var(--sev-high)', CRITICAL: 'var(--sev-critical)',
  }[severity] || 'var(--muted-fg)';

  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      fontSize: 'var(--text-xs)', fontWeight: 600, letterSpacing: '.02em',
    }}>
      <span aria-hidden="true" style={{
        width: 8, height: 8, borderRadius: 'var(--radius-full)', background: bg,
      }} />
      {severity}
    </span>
  );
}

export function StatusBadge({ status }) {
  const style = {
    OPEN: { bg: 'var(--danger-bg)', fg: 'var(--danger-fg)', bd: 'var(--danger-border)' },
    REVIEWING: { bg: 'var(--warning-bg)', fg: 'var(--warning-fg)', bd: 'var(--warning-border)' },
    CONFIRMED: { bg: 'var(--danger-bg)', fg: 'var(--danger-fg)', bd: 'var(--danger-border)' },
    CLEARED: { bg: 'var(--success-bg)', fg: 'var(--success-fg)', bd: 'var(--success-border)' },
    ENABLED: { bg: 'var(--success-bg)', fg: 'var(--success-fg)', bd: 'var(--success-border)' },
    DISABLED: { bg: 'var(--surface)', fg: 'var(--muted-fg)', bd: 'var(--border)' },
    SHADOW: { bg: 'var(--info-bg)', fg: 'var(--info-fg)', bd: 'var(--info-border)' },
  }[status] || { bg: 'var(--surface)', fg: 'var(--muted-fg)', bd: 'var(--border)' };

  return (
    <span style={{
      display: 'inline-block', padding: '2px 8px',
      borderRadius: 'var(--radius-full)', fontSize: 'var(--text-xs)', fontWeight: 600,
      background: style.bg, color: style.fg, border: `1px solid ${style.bd}`,
    }}>
      {status}
    </span>
  );
}

export function Empty({ children }) {
  return (
    <div style={{ padding: 'var(--space-8)', textAlign: 'center', color: 'var(--muted-fg)' }}>
      {children}
    </div>
  );
}

/**
 * The one button style this dashboard has.
 *
 * Lived on the Simulator page until three screens needed it. `tone` covers the
 * three things a button can mean here: the ordinary action, the one action a
 * screen is primarily for, and an action that writes to something destructive
 * enough to warrant looking different before you click it.
 */
export function Button({ children, onClick, disabled, tone = 'neutral', title, style }) {
  const palette = {
    primary: { bg: 'var(--brand)', fg: 'var(--brand-fg)', bd: 'var(--brand)' },
    danger: { bg: 'var(--danger-bg)', fg: 'var(--danger-fg)', bd: 'var(--danger-border)' },
    neutral: { bg: 'var(--bg)', fg: 'var(--fg)', bd: 'var(--border)' },
  }[tone] || { bg: 'var(--bg)', fg: 'var(--fg)', bd: 'var(--border)' };

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      style={{
        font: 'inherit', fontSize: 'var(--text-sm)', fontWeight: 600,
        padding: '6px 14px', borderRadius: 'var(--radius-md)',
        cursor: disabled ? 'default' : 'pointer',
        opacity: disabled ? 0.55 : 1,
        background: palette.bg, color: palette.fg,
        border: `1px solid ${palette.bd}`,
        whiteSpace: 'nowrap',
        ...style,
      }}
    >
      {children}
    </button>
  );
}

/**
 * Copies text to the clipboard and says so.
 *
 * The confirmation is the point. Without it a copy button is indistinguishable
 * from a broken one — nothing on screen changes either way, so people click it
 * three times and then select the text by hand anyway.
 *
 * `navigator.clipboard` needs a secure context, which http://localhost is but
 * http://<lan-ip> is not. So the failure is handled rather than assumed away:
 * the label says so, and the text is still selectable underneath.
 */
export function CopyButton({ text, label = 'Copy' }) {
  const [state, setState] = useState('idle');

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setState('done');
    } catch {
      setState('failed');
    }
    setTimeout(() => setState('idle'), 2000);
  };

  return (
    <Button onClick={copy} title="Copy to clipboard"
            style={{ fontSize: 'var(--text-xs)', padding: '3px 10px' }}>
      {state === 'done' ? 'Copied' : state === 'failed' ? 'Select it instead' : label}
    </Button>
  );
}

/**
 * A monospaced block for something machine-shaped — a payload, a URL, a curl
 * line.
 *
 * Scrolls on its own rather than widening the page: a 300-character request URL
 * must not put a horizontal scrollbar under the whole layout.
 */
export function Code({ children, maxHeight = 320, style }) {
  return (
    <pre style={{
      margin: 0, padding: 'var(--space-3)',
      background: 'var(--surface)', border: '1px solid var(--border)',
      borderRadius: 'var(--radius-md)',
      fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)',
      lineHeight: 1.55, color: 'var(--fg)',
      maxHeight, overflow: 'auto',
      whiteSpace: 'pre-wrap', wordBreak: 'break-word',
      ...style,
    }}>
      {children}
    </pre>
  );
}

/**
 * An HTTP method, coloured by how much damage it can do.
 *
 * GET reads, PATCH changes one field, POST here either creates or destroys. The
 * colours are the status tokens rather than a new palette, and the method name
 * is always present — this is a label with a background, not a colour code.
 */
export function MethodBadge({ method }) {
  const tone = {
    GET: { bg: 'var(--info-bg)', fg: 'var(--info-fg)', bd: 'var(--info-border)' },
    POST: { bg: 'var(--warning-bg)', fg: 'var(--warning-fg)', bd: 'var(--warning-border)' },
    PATCH: { bg: 'var(--brand-20)', fg: 'var(--brand)', bd: 'var(--brand-40)' },
    DELETE: { bg: 'var(--danger-bg)', fg: 'var(--danger-fg)', bd: 'var(--danger-border)' },
  }[method] || { bg: 'var(--surface)', fg: 'var(--muted-fg)', bd: 'var(--border)' };

  return (
    <span style={{
      display: 'inline-block', minWidth: 52, textAlign: 'center',
      padding: '2px 7px', borderRadius: 'var(--radius-sm)',
      fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)', fontWeight: 700,
      background: tone.bg, color: tone.fg, border: `1px solid ${tone.bd}`,
    }}>
      {method}
    </span>
  );
}
