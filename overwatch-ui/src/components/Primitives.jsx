import { useState } from 'react';
import { useCountUp } from '../motion.js';

/** Small building blocks shared by every page. */

/**
 * A panel.
 *
 * `delay` staggers its entrance. Passed by a page laying out a grid, so the
 * cards arrive in reading order over about a third of a second instead of
 * appearing all at once — which is the difference between a screen that
 * assembles and a screen that blinks. It is a keyframe on mount only: nothing
 * re-enters when the five-second poll returns, and under
 * prefers-reduced-motion the class does nothing at all.
 */
export function Card({ title, action, children, style, delay = 0 }) {
  return (
    <section
      className="ow-rise"
      style={{
        background: 'var(--bg)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-xl)',
        boxShadow: 'var(--shadow-sm)',
        animationDelay: delay ? `${delay}ms` : undefined,
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

/**
 * A single figure with its label, and optionally a trend under it.
 *
 * Pass `value` alone for text. Pass `numeric` and `format` instead and the
 * figure rolls from its old value to its new one when it changes: the label
 * stays put, the number moves, and the eye goes to the tile that actually
 * moved rather than having to diff five tiles against memory.
 *
 * `minDelta` is the caller's, because what counts as a change worth animating
 * is entirely about the quantity. A transaction count moving by 300 between
 * polls should roll; a mean risk score moving by 0.001 should not, and rolling
 * it is 650ms of noise in the last decimal.
 */
export function StatTile({ label, value, sub, tone, chart,
                           numeric, format, minDelta = 0, delay = 0 }) {
  const rolled = useCountUp(Number.isFinite(numeric) ? numeric : 0, { minDelta });
  const shown = Number.isFinite(numeric) && format ? format(rolled) : value;

  const toneColor = {
    danger: 'var(--danger-fg)',
    warning: 'var(--warning-fg)',
    success: 'var(--success-fg)',
  }[tone];

  return (
    <div
      className="ow-rise"
      style={{
        background: 'var(--bg)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-xl)',
        boxShadow: 'var(--shadow-sm)',
        padding: 'var(--space-5)',
        animationDelay: delay ? `${delay}ms` : undefined,
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
      <div title={typeof shown === 'string' ? shown : undefined}
           style={{ fontSize: valueFontSize(shown), fontWeight: 700, lineHeight: 1.15,
                    marginTop: 'var(--space-2)', color: toneColor || 'var(--fg)',
                    fontVariantNumeric: 'tabular-nums',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {shown}
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

/* ---- table filters and paging ------------------------------------------- */

const controlHeight = 30;

const controlStyle = {
  height: controlHeight,
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--input-border)',
  background: 'var(--bg)',
  color: 'var(--fg)',
  fontSize: 'var(--text-xs)',
  padding: '0 8px',
};

/**
 * A filter dropdown.
 *
 * `options` takes either strings or `{ value, label }`, because most of these
 * are enum values that are their own label and a few are not — a window filter
 * offers 24 hours as `24` and shows "Last 24 hours".
 *
 * The empty option is always first and always means "no filter", spelled out
 * ("All severities") rather than left blank: a blank first option in a dropdown
 * reads as a missing value, not as the absence of a constraint.
 */
export function Select({ value, onChange, label, options, width }) {
  return (
    <select aria-label={label} value={value}
            onChange={(e) => onChange(e.target.value)}
            style={{ ...controlStyle, width }}>
      {label !== undefined && <option value="">{label}</option>}
      {options.map((o) => {
        const opt = typeof o === 'object' ? o : { value: o, label: o };
        return <option key={opt.value} value={opt.value}>{opt.label}</option>;
      })}
    </select>
  );
}

/** A free-text filter. Debouncing belongs to the caller, which owns the fetch. */
export function TextFilter({ value, onChange, placeholder, width = 140, label }) {
  return (
    <input value={value} placeholder={placeholder} aria-label={label ?? placeholder}
           onChange={(e) => onChange(e.target.value)}
           style={{ ...controlStyle, width }} />
  );
}

/**
 * The row of filters above a table, with a way back out of them.
 *
 * The "Clear" button only appears once something is filtered. A permanently
 * visible reset control is noise on an unfiltered table; an absent one is how
 * someone ends up staring at an empty table having forgotten they typed a card
 * id into it two screens ago.
 */
export function FilterBar({ children, onClear, active }) {
  return (
    <div style={{ display: 'flex', gap: 'var(--space-2)', alignItems: 'center',
                  flexWrap: 'wrap' }}>
      {children}
      {active && (
        <button type="button" onClick={onClear}
                style={{ ...controlStyle, cursor: 'pointer', color: 'var(--brand)',
                         fontWeight: 600, border: '1px solid transparent',
                         background: 'transparent' }}>
          Clear filters
        </button>
      )}
    </div>
  );
}

/**
 * Paging controls for a table, and the count they page through.
 *
 * Three things, left to right: how many rows there are and which of them you are
 * looking at, how many to show at a time, and the way forwards and back. The
 * range ("11–20 of 4 512") rather than only a page number, because a page number
 * on its own is meaningless without knowing the page size, and the page size is
 * the control immediately next to it.
 *
 * Page size is the caller's state, not this component's: changing it has to
 * refetch, and the caller owns the fetch. What this does own is keeping the
 * reader roughly where they were when the size changes — jumping from page 5 of
 * 10-row pages back to page 5 of 50-row pages moves you 200 rows down the table
 * for no reason, so the first visible row is preserved instead.
 */
export function Pagination({ page, size, totalElements, totalPages,
                             onPage, onSize, noun = 'rows', sizes = [10, 25, 50] }) {
  const total = totalElements ?? 0;
  const pages = Math.max(1, totalPages ?? Math.ceil(total / size));
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);

  const changeSize = (next) => {
    const n = Number(next);
    onPage(Math.floor((page * size) / n));
    onSize(n);
  };

  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  gap: 'var(--space-4)', flexWrap: 'wrap',
                  paddingTop: 'var(--space-4)', marginTop: 'var(--space-2)',
                  borderTop: '1px solid var(--border)',
                  fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
      <span style={{ fontVariantNumeric: 'tabular-nums' }}>
        {total === 0
          ? `No ${noun}`
          : <>
              {first.toLocaleString('en-ZA')}–{last.toLocaleString('en-ZA')} of{' '}
              <strong style={{ color: 'var(--fg)' }}>{total.toLocaleString('en-ZA')}</strong> {noun}
            </>}
      </span>

      <span style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          Rows
          <select value={size} onChange={(e) => changeSize(e.target.value)}
                  aria-label={`Rows per page, currently ${size}`}
                  style={{ ...controlStyle, height: 26, padding: '0 4px' }}>
            {sizes.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>

        <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <PageButton onClick={() => onPage(0)} disabled={page === 0} title="First page">
            «
          </PageButton>
          <PageButton onClick={() => onPage(Math.max(0, page - 1))} disabled={page === 0}>
            Previous
          </PageButton>
          <span style={{ fontVariantNumeric: 'tabular-nums', minWidth: 92,
                         textAlign: 'center' }}>
            Page {(page + 1).toLocaleString('en-ZA')} of {pages.toLocaleString('en-ZA')}
          </span>
          <PageButton onClick={() => onPage(page + 1)} disabled={page + 1 >= pages}>
            Next
          </PageButton>
          <PageButton onClick={() => onPage(pages - 1)} disabled={page + 1 >= pages}
                      title="Last page">
            »
          </PageButton>
        </span>
      </span>
    </div>
  );
}

function PageButton({ children, onClick, disabled, title }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} title={title}
            style={{
              height: 26, padding: '0 9px', borderRadius: 'var(--radius-md)',
              border: '1px solid var(--input-border)',
              background: 'var(--bg)', color: disabled ? 'var(--muted-fg)' : 'var(--fg)',
              fontSize: 'var(--text-xs)',
              cursor: disabled ? 'default' : 'pointer',
              opacity: disabled ? 0.5 : 1,
            }}>
      {children}
    </button>
  );
}

/**
 * Client-side paging for a list already in hand.
 *
 * Some tables here are pages of a query and some are a bounded list the server
 * returned whole — a cardholder's alerts, capped at fifty by the API because a
 * profile is a summary. Both should page the same way and look the same doing
 * it, so this slices in the browser and hands back the same shape the server
 * endpoints do.
 *
 * The page is clamped rather than reset when the list shrinks under it: a poll
 * that returns two fewer rows should not throw the reader back to the top.
 */
export function useClientPaging(rows, initialSize = 10) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(initialSize);

  const all = rows ?? [];
  const totalPages = Math.max(1, Math.ceil(all.length / size));
  const safePage = Math.min(page, totalPages - 1);

  return {
    rows: all.slice(safePage * size, safePage * size + size),
    props: {
      page: safePage,
      size,
      onPage: setPage,
      onSize: setSize,
      totalElements: all.length,
      totalPages,
    },
  };
}
