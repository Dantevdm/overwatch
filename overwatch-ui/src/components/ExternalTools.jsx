import { IconExternal } from './Icons.jsx';

/**
 * The other web UIs in the stack, pinned to the foot of the sidebar.
 *
 * Everything here runs on a port nobody memorises. A reviewer who has to be told
 * "Grafana is on 3000, unless preflight moved it" will not go and look, so the
 * stack's own components end up unexamined — and the streaming step in
 * particular is the part you otherwise have to take on faith.
 *
 * These are deliberately *not* NavLinks. They leave the application, so they get
 * an outward arrow, a new tab, and a visual weight below the real navigation
 * rather than inside it. The grouping label is what makes that distinction
 * legible instead of looking like six sibling pages.
 *
 * URLs are injected at build time by Vite, from the same OW_*_PORT values
 * preflight may have reassigned, so the links follow a relocated port. The
 * fallbacks are the documented defaults, for `npm run dev` outside Docker.
 *
 * Note VITE_API_PUBLIC_URL rather than VITE_API_URL. The latter is the Vite dev
 * server's proxy target, resolved inside the UI container, where the API is
 * `http://fraud-api:8080`; a link built from it would send the browser to a
 * hostname it cannot resolve. Two audiences, two variables.
 */
const TOOLS = [
  {
    href: import.meta.env.VITE_GRAFANA_URL || 'http://localhost:3000',
    label: 'Grafana',
    hint: 'Dashboards and PromQL',
  },
  {
    // Straight to the logs dashboard rather than to Grafana's home, because
    // "it's in Grafana somewhere" is how a reviewer decides not to look.
    href: `${import.meta.env.VITE_GRAFANA_URL || 'http://localhost:3000'}/d/overwatch-logs`,
    label: 'Logs',
    hint: 'Every container, filtered by service and level',
  },
  {
    href: import.meta.env.VITE_PROMETHEUS_URL || 'http://localhost:9090',
    label: 'Prometheus',
    hint: 'Raw metrics and scrape targets',
  },
  {
    href: import.meta.env.VITE_CONSOLE_URL || 'http://localhost:8090',
    label: 'Redpanda',
    hint: 'Topics, messages, consumer lag',
  },
  {
    href: `${import.meta.env.VITE_API_PUBLIC_URL || 'http://localhost:8080'}/swagger-ui.html`,
    label: 'API docs',
    hint: 'OpenAPI, with try-it-out',
  },
];

export default function ExternalTools() {
  return (
    <div style={{
      padding: 'var(--space-3) var(--space-2) var(--space-4)',
      borderTop: '1px solid var(--side-border)',
    }}>
      <div style={{
        padding: '0 var(--space-3) var(--space-2)',
        fontSize: 'var(--text-xs)', fontWeight: 600,
        letterSpacing: '.06em', textTransform: 'uppercase',
        color: 'var(--side-fg)', opacity: 0.5,
      }}>
        External tools
      </div>

      {TOOLS.map((tool) => (
        <a key={tool.label} href={tool.href} target="_blank" rel="noreferrer"
           title={`${tool.hint} — ${tool.href}`}
           style={{
             display: 'flex', alignItems: 'center', justifyContent: 'space-between',
             gap: 8, padding: '5px var(--space-3)',
             borderRadius: 'var(--radius-md)', textDecoration: 'none',
             color: 'var(--side-fg)', opacity: 0.72,
             fontSize: 'var(--text-sm)',
           }}
           onMouseEnter={(e) => { e.currentTarget.style.opacity = 1; }}
           onMouseLeave={(e) => { e.currentTarget.style.opacity = 0.72; }}>
          <span>{tool.label}</span>
          {/* The same stroke set as the navigation icons rather than the ↗
              character, which renders at whatever weight the system font
              happens to give it and sat noticeably heavier than everything
              else in this sidebar. */}
          <span style={{ opacity: 0.7, display: 'flex' }}><IconExternal /></span>
        </a>
      ))}
    </div>
  );
}
