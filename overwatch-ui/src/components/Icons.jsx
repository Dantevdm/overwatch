/**
 * The icon set. Hand-written SVG paths rather than an icon package.
 *
 * Eleven icons at a few hundred bytes each is not a dependency's worth of
 * problem, and every icon library ships its own font-loading or tree-shaking
 * story to get wrong. These are all one shape: a 24x24 box, no fills, a 1.75
 * stroke that inherits `currentColor`, and round joins — so they sit at the same
 * visual weight as the sidebar's text instead of one being a heavy glyph and its
 * neighbour a thin outline.
 *
 * They are decoration, not information: every one is `aria-hidden`, because the
 * link text beside it already says where it goes, and a screen reader announcing
 * "graph, Dashboard" is worse than "Dashboard".
 */

function Icon({ children, size = 17 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
         stroke="currentColor" strokeWidth="1.75"
         strokeLinecap="round" strokeLinejoin="round"
         aria-hidden="true" focusable="false"
         // Stops the icon being squeezed when a long label wraps, and keeps the
         // optical baseline on the text rather than on the flex line's centre.
         style={{ flexShrink: 0, display: 'block' }}>
      {children}
    </svg>
  );
}

/** Dashboard — panels, because that is literally what the screen is. */
export const IconDashboard = () => (
  <Icon><rect x="3" y="3" width="7.5" height="7.5" rx="1.5" />
    <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.5" />
    <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.5" />
    <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.5" /></Icon>
);

/** Alerts — a warning triangle. The one place a conventional glyph beats a clever one. */
export const IconAlerts = () => (
  <Icon><path d="M10.7 3.8 2.6 17.7A1.5 1.5 0 0 0 3.9 20h16.2a1.5 1.5 0 0 0 1.3-2.3L13.3 3.8a1.5 1.5 0 0 0-2.6 0Z" />
    <path d="M12 9v4" /><path d="M12 16.5h.01" /></Icon>
);

/** Transactions — the two-way arrows of money moving. */
export const IconTransactions = () => (
  <Icon><path d="M4 8h13" /><path d="M14 5l3 3-3 3" />
    <path d="M20 16H7" /><path d="M10 13l-3 3 3 3" /></Icon>
);

/** Cardholders — a person, not a card. The screen is about who, not what. */
export const IconCardholders = () => (
  <Icon><circle cx="12" cy="8" r="3.5" />
    <path d="M4.5 20a7.5 7.5 0 0 1 15 0" /></Icon>
);

/** Rules — sliders, because rules here are weights and thresholds you move. */
export const IconRules = () => (
  <Icon><path d="M4 7h10" /><path d="M18 7h2" /><circle cx="16" cy="7" r="2" />
    <path d="M4 17h4" /><path d="M12 17h8" /><circle cx="10" cy="17" r="2" /></Icon>
);

/** Simulator — a play control, since the screen starts and stops the stream. */
export const IconSimulator = () => (
  <Icon><circle cx="12" cy="12" r="9" /><path d="M10 8.5l6 3.5-6 3.5V8.5Z" /></Icon>
);

/** Streams — records queued on a topic. */
export const IconStreams = () => (
  <Icon><ellipse cx="12" cy="6" rx="7.5" ry="3" />
    <path d="M4.5 6v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6" />
    <path d="M4.5 12v6c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-6" /></Icon>
);

/** Metrics — a rising series with its axes. */
export const IconMetrics = () => (
  <Icon><path d="M4 4v16h16" /><path d="M7.5 15l3.5-4.5 3 2.5 4-6" /></Icon>
);

/** API — angle brackets. */
export const IconApi = () => (
  <Icon><path d="M8.5 7.5 4 12l4.5 4.5" /><path d="M15.5 7.5 20 12l-4.5 4.5" />
    <path d="M13.5 5l-3 14" /></Icon>
);

/** Reports — a document with lines, plus the corner fold. */
export const IconReports = () => (
  <Icon><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z" />
    <path d="M14 3v5h5" /><path d="M9 13h6" /><path d="M9 17h4" /></Icon>
);

/** Logs — a terminal prompt. Used by the logging screen. */
export const IconLogs = () => (
  <Icon><rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="M7 9.5l2.5 2.5L7 14.5" /><path d="M12.5 15h4.5" /></Icon>
);

/** External link — the arrow leaving the box, for anything not served by this app. */
export const IconExternal = ({ size = 13 } = {}) => (
  <Icon size={size}><path d="M14 4h6v6" /><path d="M20 4l-8.5 8.5" />
    <path d="M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4" /></Icon>
);
