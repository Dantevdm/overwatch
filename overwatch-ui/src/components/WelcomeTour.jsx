import { useCallback, useEffect, useRef, useState } from 'react';
import { Lockup, Mark } from './Brand.jsx';
import {
  IconAlerts, IconApi, IconCardholders, IconDashboard, IconMetrics,
  IconRules, IconSimulator, IconStreams, IconTransactions,
} from './Icons.jsx';

/**
 * The first-run tour: what this application is, how a transaction becomes an
 * alert, and what to click first.
 *
 * Shown when the sign-in screen's "First time signing in" box is ticked, which
 * is pre-ticked on a browser that has never opened this app and clear
 * afterwards. So it appears once by itself, and any number of times on purpose
 * — which is what a demo needs: the person giving it wants to open it again in
 * front of an audience.
 *
 * On the styling: this is built in Capitec's visual idiom — navy grounds, one
 * red accent, oversized headlines, generous whitespace, rounded cards, pill
 * buttons — rather than assembled from their assets. Every word of copy here is
 * about *this* application and was written for it. Their site's text and images
 * are theirs; the look is a costume, and the footer says as much.
 *
 * Structurally it is a scroll, not a carousel. A stepper with Next buttons
 * makes people click through content they would rather skim, and hides how much
 * is left; one scrollable column with a chapter rail lets someone read two
 * sections and leave. The rail tracks position rather than driving it.
 */

const ACCENT = 'var(--accent)';

/** The chapters, in order. `id` is both the anchor and the rail's key. */
const CHAPTERS = [
  { id: 'what', label: 'What this is' },
  { id: 'pipeline', label: 'How it works' },
  { id: 'scoring', label: 'Scoring' },
  { id: 'screens', label: 'The screens' },
  { id: 'try', label: 'Try this' },
  { id: 'stack', label: 'Under the hood' },
];

const PIPELINE = [
  {
    title: 'Transactions arrive',
    detail: 'A simulator publishes card transactions shaped like real South '
      + 'African card traffic — a diurnal curve, plausible merchants, a small '
      + 'seam of injected fraud.',
    note: 'Kafka topic: transactions',
  },
  {
    title: 'The engine scores each one',
    detail: 'Every transaction is evaluated against the whole rule set — not '
      + 'the first rule that matches. Each rule that fires contributes a '
      + 'weight, and the weights accumulate into a risk score.',
    note: 'Sub-second, p95',
  },
  {
    title: 'An alert is raised, with its reasoning',
    detail: 'Above the threshold the alert is stored together with every rule '
      + 'that contributed, its weight, and the evidence — so an analyst can '
      + 'see why, not just what.',
    note: 'Kafka topic: fraud-alerts',
  },
  {
    title: 'You watch it happen',
    detail: 'The console reads the same store. Alerts appear within about a '
      + 'second of the transaction that caused them, and every figure on the '
      + 'dashboard is a live query.',
    note: 'This screen',
  },
];

const BANDS = [
  { name: 'LOW', range: '0.30 – 0.44', meaning: 'One rule fired. Worth a glance.', colour: 'var(--sev-low)' },
  { name: 'MEDIUM', range: '0.45 – 0.59', meaning: 'Two signals agree.', colour: 'var(--sev-medium)' },
  { name: 'HIGH', range: '0.60 – 0.79', meaning: 'Three or more. Act on it.', colour: 'var(--sev-high)' },
  { name: 'CRITICAL', range: '0.80 – 1.00', meaning: 'A pile-up. Stop the card.', colour: 'var(--sev-critical)' },
];

const SCREENS = [
  { icon: IconDashboard, name: 'Dashboard', what: 'The whole pipeline in one glance, over any window from 15 minutes to a week.' },
  { icon: IconAlerts, name: 'Alerts', what: 'Every alert with its contributing rules, filterable by severity and status.' },
  { icon: IconTransactions, name: 'Transactions', what: 'The raw stream, searchable by cardholder, card or merchant.' },
  { icon: IconCardholders, name: 'Cardholders', what: 'One person, all their activity — the context that turns a flag into a decision.' },
  { icon: IconRules, name: 'Rules', what: 'Tune a weight or a threshold live. Shadow mode scores without alerting.' },
  { icon: IconSimulator, name: 'Simulator', what: 'Start, pause, change the rate, or inject a specific fraud pattern on demand.' },
  { icon: IconStreams, name: 'Streams', what: 'The topics themselves — messages, offsets, consumer lag.' },
  { icon: IconMetrics, name: 'Metrics', what: 'Detection latency, throughput and rule hit rates, straight from Prometheus.' },
  { icon: IconApi, name: 'API', what: 'Every endpoint, callable from the browser, with the request it sent.' },
];

const MOVES = [
  {
    do: 'Inject a compound fraud pattern',
    where: 'Simulator → Inject → COMPOUND',
    then: 'A large, round, foreign, small-hours transaction goes on the stream. '
      + 'A CRITICAL alert with five contributing rules appears on the dashboard '
      + 'about a second later.',
  },
  {
    do: 'Open the alert and read the reasoning',
    where: 'Alerts → the newest row',
    then: 'Five rules, five weights, summing past 1.0 and capped. That is the '
      + 'whole design in one screen.',
  },
  {
    do: 'Put a rule into shadow mode',
    where: 'Rules → any rule → Shadow',
    then: 'It keeps scoring and recording hits but stops raising alerts — how '
      + 'you measure a threshold change before it reaches production.',
  },
  {
    do: 'Look at one cardholder',
    where: 'Alerts → a cardholder name',
    then: 'Their whole history. A frequent traveller trips the cross-border '
      + 'rule constantly, and this is the screen where that stops looking '
      + 'like fraud.',
  },
];

const STACK = [
  'Java 25', 'Spring Boot 4.1', 'Kafka (Redpanda)', 'PostgreSQL', 'Flyway',
  'React 19', 'Vite', 'Prometheus', 'Grafana', 'Docker Compose',
];

export default function WelcomeTour({ onClose, analystName }) {
  const scroller = useRef(null);
  const dialog = useRef(null);
  const closeButton = useRef(null);
  const [progress, setProgress] = useState(0);
  const [active, setActive] = useState(CHAPTERS[0].id);
  const [seen, setSeen] = useState(() => new Set());

  // Escape closes, and focus starts on the close button rather than being left
  // on whatever the sign-in screen had. Unlike the reset dialog there is
  // nothing destructive in here, so the default action being "close" is fine.
  useEffect(() => {
    closeButton.current?.focus();
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    // The page behind must not scroll while a full-height modal is over it.
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = previous;
    };
  }, [onClose]);

  // A focus trap, not a focus suggestion. Without it Tab walks out of the
  // dialog and into the sign-in form underneath, which is still in the DOM.
  const onKeyDown = useCallback((e) => {
    if (e.key !== 'Tab') return;
    const focusable = dialog.current?.querySelectorAll(
      'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
    if (!focusable || focusable.length === 0) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }, []);

  // One scroll handler drives both the progress bar and the chapter rail.
  // Cheaper and steadier than an IntersectionObserver per section, and it
  // cannot disagree with itself about which chapter is current.
  const onScroll = useCallback(() => {
    const el = scroller.current;
    if (!el) return;
    const max = el.scrollHeight - el.clientHeight;
    setProgress(max > 0 ? Math.min(1, el.scrollTop / max) : 1);

    // Current chapter: the last one whose top has passed a line a third of the
    // way down the viewport. Using the viewport top instead makes the rail
    // change one section too late all the way down.
    const line = el.scrollTop + el.clientHeight / 3;
    let current = CHAPTERS[0].id;
    const arrived = new Set();
    for (const chapter of CHAPTERS) {
      const node = el.querySelector(`#tour-${chapter.id}`);
      if (!node) continue;
      if (node.offsetTop <= line) current = chapter.id;
      // Sections fade in as they come within a viewport of the fold, and stay
      // in once seen — re-hiding a section on scroll-up is a party trick that
      // makes re-reading something feel broken.
      if (node.offsetTop < el.scrollTop + el.clientHeight * 1.15) arrived.add(chapter.id);
    }
    setActive(current);
    setSeen((previous) => {
      const merged = new Set(previous);
      let changed = false;
      arrived.forEach((id) => { if (!merged.has(id)) { merged.add(id); changed = true; } });
      return changed ? merged : previous;
    });
  }, []);

  useEffect(() => { onScroll(); }, [onScroll]);

  const jump = (id) => {
    scroller.current?.querySelector(`#tour-${id}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  const atEnd = progress > 0.985;

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 200,
      background: 'rgba(0, 12, 26, .62)', backdropFilter: 'blur(3px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: 'clamp(0px, 3vw, 32px)',
    }}>
      <div ref={dialog} role="dialog" aria-modal="true" aria-labelledby="tour-title"
           onKeyDown={onKeyDown}
           className="ow-rise"
           style={{
             position: 'relative', width: 'min(1040px, 100%)',
             height: 'min(760px, 100%)', background: 'var(--bg)',
             borderRadius: 'clamp(0px, 2vw, var(--radius-xl))',
             boxShadow: '0 30px 80px -20px rgba(0, 12, 26, .6)',
             overflow: 'hidden', display: 'flex', flexDirection: 'column',
           }}>

        {/* Reading progress. Three pixels at the top of the dialog, because the
            one thing a long scrollable modal must not do is hide how long it
            is. */}
        <div aria-hidden="true" style={{
          position: 'absolute', top: 0, left: 0, right: 0, height: 3,
          background: 'var(--border)', zIndex: 3,
        }}>
          <div style={{
            height: '100%', width: `${progress * 100}%`, background: ACCENT,
            transition: 'width 90ms linear',
          }} />
        </div>

        <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
          {/* ---- Chapter rail ------------------------------------------------
              Hidden under 860px, where 200px of rail costs more than it gives.
              It reports position and offers a jump; it is not a stepper, and
              nothing here is gated behind reading the section before it. */}
          <nav aria-label="Sections" className="ow-tour-rail" style={{
            width: 208, flexShrink: 0, borderRight: '1px solid var(--border)',
            padding: 'var(--space-6) var(--space-4)', background: 'var(--canvas)',
            display: 'flex', flexDirection: 'column', gap: 'var(--space-5)',
          }}>
            <Mark size={26} />
            <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 2 }}>
              {CHAPTERS.map((chapter, i) => {
                const current = chapter.id === active;
                return (
                  <li key={chapter.id}>
                    <button type="button" onClick={() => jump(chapter.id)} style={{
                      display: 'flex', alignItems: 'center', gap: 10, width: '100%',
                      padding: '7px var(--space-3)', border: 'none', background: 'none',
                      borderRadius: 'var(--radius-md)', cursor: 'pointer',
                      textAlign: 'left', fontFamily: 'inherit',
                      fontSize: 'var(--text-sm)',
                      fontWeight: current ? 600 : 400,
                      color: current ? 'var(--fg)' : 'var(--muted-fg)',
                    }}>
                      <span aria-hidden="true" style={{
                        width: 20, height: 20, borderRadius: 'var(--radius-full)',
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: 11, fontWeight: 700, flexShrink: 0,
                        background: current ? ACCENT : 'var(--surface)',
                        color: current ? 'var(--accent-fg)' : 'var(--muted-fg)',
                        transition: 'background 200ms ease-out, color 200ms ease-out',
                      }}>
                        {i + 1}
                      </span>
                      {chapter.label}
                    </button>
                  </li>
                );
              })}
            </ul>
            <p style={{ marginTop: 'auto', marginBottom: 0, fontSize: 'var(--text-xs)',
                        color: 'var(--muted-fg)', lineHeight: 1.5 }}>
              About three minutes. You can close this at any point and open it
              again from the sign-in screen.
            </p>
          </nav>

          {/* ---- The scroll -------------------------------------------------- */}
          <div ref={scroller} onScroll={onScroll} style={{
            flex: 1, minWidth: 0, overflowY: 'auto', scrollBehavior: 'smooth',
          }}>
            {/* Hero */}
            <header style={{
              background: 'var(--brand-gradient)', color: '#ffffff',
              padding: 'clamp(28px, 5vw, 56px)', position: 'relative', overflow: 'hidden',
            }}>
              <div aria-hidden="true" className="ow-sweep" style={{
                position: 'absolute', top: 0, bottom: 0, width: '50%',
                background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.07), transparent)',
              }} />
              <Lockup mono size={26} />
              <h1 id="tour-title" style={{
                margin: 'var(--space-6) 0 var(--space-4)',
                fontSize: 'clamp(28px, 4.4vw, 46px)', lineHeight: 1.1,
                letterSpacing: '-.025em', fontWeight: 700, maxWidth: 620,
              }}>
                {analystName ? `Welcome, ${analystName.split(' ')[0]}.` : 'Welcome.'}
                <br />
                This is Overwatch.
              </h1>
              <p style={{ margin: 0, maxWidth: 560, fontSize: 'var(--text-lg)',
                          lineHeight: 1.6, opacity: 0.88 }}>
                A working card-fraud detection pipeline: transactions scored
                against a weighted rule set as they land, with the reasoning
                attached. Here is what it does and what to click first.
              </p>
            </header>

            <div style={{ padding: 'clamp(24px, 4vw, 48px)', display: 'grid',
                          gap: 'clamp(36px, 5vw, 64px)' }}>

              {/* ---- What this is ---------------------------------------- */}
              <Section id="what" seen={seen} eyebrow="What this is"
                       title="Fraud detection you can see the inside of">
                <p style={PROSE}>
                  Most fraud systems are a black box that returns a verdict.
                  This one is built the other way round: every alert carries the
                  rules that produced it, their weights and the evidence, and
                  every number on every screen is a live query you can go and
                  run yourself on the API page.
                </p>
                <p style={PROSE}>
                  It is a demonstration stack, not a product. It runs entirely
                  on your machine, the traffic is generated, and every cardholder
                  in it is invented — combinatorial names, tokenised card
                  references, no real person and no real card anywhere in the
                  system.
                </p>
                <div style={{ display: 'grid', gap: 'var(--space-3)',
                              gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
                              marginTop: 'var(--space-5)' }}>
                  {[
                    ['< 1s', 'transaction to alert, p95'],
                    ['7', 'rules, individually weighted'],
                    ['9', 'screens, one pipeline'],
                    ['0', 'real cardholders'],
                  ].map(([figure, caption]) => (
                    <div key={caption} style={{
                      padding: 'var(--space-4)', background: 'var(--canvas)',
                      border: '1px solid var(--border)', borderRadius: 'var(--radius)',
                    }}>
                      <div style={{ fontSize: 'var(--text-2xl)', fontWeight: 700,
                                    color: 'var(--brand)', lineHeight: 1.1 }}>
                        {figure}
                      </div>
                      <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                    marginTop: 4 }}>
                        {caption}
                      </div>
                    </div>
                  ))}
                </div>
              </Section>

              {/* ---- Pipeline -------------------------------------------- */}
              <Section id="pipeline" seen={seen} eyebrow="How it works"
                       title="How a transaction becomes an alert">
                <ol style={{ listStyle: 'none', margin: 'var(--space-5) 0 0', padding: 0,
                             display: 'grid', gap: 0 }}>
                  {PIPELINE.map((step, i) => (
                    <li key={step.title} style={{ display: 'grid',
                                                  gridTemplateColumns: '34px 1fr',
                                                  gap: 'var(--space-4)' }}>
                      {/* The spine: a numbered node with a line running down to
                          the next one. Drawn with a border rather than an SVG so
                          it stretches to whatever height the copy needs. */}
                      <div style={{ display: 'flex', flexDirection: 'column',
                                    alignItems: 'center' }}>
                        <span style={{
                          width: 30, height: 30, borderRadius: 'var(--radius-full)',
                          background: 'var(--brand)', color: '#ffffff',
                          display: 'inline-flex', alignItems: 'center',
                          justifyContent: 'center', fontWeight: 700,
                          fontSize: 'var(--text-sm)', flexShrink: 0,
                        }}>
                          {i + 1}
                        </span>
                        {i < PIPELINE.length - 1 && (
                          <span aria-hidden="true" style={{
                            flex: 1, width: 2, background: 'var(--border)',
                            marginTop: 4, marginBottom: 4, minHeight: 24,
                          }} />
                        )}
                      </div>
                      <div style={{ paddingBottom: 'var(--space-6)' }}>
                        <h3 style={{ margin: '4px 0 6px', fontSize: 'var(--text-lg)',
                                     fontWeight: 600 }}>
                          {step.title}
                        </h3>
                        <p style={{ ...PROSE, margin: 0 }}>{step.detail}</p>
                        <code style={{
                          display: 'inline-block', marginTop: 'var(--space-3)',
                          padding: '3px 8px', background: 'var(--surface)',
                          borderRadius: 'var(--radius-sm)', fontFamily: 'var(--font-mono)',
                          fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                        }}>
                          {step.note}
                        </code>
                      </div>
                    </li>
                  ))}
                </ol>
              </Section>

              {/* ---- Scoring --------------------------------------------- */}
              <Section id="scoring" seen={seen} eyebrow="Scoring"
                       title="Rules do not return true or false">
                <p style={PROSE}>
                  Each rule contributes a weight rather than a verdict, and the
                  weights accumulate. A single moderate signal and five
                  overlapping ones are different situations, and a system that
                  latches on the first hit throws that difference away — which
                  in production is most of the job, because a false positive is
                  not free. It is somebody whose card stops working in a
                  supermarket queue.
                </p>
                <div style={{ display: 'grid', gap: 'var(--space-2)',
                              marginTop: 'var(--space-5)' }}>
                  {BANDS.map((band) => (
                    <div key={band.name} style={{
                      display: 'grid', gridTemplateColumns: '10px 92px 108px 1fr',
                      alignItems: 'center', gap: 'var(--space-4)',
                      padding: 'var(--space-3) var(--space-4)',
                      background: 'var(--canvas)', border: '1px solid var(--border)',
                      borderRadius: 'var(--radius)', fontSize: 'var(--text-sm)',
                    }}>
                      <span aria-hidden="true" style={{
                        width: 10, height: 10, borderRadius: 2, background: band.colour,
                      }} />
                      <strong style={{ letterSpacing: '.03em' }}>{band.name}</strong>
                      <span style={{ fontFamily: 'var(--font-mono)',
                                     fontSize: 'var(--text-xs)',
                                     color: 'var(--muted-fg)' }}>
                        {band.range}
                      </span>
                      <span style={{ color: 'var(--muted-fg)' }}>{band.meaning}</span>
                    </div>
                  ))}
                </div>
                <p style={{ ...PROSE, fontSize: 'var(--text-xs)',
                            marginTop: 'var(--space-4)' }}>
                  Nothing under 0.30 becomes an alert at all — that is the
                  threshold, and it is why the bottom band starts exactly there.
                </p>
              </Section>

              {/* ---- Screens --------------------------------------------- */}
              <Section id="screens" seen={seen} eyebrow="The screens"
                       title="Nine views of one pipeline">
                <div style={{ display: 'grid', gap: 'var(--space-3)',
                              gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 240px), 1fr))',
                              marginTop: 'var(--space-5)' }}>
                  {SCREENS.map((screen) => (
                    <div key={screen.name} style={{
                      padding: 'var(--space-4)', border: '1px solid var(--border)',
                      borderRadius: 'var(--radius)', background: 'var(--bg)',
                      display: 'grid', gap: 6,
                    }}>
                      <span style={{ display: 'flex', alignItems: 'center', gap: 8,
                                     color: 'var(--brand)' }}>
                        <screen.icon />
                        <strong style={{ fontSize: 'var(--text-base)', color: 'var(--fg)' }}>
                          {screen.name}
                        </strong>
                      </span>
                      <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                     lineHeight: 1.55 }}>
                        {screen.what}
                      </span>
                    </div>
                  ))}
                </div>
              </Section>

              {/* ---- Try this -------------------------------------------- */}
              <Section id="try" seen={seen} eyebrow="Try this"
                       title="Four things worth doing first">
                <div style={{ display: 'grid', gap: 'var(--space-3)',
                              marginTop: 'var(--space-5)' }}>
                  {MOVES.map((move, i) => (
                    <div key={move.do} style={{
                      padding: 'var(--space-4) var(--space-5)',
                      borderRadius: 'var(--radius)', background: 'var(--canvas)',
                      // The accent as a left edge rather than a fill: four
                      // red cards in a column would shout, and only one thing
                      // on a screen gets to be red.
                      borderLeft: `3px solid ${ACCENT}`,
                      border: '1px solid var(--border)', borderLeftWidth: 3,
                      borderLeftColor: ACCENT,
                    }}>
                      <div style={{ display: 'flex', gap: 'var(--space-3)',
                                    alignItems: 'baseline', flexWrap: 'wrap' }}>
                        <strong style={{ fontSize: 'var(--text-base)' }}>
                          {i + 1}. {move.do}
                        </strong>
                        <code style={{ fontFamily: 'var(--font-mono)',
                                       fontSize: 'var(--text-xs)',
                                       color: 'var(--brand)' }}>
                          {move.where}
                        </code>
                      </div>
                      <p style={{ ...PROSE, margin: '6px 0 0',
                                  fontSize: 'var(--text-sm)' }}>
                        {move.then}
                      </p>
                    </div>
                  ))}
                </div>
              </Section>

              {/* ---- Stack ----------------------------------------------- */}
              <Section id="stack" seen={seen} eyebrow="Under the hood"
                       title="What it is built on">
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-2)',
                              marginTop: 'var(--space-5)' }}>
                  {STACK.map((item) => (
                    <span key={item} style={{
                      padding: '5px 12px', borderRadius: 'var(--radius-full)',
                      background: 'var(--brand-20)', color: 'var(--brand-active)',
                      fontSize: 'var(--text-xs)', fontWeight: 600,
                    }}>
                      {item}
                    </span>
                  ))}
                </div>
                <p style={{ ...PROSE, marginTop: 'var(--space-5)' }}>
                  Five services in one Maven reactor, one <code>docker compose
                  up</code>, schema owned by Flyway, metrics scraped by
                  Prometheus into Grafana. The whole thing starts from nothing
                  in under a minute.
                </p>

                {/* The disclaimer, at the end of the scroll rather than in a
                    corner. A demo dressed in a bank's colours has to say
                    plainly that it is not the bank's. */}
                <p style={{
                  marginTop: 'var(--space-6)', marginBottom: 0,
                  padding: 'var(--space-4)', background: 'var(--surface)',
                  borderRadius: 'var(--radius)', fontSize: 'var(--text-xs)',
                  color: 'var(--muted-fg)', lineHeight: 1.6,
                }}>
                  Overwatch is an independent demonstration project. It is not a
                  Capitec product, not affiliated with or endorsed by Capitec,
                  and the branding here is an approximation used to show the work
                  in context. No real customer, card or account data is present
                  anywhere in the system, and the sign-in screen authenticates
                  nothing.
                </p>
              </Section>
            </div>
          </div>
        </div>

        {/* ---- Footer ------------------------------------------------------ */}
        <footer style={{
          borderTop: '1px solid var(--border)', background: 'var(--bg)',
          padding: 'var(--space-4) clamp(16px, 3vw, 28px)',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          gap: 'var(--space-4)', flexWrap: 'wrap',
        }}>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
            {atEnd ? 'That is everything.' : `${Math.round(progress * 100)}% read`}
          </span>
          <div style={{ display: 'flex', gap: 'var(--space-3)', alignItems: 'center' }}>
            {!atEnd && (
              <button type="button" onClick={() => jump(CHAPTERS[CHAPTERS.length - 1].id)}
                      style={GHOST_BUTTON}>
                Skip to the end
              </button>
            )}
            {/* One button, and it says the same thing whether you read all of
                it or none: there is nothing to complete here. */}
            <button ref={closeButton} type="button" onClick={onClose} style={{
              padding: '10px 22px', border: 'none', borderRadius: 'var(--radius-full)',
              background: ACCENT, color: 'var(--accent-fg)', cursor: 'pointer',
              fontSize: 'var(--text-base)', fontWeight: 600, fontFamily: 'inherit',
            }}>
              Open the console
            </button>
          </div>
        </footer>
      </div>

      {/* Scoped rather than global: the rail is this component's layout and
          nothing else in the app has one. A <style> element is the only way to
          express a media query alongside inline styles. */}
      <style>{`
        @media (max-width: 860px) { .ow-tour-rail { display: none !important; } }
      `}</style>
    </div>
  );
}

const PROSE = {
  margin: '0 0 var(--space-4)', fontSize: 'var(--text-base)',
  lineHeight: 1.68, color: 'var(--fg)', maxWidth: '62ch',
};

const GHOST_BUTTON = {
  padding: '9px 16px', borderRadius: 'var(--radius-full)',
  border: '1px solid var(--border)', background: 'none',
  color: 'var(--muted-fg)', cursor: 'pointer',
  fontSize: 'var(--text-sm)', fontFamily: 'inherit',
};

/**
 * One chapter. Fades and lifts in the first time it comes near the fold, and
 * stays in afterwards — a section that re-hides itself on scroll-up makes
 * re-reading something feel broken.
 */
function Section({ id, seen, eyebrow, title, children }) {
  const shown = seen.has(id);
  return (
    <section id={`tour-${id}`} aria-labelledby={`tour-${id}-title`}
             className="ow-grow"
             style={{
               opacity: shown ? 1 : 0,
               transform: shown ? 'none' : 'translateY(18px)',
               transition: 'opacity 520ms ease-out, transform 520ms cubic-bezier(.21,.78,.35,1)',
               // scroll-margin, so the rail's jump lands with the eyebrow
               // clear of the progress bar rather than tucked under it.
               scrollMarginTop: 'var(--space-6)',
             }}>
      <div style={{
        fontSize: 'var(--text-xs)', fontWeight: 700, letterSpacing: '.1em',
        textTransform: 'uppercase', color: ACCENT, marginBottom: 'var(--space-3)',
      }}>
        {eyebrow}
      </div>
      <h2 id={`tour-${id}-title`} style={{
        margin: '0 0 var(--space-4)', fontSize: 'clamp(22px, 3vw, 32px)',
        lineHeight: 1.2, letterSpacing: '-.02em', fontWeight: 700, maxWidth: '24ch',
      }}>
        {title}
      </h2>
      {children}
    </section>
  );
}
