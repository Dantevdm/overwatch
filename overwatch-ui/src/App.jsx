/**
 * Application shell. Phase 6 fills in the Dashboard, Transactions, Alerts, Rules
 * and Metrics pages behind this navigation; the shell and the design tokens are
 * in place from the start so those pages have somewhere to land.
 */
const NAV = ['Dashboard', 'Transactions', 'Alerts', 'Rules', 'Metrics'];

export default function App() {
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <aside
        style={{
          width: 'var(--sidebar-w)',
          background: 'var(--side-bg)',
          color: 'var(--side-fg)',
          flexShrink: 0,
        }}
      >
        <div
          style={{
            height: 'var(--topbar-h)',
            display: 'flex',
            alignItems: 'center',
            gap: 'var(--space-2)',
            padding: '0 var(--space-4)',
            borderBottom: '1px solid var(--side-border)',
            fontWeight: 700,
            letterSpacing: '-.01em',
          }}
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M12 2 4 5.5v6c0 4.6 3.4 8.9 8 10.5 4.6-1.6 8-5.9 8-10.5v-6L12 2Z"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinejoin="round"
            />
          </svg>
          Overwatch
        </div>
        <nav style={{ padding: 'var(--space-2)' }}>
          {NAV.map((item, i) => (
            <a
              key={item}
              href="#"
              style={{
                display: 'block',
                padding: 'var(--row-pad)',
                borderRadius: 'var(--radius-md)',
                color: 'var(--side-fg)',
                textDecoration: 'none',
                fontSize: 'var(--text-base)',
                background: i === 0 ? 'var(--side-active)' : 'transparent',
              }}
            >
              {item}
            </a>
          ))}
        </nav>
      </aside>

      <main style={{ flex: 1, background: 'var(--canvas)' }}>
        <header
          style={{
            height: 'var(--topbar-h)',
            display: 'flex',
            alignItems: 'center',
            padding: '0 var(--space-6)',
            background: 'var(--bg)',
            borderBottom: '1px solid var(--border)',
            fontWeight: 600,
          }}
        >
          Dashboard
        </header>
        <div style={{ padding: 'var(--space-6)' }}>
          <div
            style={{
              background: 'var(--bg)',
              border: '1px solid var(--border)',
              borderRadius: 'var(--radius-xl)',
              boxShadow: 'var(--shadow-sm)',
              padding: 'var(--space-6)',
            }}
          >
            <h1 style={{ margin: 0, fontSize: 'var(--text-xl)' }}>Overwatch</h1>
            <p style={{ color: 'var(--muted-fg)', marginBottom: 0 }}>
              Fraud rule engine. Scaffolding in place — dashboard pages arrive in Phase 6.
            </p>
          </div>
        </div>
      </main>
    </div>
  );
}
