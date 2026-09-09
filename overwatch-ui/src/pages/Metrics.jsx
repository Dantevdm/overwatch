import { useState } from 'react';
import { Card, SegmentedControl } from '../components/Primitives.jsx';

/**
 * The Grafana dashboards, framed in place.
 *
 * Two things are true at once: Prometheus and Grafana are the right tools for
 * time-series metrics and nobody should reimplement them in React, and a reviewer
 * who has to find a second URL on a port they were not told about will simply not
 * look at them. Framing them here settles both — the metrics stay in Grafana,
 * where the query language and the drill-down live, but they are one click from
 * the alerts they explain.
 *
 * Each dashboard keeps an "Open in Grafana" link. The frame is deliberately the
 * lesser view: `kiosk` strips the chrome, which also strips the time picker, the
 * variable controls and the panel menus. When someone wants to actually explore —
 * change a query, widen a panel, inspect the data — they should be in Grafana
 * proper, and the link says so rather than leaving them fighting an iframe.
 */

/**
 * Base URL for the browser, not for the compose network.
 *
 * Injected at build time by Vite from VITE_GRAFANA_URL, which compose sets from
 * the same OW_GRAFANA_PORT preflight may have reassigned. The fallback matters
 * for `npm run dev` outside Docker, where nothing sets it.
 */
const GRAFANA = import.meta.env.VITE_GRAFANA_URL || 'http://localhost:3000';

/**
 * The provisioned dashboards, by UID.
 *
 * UIDs rather than titles or slugs: a UID is declared in the dashboard JSON and
 * is what Grafana's own URLs are keyed on, so renaming a dashboard does not break
 * this page. The blurb says what the dashboard answers, because "Pipeline Health"
 * does not.
 */
const DASHBOARDS = [
  {
    uid: 'overwatch-pipeline',
    label: 'Pipeline health',
    blurb: 'Throughput, detection latency percentiles, JVM and service uptime — '
         + 'is the pipeline keeping up, and is anything falling over.',
  },
  {
    uid: 'overwatch-fraud',
    label: 'Fraud overview',
    blurb: 'Alert volume and rate, severity mix, value flagged in ZAR, and the '
         + 'transaction mix by merchant category.',
  },
  {
    uid: 'overwatch-rules',
    label: 'Rule performance',
    blurb: 'Which rules fire and how often, including what the shadow rules '
         + 'would have caught.',
  },
];

/**
 * Windows offered to Grafana. Expressed in Grafana's own relative syntax rather
 * than converted from the dashboard's minutes, because `from=now-5m` is what the
 * URL takes and a translation layer between the two would only be a place for
 * them to disagree.
 */
const RANGES = [
  { value: 'now-5m', label: '5m' },
  { value: 'now-15m', label: '15m' },
  { value: 'now-30m', label: '30m' },
  { value: 'now-1h', label: '1h' },
  { value: 'now-12h', label: '12h' },
  { value: 'now-24h', label: '24h' },
  { value: 'now-7d', label: '7d' },
];

export default function Metrics() {
  const [active, setActive] = useState(DASHBOARDS[0].uid);
  const [from, setFrom] = useState('now-1h');

  const dashboard = DASHBOARDS.find((d) => d.uid === active) ?? DASHBOARDS[0];

  // kiosk: no Grafana chrome, so the frame reads as part of this page.
  // theme=light: matched to the UI rather than left to Grafana's own preference,
  // which would otherwise flip independently of the surrounding page.
  // refresh=10s: the Prometheus scrape interval, so the frame is never showing
  // something staler than the data behind it.
  const src = `${GRAFANA}/d/${dashboard.uid}`
    + `?kiosk&theme=light&refresh=10s&from=${from}&to=now`;

  // The same view with the chrome put back, for the escape hatch.
  const openHref = `${GRAFANA}/d/${dashboard.uid}?from=${from}&to=now`;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      <Card
        title={dashboard.label}
        action={
          <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
            <SegmentedControl
              label="Time range for the Grafana view"
              value={from}
              onChange={setFrom}
              options={RANGES.map((r) => ({ ...r, title: `Last ${r.label}` }))}
            />
            <a href={openHref} target="_blank" rel="noreferrer"
               style={{ fontSize: 'var(--text-sm)', color: 'var(--brand)',
                        textDecoration: 'none', whiteSpace: 'nowrap' }}>
              Open in Grafana ↗
            </a>
          </div>
        }
      >
        <div role="tablist" aria-label="Grafana dashboards"
             style={{ display: 'flex', gap: 'var(--space-2)', flexWrap: 'wrap',
                      marginBottom: 'var(--space-4)' }}>
          {DASHBOARDS.map((d) => {
            const selected = d.uid === active;
            return (
              <button key={d.uid} type="button" role="tab" aria-selected={selected}
                      onClick={() => setActive(d.uid)}
                      style={{
                        appearance: 'none', cursor: 'pointer',
                        padding: '5px 12px', borderRadius: 'var(--radius-md)',
                        border: '1px solid var(--border)',
                        background: selected ? 'var(--surface)' : 'var(--bg)',
                        color: selected ? 'var(--fg)' : 'var(--muted-fg)',
                        fontWeight: selected ? 600 : 400,
                        fontSize: 'var(--text-sm)',
                      }}>
                {d.label}
              </button>
            );
          })}
        </div>

        <p style={{ fontSize: 'var(--text-sm)', color: 'var(--muted-fg)',
                    margin: '0 0 var(--space-4)' }}>
          {dashboard.blurb}
        </p>

        {/* Keyed by uid and range so switching either remounts the frame. Without
            the key React reuses the iframe and only swaps src, which leaves the
            previous dashboard on screen through the reload. */}
        <iframe
          key={`${dashboard.uid}:${from}`}
          title={`${dashboard.label} — Grafana`}
          src={src}
          style={{
            width: '100%', height: 'clamp(520px, 78vh, 1100px)',
            border: '1px solid var(--border)', borderRadius: 'var(--radius-md)',
            background: 'var(--bg)', display: 'block',
          }}
        />

        <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                    marginTop: 'var(--space-4)', marginBottom: 0 }}>
          Framed from {GRAFANA} in kiosk mode, so the time picker and panel menus
          are hidden — use <strong>Open in Grafana</strong> to edit queries or
          inspect a panel's data. Blank panels here almost always mean Grafana is
          not reachable on that URL rather than that the metric is missing;{' '}
          <a href={GRAFANA} target="_blank" rel="noreferrer"
             style={{ color: 'inherit' }}>check it directly</a>.
        </p>
      </Card>
    </div>
  );
}
