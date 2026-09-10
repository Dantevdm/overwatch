import { useEffect, useState } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom';
import Dashboard from './pages/Dashboard.jsx';
import Alerts from './pages/Alerts.jsx';
import Transactions from './pages/Transactions.jsx';
import Customers from './pages/Customers.jsx';
import Rules from './pages/Rules.jsx';
import Simulator from './pages/Simulator.jsx';
import Metrics from './pages/Metrics.jsx';
import Streams from './pages/Streams.jsx';
import ApiExplorer from './pages/ApiExplorer.jsx';
import ResetData from './components/ResetData.jsx';
import ExternalTools from './components/ExternalTools.jsx';
import {
  IconAlerts, IconApi, IconCardholders, IconDashboard, IconMetrics,
  IconRules, IconSimulator, IconStreams, IconTransactions,
} from './components/Icons.jsx';
import { api } from './api.js';

/**
 * The navigation, grouped by what you came here to do.
 *
 * Nine flat links is a list you read every time rather than a place you know
 * your way around — nothing tells you that Cardholders and Transactions answer
 * the same kind of question, or that Streams and Metrics are about the plumbing
 * rather than about fraud. The groups are the four reasons to open this app:
 * watch it, investigate something, change how it behaves, or look underneath it.
 *
 * Order within a group is by how often it is opened, not alphabetically.
 *
 * Each item carries an icon. With four headings and nine links the headings do
 * the grouping and the icons do the picking-out: you stop reading the list and
 * start aiming at a shape. They are deliberately not a substitute for the label
 * — an icon alone is a guessing game, and a fraud console is not the place to
 * make someone guess which button clears the store.
 */
const NAV = [
  {
    heading: 'Monitor',
    items: [
      { to: '/dashboard', label: 'Dashboard', icon: IconDashboard },
      { to: '/alerts', label: 'Alerts', icon: IconAlerts },
    ],
  },
  {
    heading: 'Investigate',
    items: [
      { to: '/transactions', label: 'Transactions', icon: IconTransactions },
      { to: '/customers', label: 'Cardholders', icon: IconCardholders },
    ],
  },
  {
    heading: 'Configure',
    items: [
      { to: '/rules', label: 'Rules', icon: IconRules },
      { to: '/simulator', label: 'Simulator', icon: IconSimulator },
    ],
  },
  {
    heading: 'Platform',
    items: [
      { to: '/streams', label: 'Streams', icon: IconStreams },
      { to: '/metrics', label: 'Metrics', icon: IconMetrics },
      // Deliberately not '/api': that prefix is the Vite proxy's, so a route
      // there is handed to the BFF and the browser gets the API's 404 instead
      // of this page.
      { to: '/api-explorer', label: 'API', icon: IconApi },
    ],
  },
];

export default function App() {
  return (
    <BrowserRouter>
      <Shell />
    </BrowserRouter>
  );
}

function Shell() {
  const [openAlerts, setOpenAlerts] = useState(null);
  const [live, setLive] = useState(false);
  // Bumped by the reset control. Used as a key on <Routes>, which remounts the
  // current page and so re-runs its initial fetch — otherwise a cleared store
  // would keep showing the old figures for up to one 5-second poll, which reads
  // as the reset having failed.
  const [resetNonce, setResetNonce] = useState(0);

  useEffect(() => {
    const poll = () => api.dashboard()
      .then((s) => { setOpenAlerts(s.openAlerts); setLive(true); })
      .catch(() => setLive(false));
    poll();
    const t = setInterval(poll, 5000);
    return () => clearInterval(t);
  }, [resetNonce]);

  return (
    <div style={{ display: 'flex', minHeight: '100vh', background: 'var(--canvas)' }}>
      <aside style={{
        width: 'var(--sidebar-w)', background: 'var(--side-bg)', color: 'var(--side-fg)',
        flexShrink: 0, position: 'sticky', top: 0, height: '100vh',
        // Column, so the navigation takes the slack and the external tools stay
        // at the bottom whatever the viewport height.
        display: 'flex', flexDirection: 'column',
      }}>
        <div style={{
          height: 'var(--topbar-h)', display: 'flex', alignItems: 'center', gap: 10,
          padding: '0 var(--space-4)', borderBottom: '1px solid var(--side-border)',
          fontWeight: 700, letterSpacing: '-.01em', fontSize: 'var(--text-lg)',
        }}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 2 4 5.5v6c0 4.6 3.4 8.9 8 10.5 4.6-1.6 8-5.9 8-10.5v-6L12 2Z"
                  stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
          </svg>
          Overwatch
        </div>

        <nav style={{ padding: 'var(--space-2)', flex: 1, minHeight: 0, overflowY: 'auto' }}>
          {NAV.map((group) => (
            // A real <ul> per group, labelled by its heading. The grouping is
            // then structure rather than decoration, so a screen reader
            // announces "Investigate, list, 2 items" instead of reading nine
            // links with two visual gaps it cannot see.
            <section key={group.heading} aria-labelledby={`nav-${group.heading}`}
                     style={{ marginBottom: 'var(--space-4)' }}>
              <h2 id={`nav-${group.heading}`} style={{
                margin: 0, padding: '0 var(--space-3) var(--space-2)',
                fontSize: 'var(--text-xs)', fontWeight: 600,
                letterSpacing: '.06em', textTransform: 'uppercase',
                color: 'var(--side-fg)', opacity: 0.5,
              }}>
                {group.heading}
              </h2>
              <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                {group.items.map((item) => (
                  <li key={item.to}>
                    {/* Render-prop form rather than the style callback alone:
                        the icon's strength depends on isActive too, and
                        NavLink only hands that to children this way. */}
                    <NavLink to={item.to} style={({ isActive }) => ({
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                      padding: 'var(--row-pad)', borderRadius: 'var(--radius-md)',
                      color: 'var(--side-fg)', textDecoration: 'none',
                      fontSize: 'var(--text-base)', fontWeight: isActive ? 600 : 400,
                      background: isActive ? 'var(--side-active)' : 'transparent',
                      marginBottom: 2,
                    })}>
                    {({ isActive }) => (
                      <>
                      <span style={{ display: 'inline-flex', alignItems: 'center',
                                     gap: 'var(--space-3)', minWidth: 0 }}>
                        {/* The icon sits at 0.72 opacity when the link is idle
                            and full strength when it is active, so the active
                            row reads as one bright unit rather than bright text
                            next to a grey pictogram. */}
                        <span style={{ opacity: isActive ? 1 : 0.72, display: 'flex' }}>
                          <item.icon />
                        </span>
                        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis',
                                       whiteSpace: 'nowrap' }}>{item.label}</span>
                      </span>
                      {item.to === '/alerts' && openAlerts > 0 && (
                        <span style={{
                          background: 'rgba(255,255,255,.22)', borderRadius: 'var(--radius-full)',
                          padding: '1px 8px', fontSize: 'var(--text-xs)', fontWeight: 700,
                          fontVariantNumeric: 'tabular-nums',
                        }}>
                          {openAlerts}
                        </span>
                      )}
                      </>
                    )}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </nav>

        <ExternalTools />
      </aside>

      <main style={{ flex: 1, minWidth: 0 }}>
        <header style={{
          height: 'var(--topbar-h)', display: 'flex', alignItems: 'center',
          justifyContent: 'space-between', padding: '0 var(--space-6)',
          background: 'var(--bg)', borderBottom: '1px solid var(--border)',
          position: 'sticky', top: 0, zIndex: 10,
        }}>
          <span style={{ fontWeight: 600 }}>Fraud monitoring — South Africa</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)' }}>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7,
                           fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
              <span aria-hidden="true" style={{
                width: 8, height: 8, borderRadius: '50%',
                background: live ? 'var(--success-fg)' : 'var(--muted-fg)',
              }} />
              {live ? 'Live' : 'Disconnected'}
            </span>
            <ResetData onReset={() => { setOpenAlerts(0); setResetNonce((n) => n + 1); }} />
          </div>
        </header>

        <div style={{ padding: 'var(--space-6)' }}>
          <Routes key={resetNonce}>
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/transactions" element={<Transactions />} />
            {/* Two routes, one component: the directory and one person's
                profile are the same screen at different depths, and a profile
                needs its own URL so it can be linked to from an alert. */}
            <Route path="/customers" element={<Customers />} />
            <Route path="/customers/:id" element={<Customers />} />
            <Route path="/alerts" element={<Alerts />} />
            <Route path="/rules" element={<Rules />} />
            <Route path="/metrics" element={<Metrics />} />
            <Route path="/streams" element={<Streams />} />
            <Route path="/simulator" element={<Simulator />} />
            <Route path="/api-explorer" element={<ApiExplorer />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
