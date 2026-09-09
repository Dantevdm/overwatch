import { useEffect, useState } from 'react';
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom';
import Dashboard from './pages/Dashboard.jsx';
import Alerts from './pages/Alerts.jsx';
import Transactions from './pages/Transactions.jsx';
import Rules from './pages/Rules.jsx';
import Simulator from './pages/Simulator.jsx';
import Metrics from './pages/Metrics.jsx';
import ResetData from './components/ResetData.jsx';
import { api } from './api.js';

const NAV = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/transactions', label: 'Transactions' },
  { to: '/alerts', label: 'Alerts' },
  { to: '/rules', label: 'Rules' },
  { to: '/metrics', label: 'Metrics' },
  { to: '/simulator', label: 'Simulator' },
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

        <nav style={{ padding: 'var(--space-2)' }}>
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to} style={({ isActive }) => ({
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: 'var(--row-pad)', borderRadius: 'var(--radius-md)',
              color: 'var(--side-fg)', textDecoration: 'none',
              fontSize: 'var(--text-base)', fontWeight: isActive ? 600 : 400,
              background: isActive ? 'var(--side-active)' : 'transparent',
              marginBottom: 2,
            })}>
              {item.label}
              {item.to === '/alerts' && openAlerts > 0 && (
                <span style={{
                  background: 'rgba(255,255,255,.22)', borderRadius: 'var(--radius-full)',
                  padding: '1px 8px', fontSize: 'var(--text-xs)', fontWeight: 700,
                  fontVariantNumeric: 'tabular-nums',
                }}>
                  {openAlerts}
                </span>
              )}
            </NavLink>
          ))}
        </nav>
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
            <Route path="/alerts" element={<Alerts />} />
            <Route path="/rules" element={<Rules />} />
            <Route path="/metrics" element={<Metrics />} />
            <Route path="/simulator" element={<Simulator />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
