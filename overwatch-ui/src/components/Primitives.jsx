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
 * A stat tile carries a single figure. Per the form heuristic, one number is not
 * a chart — a tile says it faster and more accurately than any plot would.
 */
export function StatTile({ label, value, sub, tone }) {
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
      <div style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, lineHeight: 1.15,
                    marginTop: 'var(--space-2)', color: toneColor || 'var(--fg)' }}>
        {value}
      </div>
      {sub && (
        <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)', marginTop: 4 }}>
          {sub}
        </div>
      )}
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
