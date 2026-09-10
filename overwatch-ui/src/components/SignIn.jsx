import { useEffect, useRef, useState } from 'react';
import { Lockup, Mark } from './Brand.jsx';

/**
 * A mock sign-in screen, so the demo opens the way the real thing would.
 *
 * ---------------------------------------------------------------------------
 * This authenticates nothing, and that is deliberate and permanent.
 * ---------------------------------------------------------------------------
 *
 * A convincing bank login page is the exact shape of a phishing page, so this
 * one is built so that it cannot be repurposed into one, and so that nobody
 * looking at it is in any doubt:
 *
 *  - It makes no network call. There is no endpoint, no fetch, nothing to point
 *    at a collector. The submit handler sets a flag in this tab's memory.
 *  - The password field's value is never stored, never logged, and is discarded
 *    on submit. Only the analyst's display name survives, in sessionStorage.
 *  - autoComplete is off on the form and on both fields. Left on, a browser
 *    that has ever saved real Capitec credentials would happily offer them to
 *    this page — which is precisely the harm to design against, and it costs
 *    one attribute to prevent.
 *  - The notice on the card is not dismissable and does not fade. It says the
 *    screen is a mock and asks you not to type a real password.
 *  - The happy path requires typing nothing: the demo analyst is prefilled and
 *    Enter goes straight through.
 *
 * The real system has no authentication at all — a known, documented gap. This
 * screen does not close it and must never be mistaken for closing it: the API
 * behind it is open, and anything reachable from this UI is reachable without
 * it. It is stage dressing for a demonstration, and the day real auth arrives
 * it should be deleted rather than extended.
 *
 * Session-scoped rather than persistent, because for a demo the screen is worth
 * seeing: a new tab shows it again.
 */

const STORAGE_KEY = 'ow.demo.analyst';

/**
 * Whether this browser has ever opened the app. localStorage rather than
 * sessionStorage, deliberately: the sign-in flag is per-tab so the screen is
 * re-showable during a demo, but "have you seen the tour" is a fact about the
 * person, and pre-ticking the box on every new tab would be a modal in the
 * face of someone who has already read it.
 */
const RETURNING_KEY = 'ow.demo.returning';

function isFirstEverVisit() {
  try {
    return localStorage.getItem(RETURNING_KEY) === null;
  } catch {
    // Storage blocked. Treating that as "returning" is the quieter wrong
    // answer: an unwanted tour on every load is worse than a missed one.
    return false;
  }
}

export function markVisited() {
  try {
    localStorage.setItem(RETURNING_KEY, new Date().toISOString());
  } catch { /* nothing to do — see isFirstEverVisit */ }
}

/** The prefilled identity. Invented — no such person, which is the point. */
const DEMO_ANALYST = { username: 'l.mokoena', name: 'Lerato Mokoena', role: 'Fraud Analyst' };

/**
 * Whether this tab has been through the screen. Wrapped because sessionStorage
 * throws outright in some privacy modes, and a storage error must not be the
 * thing that stops the app rendering.
 */
export function readSession() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function clearSession() {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch { /* nothing to do — see readSession */ }
}

export default function SignIn({ onSignedIn }) {
  const [username, setUsername] = useState(DEMO_ANALYST.username);
  const [password, setPassword] = useState('');
  // Pre-ticked on a browser that has never opened this app, clear afterwards.
  // So the tour appears once by itself, and any number of times on purpose —
  // which is what a demo needs, because the person giving it wants to open it
  // again in front of an audience.
  const [firstTime, setFirstTime] = useState(isFirstEverVisit);
  const [busy, setBusy] = useState(false);
  const buttonRef = useRef(null);

  // Focus the button, not a field. Nothing needs filling in, so the fastest
  // route through is the one the keyboard already offers — and unlike the reset
  // dialog, the default action here destroys nothing.
  useEffect(() => { buttonRef.current?.focus(); }, []);

  const submit = (e) => {
    e.preventDefault();
    if (busy) return;
    // Discarded immediately. Not sent, not hashed, not kept.
    setPassword('');
    // A beat of "signing in", because instant is the one thing that would make
    // the screen feel fake in the wrong way. 550ms, not two seconds.
    setBusy(true);
    window.setTimeout(() => {
      const analyst = { ...DEMO_ANALYST, username: username.trim() || DEMO_ANALYST.username };
      try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(analyst));
      } catch { /* the flag is a nicety; the app works without it */ }
      onSignedIn(analyst, { showTour: firstTime });
    }, 550);
  };

  return (
    <div style={{
      minHeight: '100vh', display: 'grid',
      // Two panels on a wide viewport, one column on a narrow one. The brand
      // panel is the one that collapses away: on a phone the form is the whole
      // job and a full-bleed navy hero above it just pushes it below the fold.
      gridTemplateColumns: 'minmax(0, 1fr)',
      background: 'var(--canvas)',
    }}>
      <div style={{
        display: 'flex', flexWrap: 'wrap', minHeight: '100vh',
        alignItems: 'stretch',
      }}>
        {/* ---- Brand panel ------------------------------------------------ */}
        <div style={{
          flex: '1 1 420px', minWidth: 'min(100%, 340px)',
          background: 'var(--brand-gradient)', color: '#ffffff',
          padding: 'clamp(28px, 6vw, 64px)',
          display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
          gap: 'var(--space-8)', position: 'relative', overflow: 'hidden',
        }}>
          <div aria-hidden="true" className="ow-sweep" style={{
            position: 'absolute', top: 0, bottom: 0, width: '50%',
            background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.06), transparent)',
          }} />

          <Lockup mono size={30} />

          <div className="ow-rise" style={{ maxWidth: 460 }}>
            <h1 style={{
              margin: '0 0 var(--space-4)', fontSize: 'clamp(26px, 4vw, 40px)',
              lineHeight: 1.15, letterSpacing: '-.02em', fontWeight: 700,
            }}>
              Real-time fraud detection
            </h1>
            <p style={{ margin: 0, fontSize: 'var(--text-lg)', lineHeight: 1.6, opacity: 0.86 }}>
              Every card transaction scored against a weighted rule set as it
              lands, in under a second — with the reasoning attached, so an
              analyst can see why.
            </p>
          </div>

          <ul style={{
            listStyle: 'none', margin: 0, padding: 0, display: 'grid',
            gap: 'var(--space-3)', fontSize: 'var(--text-sm)', opacity: 0.8,
          }}>
            {[
              'Kafka-backed pipeline, sub-second detection latency',
              'Weighted rules with shadow mode for safe tuning',
              'Cardholder history behind every alert',
            ].map((line) => (
              <li key={line} style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <span aria-hidden="true" style={{
                  width: 5, height: 5, borderRadius: '50%', background: 'var(--accent)',
                  marginTop: 7, flexShrink: 0,
                }} />
                {line}
              </li>
            ))}
          </ul>
        </div>

        {/* ---- Form panel -------------------------------------------------- */}
        <div style={{
          flex: '1 1 400px', minWidth: 'min(100%, 320px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          padding: 'clamp(24px, 5vw, 56px)',
        }}>
          <form onSubmit={submit} autoComplete="off" noValidate
                className="ow-rise"
                style={{
                  width: '100%', maxWidth: 380, background: 'var(--bg)',
                  border: '1px solid var(--border)', borderRadius: 'var(--radius-xl)',
                  boxShadow: 'var(--shadow-lg)', padding: 'var(--space-6)',
                  display: 'grid', gap: 'var(--space-5)',
                }}>
            <div style={{ display: 'grid', gap: 6, justifyItems: 'start' }}>
              <Mark size={32} />
              <h2 style={{ margin: '6px 0 0', fontSize: 'var(--text-xl)',
                           letterSpacing: '-.01em' }}>
                Sign in
              </h2>
              <p style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
                Fraud Operations — Overwatch console
              </p>
            </div>

            {/* Not a toast and not dismissable. A mock login screen that does
                not say so is the one thing this file must not ship. */}
            <p role="note" style={{
              margin: 0, padding: 'var(--space-3)',
              background: 'var(--warning-bg)', color: 'var(--warning-fg)',
              border: '1px solid var(--warning-border)',
              borderRadius: 'var(--radius-md)',
              fontSize: 'var(--text-xs)', lineHeight: 1.55,
            }}>
              <strong>Demonstration only.</strong> This screen authenticates
              nothing — any details are accepted, none are sent anywhere, and
              nothing is checked. Please do not type a real password.
            </p>

            <Field label="Analyst ID" id="analyst-id">
              <input id="analyst-id" name="analyst-id" type="text"
                     value={username} onChange={(e) => setUsername(e.target.value)}
                     autoComplete="off" spellCheck="false" style={INPUT} />
            </Field>

            <Field label="Password" id="analyst-password">
              <input id="analyst-password" name="analyst-password" type="password"
                     value={password} onChange={(e) => setPassword(e.target.value)}
                     placeholder="Not required"
                     // "new-password" rather than "off": Chrome ignores "off"
                     // on password inputs and will offer a saved credential
                     // anyway. This is the value it actually respects.
                     autoComplete="new-password" style={INPUT} />
            </Field>

            {/* A checkbox rather than a link, because on a first visit the
                tour is the thing to do and the box being already ticked says
                so — while still leaving it as one click to decline. */}
            <label style={{
              display: 'flex', alignItems: 'flex-start', gap: 'var(--space-3)',
              fontSize: 'var(--text-sm)', cursor: 'pointer', lineHeight: 1.5,
            }}>
              <input type="checkbox" checked={firstTime}
                     onChange={(e) => setFirstTime(e.target.checked)}
                     style={{ marginTop: 2, width: 15, height: 15,
                              accentColor: 'var(--accent)', cursor: 'pointer' }} />
              <span>
                First time signing in
                <span style={{ display: 'block', fontSize: 'var(--text-xs)',
                               color: 'var(--muted-fg)' }}>
                  Show me what this application does
                </span>
              </span>
            </label>

            <button ref={buttonRef} type="submit" disabled={busy} style={{
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              gap: 8, width: '100%', padding: '11px var(--space-4)',
              background: busy ? 'var(--accent-hover)' : 'var(--accent)',
              color: 'var(--accent-fg)', border: 'none',
              borderRadius: 'var(--radius-md)', fontSize: 'var(--text-base)',
              fontWeight: 600, cursor: busy ? 'progress' : 'pointer',
            }}>
              {busy && (
                <svg className="ow-spin" width="14" height="14" viewBox="0 0 24 24"
                     fill="none" aria-hidden="true">
                  <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3"
                          opacity="0.3" />
                  <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="3"
                          strokeLinecap="round" strokeDasharray="14 43" />
                </svg>
              )}
              {busy ? 'Signing in…' : 'Sign in'}
            </button>

            <p style={{ margin: 0, fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                        lineHeight: 1.55 }}>
              Signed in as <strong>{DEMO_ANALYST.name}</strong>,{' '}
              {DEMO_ANALYST.role}. The console itself has no authentication —
              a documented gap, not something this screen closes.
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}

const INPUT = {
  width: '100%', padding: '9px var(--space-3)',
  border: '1px solid var(--input-border)', borderRadius: 'var(--radius-md)',
  fontSize: 'var(--text-base)', background: 'var(--bg)', color: 'var(--fg)',
  fontFamily: 'inherit',
};

function Field({ label, id, children }) {
  return (
    <div style={{ display: 'grid', gap: 5 }}>
      <label htmlFor={id} style={{
        fontSize: 'var(--text-xs)', fontWeight: 600, color: 'var(--muted-fg)',
        letterSpacing: '.03em', textTransform: 'uppercase',
      }}>
        {label}
      </label>
      {children}
    </div>
  );
}
