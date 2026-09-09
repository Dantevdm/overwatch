import { useState } from 'react';
import { SEVERITIES, SEVERITY_COLOR } from '../api.js';

/**
 * Charts are hand-authored SVG rather than a charting library.
 *
 * Three reasons. The marks need to follow specific rules (2px lines, >= 8px hit
 * targets, 2px surface gaps between adjacent fills, direct labels rather than a
 * number on every point) that a library fights you on. Chart text has to take its
 * colour from the theme tokens so it reads in both light and dark. And a
 * dashboard that ships no chart library is a dashboard with one fewer dependency
 * to keep patched.
 */

const PAD = { top: 16, right: 16, bottom: 28, left: 44 };

function useHover() {
  const [hovered, setHovered] = useState(null);
  return [hovered, setHovered];
}

function Tooltip({ x, y, width, children }) {
  // Flip to the left of the cursor near the right edge so it never overflows.
  const flip = x > width - 150;
  return (
    <foreignObject x={flip ? x - 156 : x + 8} y={Math.max(0, y - 34)} width={150} height={70}
                   style={{ pointerEvents: 'none', overflow: 'visible' }}>
      <div style={{
        background: 'var(--fg)', color: 'var(--bg)',
        padding: '6px 9px', borderRadius: 'var(--radius-md)',
        fontSize: 12, lineHeight: 1.4, fontFamily: 'var(--font-sans)',
        boxShadow: 'var(--shadow-lg)', display: 'inline-block', whiteSpace: 'nowrap',
      }}>
        {children}
      </div>
    </foreignObject>
  );
}

/**
 * Alerts over time. One series, so no legend — the title names it.
 */
export function AlertsOverTime({ data, height = 220 }) {
  const [hovered, setHovered] = useHover();
  const W = 720;
  const H = height;
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;

  if (!data || data.length === 0) {
    return <div style={{ height, display: 'grid', placeItems: 'center',
                         color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
      No alerts in this window yet.
    </div>;
  }

  const max = Math.max(1, ...data.map((d) => d.count));
  // Round the axis up to something a person would choose.
  const step = Math.max(1, Math.ceil(max / 4));
  const top = step * 4;

  const x = (i) => PAD.left + (data.length === 1 ? plotW / 2 : (i / (data.length - 1)) * plotW);
  const y = (v) => PAD.top + plotH - (v / top) * plotH;

  const line = data.map((d, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(d.count)}`).join(' ');
  const area = `${line} L${x(data.length - 1)},${PAD.top + plotH} L${x(0)},${PAD.top + plotH} Z`;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block' }}
         role="img" aria-label={`Alerts per hour over the last ${data.length} hours`}>
      <defs>
        <linearGradient id="alertFill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--chart-1)" stopOpacity="0.22" />
          <stop offset="100%" stopColor="var(--chart-1)" stopOpacity="0.02" />
        </linearGradient>
      </defs>

      {/* Recessive gridlines; every label names a value the scale reaches. */}
      {[0, 1, 2, 3, 4].map((i) => {
        const v = (top / 4) * i;
        return (
          <g key={i}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)}
                  stroke="var(--grid)" strokeWidth="1" />
            <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end"
                  fontSize="11" fill="var(--muted-fg)"
                  style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</text>
          </g>
        );
      })}

      <path d={area} fill="url(#alertFill)" />
      <path d={line} fill="none" stroke="var(--chart-1)" strokeWidth="2"
            strokeLinejoin="round" strokeLinecap="round" />

      {/* Emphasised endpoint, per mark specs. */}
      <circle cx={x(data.length - 1)} cy={y(data[data.length - 1].count)} r="4"
              fill="var(--chart-1)" stroke="var(--chart-bg)" strokeWidth="2" />

      {/* Hit targets larger than the marks. */}
      {data.map((d, i) => (
        <rect key={i} x={x(i) - plotW / data.length / 2} y={PAD.top}
              width={plotW / data.length} height={plotH} fill="transparent"
              onMouseEnter={() => setHovered(i)} onMouseLeave={() => setHovered(null)} />
      ))}

      {hovered !== null && (
        <>
          <line x1={x(hovered)} x2={x(hovered)} y1={PAD.top} y2={PAD.top + plotH}
                stroke="var(--axis)" strokeWidth="1" strokeDasharray="3 3" />
          <circle cx={x(hovered)} cy={y(data[hovered].count)} r="5"
                  fill="var(--chart-1)" stroke="var(--chart-bg)" strokeWidth="2" />
          <Tooltip x={x(hovered)} y={y(data[hovered].count)} width={W}>
            <strong>{data[hovered].count}</strong> alert{data[hovered].count === 1 ? '' : 's'}
            <br />
            <span style={{ opacity: 0.75 }}>
              {new Date(data[hovered].hour).toLocaleString('en-ZA',
                { hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })}
            </span>
          </Tooltip>
        </>
      )}

      <line x1={PAD.left} x2={W - PAD.right} y1={PAD.top + plotH} y2={PAD.top + plotH}
            stroke="var(--axis)" strokeWidth="1" />
      {data.length > 1 && [0, data.length - 1].map((i) => (
        <text key={i} x={x(i)} y={H - 8} textAnchor={i === 0 ? 'start' : 'end'}
              fontSize="11" fill="var(--muted-fg)">
          {new Date(data[i].hour).toLocaleTimeString('en-ZA',
            { hour: '2-digit', minute: '2-digit' })}
        </text>
      ))}
    </svg>
  );
}

/**
 * Severity breakdown as a single stacked bar.
 *
 * Severity is ordinal, so it uses one hue stepped light to dark rather than four
 * separate hues — four measured hues put MEDIUM and HIGH within 4 ΔE, which is
 * indistinguishable even with normal colour vision. Segments carry direct labels
 * and a legend, so the encoding never rests on hue alone.
 */
export function SeverityBreakdown({ counts }) {
  const [hovered, setHovered] = useHover();
  const total = SEVERITIES.reduce((sum, s) => sum + (counts?.[s] ?? 0), 0);

  if (!total) {
    return <div style={{ padding: 'var(--space-6)', textAlign: 'center',
                         color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
      No alerts yet.
    </div>;
  }

  const W = 720;
  const barH = 34;
  let cursor = 0;
  const segments = SEVERITIES.map((s) => {
    const value = counts[s] ?? 0;
    const width = (value / total) * W;
    const seg = { severity: s, value, x: cursor, width, pct: (value / total) * 100 };
    cursor += width;
    return seg;
  }).filter((s) => s.value > 0);

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${barH}`} style={{ width: '100%', height: barH, display: 'block' }}
           role="img" aria-label={
             `Alert severity: ${segments.map((s) => `${s.value} ${s.severity}`).join(', ')}`
           }>
        {segments.map((s, i) => (
          <g key={s.severity}
             onMouseEnter={() => setHovered(i)} onMouseLeave={() => setHovered(null)}>
            {/* 2px surface gap between adjacent fills. */}
            <rect x={s.x} y="0" width={Math.max(0, s.width - 2)} height={barH}
                  fill={SEVERITY_COLOR[s.severity]}
                  rx="4"
                  opacity={hovered === null || hovered === i ? 1 : 0.55} />
            {s.width > 62 && (
              <text x={s.x + s.width / 2 - 1} y={barH / 2 + 4} textAnchor="middle"
                    fontSize="12" fontWeight="600" fill="#ffffff"
                    style={{ pointerEvents: 'none', fontVariantNumeric: 'tabular-nums' }}>
                {s.value}
              </text>
            )}
          </g>
        ))}
      </svg>

      {/* Legend: identity is never colour-alone. */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-4)',
                    marginTop: 'var(--space-4)' }}>
        {segments.map((s) => (
          <span key={s.severity} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                          fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
            <span aria-hidden="true" style={{
              width: 10, height: 10, borderRadius: 3, background: SEVERITY_COLOR[s.severity],
            }} />
            <strong style={{ color: 'var(--fg)' }}>{s.severity}</strong>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>
              {s.value} ({s.pct.toFixed(0)}%)
            </span>
          </span>
        ))}
      </div>
    </div>
  );
}

/**
 * Horizontal bars for rule fire counts. One series, so no legend; values are
 * direct-labelled at the end of each bar rather than on an axis.
 */
export function RuleBars({ rows, height = 26 }) {
  const [hovered, setHovered] = useHover();
  if (!rows || rows.length === 0) {
    return <div style={{ color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
      No rule hits recorded yet.
    </div>;
  }
  const max = Math.max(1, ...rows.map((r) => r.value));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
      {rows.map((r, i) => (
        <div key={r.label}
             onMouseEnter={() => setHovered(i)} onMouseLeave={() => setHovered(null)}
             style={{ display: 'grid', gridTemplateColumns: '150px 1fr 56px',
                      alignItems: 'center', gap: 'var(--space-3)', minHeight: height }}>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--fg)',
                         overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                title={r.label}>
            {r.label}
          </span>
          <div style={{ background: 'var(--surface)', borderRadius: 4, height: 14 }}>
            <div style={{
              width: `${(r.value / max) * 100}%`, height: '100%',
              background: r.shadow ? 'var(--chart-3)' : 'var(--chart-1)',
              borderRadius: 4, minWidth: r.value > 0 ? 4 : 0,
              opacity: hovered === null || hovered === i ? 1 : 0.6,
              transition: 'opacity .12s',
            }} />
          </div>
          <span style={{ fontSize: 'var(--text-xs)', textAlign: 'right',
                         fontVariantNumeric: 'tabular-nums', color: 'var(--muted-fg)' }}>
            {r.value.toLocaleString('en-ZA')}
          </span>
        </div>
      ))}
    </div>
  );
}
