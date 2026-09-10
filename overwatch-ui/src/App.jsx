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
import Splash from './components/Splash.jsx';
import SignIn, { clearSession, readSession } from './components/SignIn.jsx';
import { Lockup } from './components/Brand.jsx';
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

/**
 * The shell above the router: sign-in, then splash, then the app.
 *
 * Both gates live outside <BrowserRouter> on purpose. Neither is a route — a
 * URL you can navigate to in order to skip the sign-in screen would make the
 * screen a decoration with a back door, and a splash with an address is a page
 * someone can bookmark. They are states of the application, so they are held
 * as state.
 */
export default function App() {
  const [analyst, setAnalyst] = useState(() => readSession());

  if (!analyst) {
    return <SignIn onSignedIn={setAnalyst} />;
  }

  return (
    <BrowserRouter>
      <Shell analyst={analyst} onSignOut={() => { clearSession(); setAnalyst(null); }} />
    </BrowserRouter>
  );
}

function Shell({ analyst, onSignOut }) {
  const [openAlerts, setOpenAlerts] = useState(null);
  const [live, setLive] = useState(false);
  // The splash is up until the first poll settles, either way, or until the
  // deadline below — whichever comes first. "Either way" is the important half:
  // a failed fetch dismisses it too, so an API that is down shows the app with
  // a Disconnected badge rather than a logo forever.
  const [booted, setBooted] = useState(false);
  const [splashGone, setSplashGone] = useState(false);
  // Bumped by the reset control. Used as a key on <Routes>, which remounts the
  // current page and so re-runs its initial fetch — otherwise a cleared store
  // would keep showing the old figures for up to one 5-second poll, which reads
  // as the reset having failed.
  const [resetNonce, setResetNonce] = useState(0);

  useEffect(() => {
    const poll = () => api.dashboard()
      .then((s) => { setOpenAlerts(s.openAlerts); setLive(true); })
      .catch(() => setLive(false))
      .finally(() => setBooted(true));
    poll();
    const t = setInterval(poll, 5000);
    return () => clearInterval(t);
  }, [resetNonce]);

  // The splash's two bounds. A floor of 600ms so a warm reload fades instead of
  // strobing, and a ceiling of 2.5s so nothing — a cold Docker start still
  // running Flyway, a hung request with no timeout — can hold the app behind a
  // logo. Both timers are set once, on mount, and not restarted by a reset.
  const [floorPassed, setFloorPassed] = useState(false);
  useEffect(() => {
    const floor = window.setTimeout(() => setFloorPassed(true), 600);
    const ceiling = window.setTimeout(() => setBooted(true), 2500);
    return () => { window.clearTimeout(floor); window.clearTimeout(ceiling); };
  }, []);

  const splashLeaving = booted && floorPassed;
  useEffect(() => {
    if (!splashLeaving || splashGone) return undefined;
    // Matches the fade in Splash. Unmounted afterwards rather than left at
    // opacity 0, so it stops being a fixed layer over the whole app.
    const t = window.setTimeout(() => setSplashGone(true), 300);
    return () => window.clearTimeout(t);
  }, [splashLeaving, splashGone]);

  return (
    <div style={{ display: 'flex', minHeight: '100vh', background: 'var(--canvas)' }}>
      {!splashGone && <Splash leaving={splashLeaving} />}
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
        }}>
          {/* Mono, because a red arc on the navy sidebar has nowhere near the
              contrast to read as a shape. */}
          <Lockup mono size={22} showProduct={false} />
          <span aria-hidden="true" style={{
            width: 1, height: 14, background: 'currentColor', opacity: 0.3,
          }} />
          <span style={{ fontWeight: 500, opacity: 0.85 }}>Overwatch</span>
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
                      marginBottom: 2, position: 'relative',
                      // Colour and weight change instantly on click, which is
                      // right for a navigation: the response to a click should
                      // never be something you wait for.
                      transition: 'background 140ms ease-out',
                    })}>
                    {({ isActive }) => (
                      <>
                      {/* The accent's one appearance in the sidebar: a marker
                          on the active row that grows out of nothing rather
                          than being drawn or not drawn. Scaled on Y from the
                          centre, so it costs a composite and cannot shift the
                          row's layout. */}
                      <span aria-hidden="true" className="ow-grow" style={{
                        position: 'absolute', left: 0, top: 6, bottom: 6, width: 3,
                        borderRadius: '0 3px 3px 0', background: 'var(--accent)',
                        transform: `scaleY(${isActive ? 1 : 0})`,
                        transformOrigin: 'center',
                        transition: 'transform 220ms cubic-bezier(.2,.8,.3,1)',
                      }} />
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
              {/* The dot, plus a ring expanding out of it while the poll is
                  landing. The halo is the only always-on animation in the app,
                  which is justified: "is this thing live" is the one question
                  a static dot genuinely cannot answer — a frozen page and a
                  connected one look identical. It stops when the connection
                  drops, which is the useful signal. */}
              <span aria-hidden="true" style={{
                position: 'relative', width: 8, height: 8, display: 'inline-flex',
              }}>
                <span style={{
                  position: 'absolute', inset: 0, borderRadius: '50%',
                  background: live ? 'var(--success-fg)' : 'var(--muted-fg)',
                }} />
                {live && (
                  <span className="ow-pulse" style={{
                    position: 'absolute', inset: 0, borderRadius: '50%',
                    background: 'var(--success-fg)',
                  }} />
                )}
              </span>
              {live ? 'Live' : 'Disconnected'}
            </span>
            <ResetData onReset={() => { setOpenAlerts(0); setResetNonce((n) => n + 1); }} />
            <AnalystChip analyst={analyst} onSignOut={onSignOut} />
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

/**
 * Who is looking at this, and the way back out to the sign-in screen.
 *
 * The initials avatar is the accent's one appearance in the top bar. It is
 * flat, not a photo: a demo that invents a face for a person who does not exist
 * is a small dishonesty with no upside.
 */
function AnalystChip({ analyst, onSignOut }) {
  const initials = analyst.name.split(' ').map((part) => part[0]).slice(0, 2).join('');
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)',
                  paddingLeft: 'var(--space-3)',
                  borderLeft: '1px solid var(--border)' }}>
      <span aria-hidden="true" style={{
        width: 28, height: 28, borderRadius: 'var(--radius-full)',
        background: 'var(--brand)', color: '#ffffff',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 'var(--text-xs)', fontWeight: 700, letterSpacing: '.02em',
      }}>
        {initials}
      </span>
      {/* The name and role are the useful part on a wide screen and the first
          thing to go on a narrow one, where the avatar alone still identifies
          the session. */}
      <span style={{ display: 'grid', lineHeight: 1.25 }}>
        <span style={{ fontSize: 'var(--text-sm)', fontWeight: 600 }}>{analyst.name}</span>
        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
          {analyst.role}
        </span>
      </span>
      <button type="button" onClick={onSignOut} title="Return to the sign-in screen"
              style={{
                background: 'none', border: '1px solid var(--border)',
                borderRadius: 'var(--radius-md)', padding: '5px 10px',
                fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                cursor: 'pointer', fontFamily: 'inherit',
              }}>
        Sign out
      </button>
    </div>
  );
}
