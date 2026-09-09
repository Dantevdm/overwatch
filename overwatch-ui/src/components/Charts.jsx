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

function Tooltip({ x, y, width, height = 70, children }) {
  // Flip to the left of the cursor near the right edge so it never overflows.
  const flip = x > width - 150;
  return (
    <foreignObject x={flip ? x - 156 : x + 8} y={Math.max(0, y - 34)} width={150} height={height}
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
 * Severity over time — one line per severity across the same hourly window.
 *
 * The series read together rather than stacked: the question a fraud team asks of
 * this chart is "is the serious end of the mix growing", and a stack answers the
 * volume question instead, by making every line depend on the ones beneath it.
 *
 * On colour. Severity is ordinal, so the ramp is one hue stepped light to dark
 * (measured: hue spread 4.4°, lightness strictly decreasing 0.715 → 0.421 in
 * light, 0.892 → 0.619 in dark) rather than four separate hues — four measured
 * hues put MEDIUM and HIGH within 4 ΔE, indistinguishable even with normal colour
 * vision. But a four-step single hue cannot also clear the categorical
 * adjacent-pair floor: the usable lightness band is about 0.30 wide, so four
 * steps land ~0.10 apart and no arrangement of them reaches ΔE 15. That is a
 * property of ordinal ramps, not a bug to tune away, and it is why the palette
 * validator's categorical checks are the wrong test here — monotonic lightness is
 * the right one, and the ramp passes it.
 *
 * The consequence is real though: four thin lines that differ only in lightness
 * are hard to follow where they cross. So severity is encoded three ways at once,
 * all of them ordinal — lightness, stroke weight, and dash, running from a fine
 * dotted LOW to a solid heavy CRITICAL. Each line also carries its name at its
 * right-hand end, and the legend repeats it with the window total. Identity never
 * rests on hue, which is also what makes this chart survive printing, forced
 * colours, and the lightest step's sub-3:1 contrast against the surface.
 */
/**
 * Stroke encoding, ordinal alongside the colour ramp: finer and more broken as
 * severity falls, solid and heaviest at CRITICAL.
 */
const SEVERITY_STROKE = {
  LOW:      { width: 1.5, dash: '2 4' },
  MEDIUM:   { width: 1.75, dash: '6 3' },
  HIGH:     { width: 2, dash: '10 3' },
  CRITICAL: { width: 2.5, dash: null },
};

export function SeverityOverTime({ data, height = 240 }) {
  const [hovered, setHovered] = useHover();
  const W = 720;
  const H = height;
  const RIGHT = 74;                       // room for the direct end labels
  const plotW = W - PAD.left - RIGHT;
  const plotH = H - PAD.top - PAD.bottom;

  if (!data || data.length === 0) {
    return <div style={{ height, display: 'grid', placeItems: 'center',
                         color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
      No alerts in this window yet.
    </div>;
  }

  const at = (d, sev) => d.counts?.[sev] ?? 0;
  const totals = Object.fromEntries(
    SEVERITIES.map((s) => [s, data.reduce((sum, d) => sum + at(d, s), 0)]));

  const max = Math.max(1, ...data.flatMap((d) => SEVERITIES.map((s) => at(d, s))));
  const step = Math.max(1, Math.ceil(max / 4));
  const top = step * 4;

  const x = (i) => PAD.left + (data.length === 1 ? plotW / 2 : (i / (data.length - 1)) * plotW);
  const y = (v) => PAD.top + plotH - (v / top) * plotH;

  // Direct labels sit at each line's last point, nudged apart so two series that
  // end at the same value do not print on top of one another.
  const MIN_GAP = 13;
  const last = data[data.length - 1];
  const endLabels = SEVERITIES
    .map((sev) => ({ sev, value: at(last, sev), y: y(at(last, sev)) }))
    .sort((a, b) => a.y - b.y);
  for (let i = 1; i < endLabels.length; i++) {
    if (endLabels[i].y - endLabels[i - 1].y < MIN_GAP) {
      endLabels[i].y = endLabels[i - 1].y + MIN_GAP;
    }
  }

  const hourLabel = (iso) => new Date(iso).toLocaleTimeString('en-ZA',
    { hour: '2-digit', minute: '2-digit' });

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block' }}
           role="img" aria-label={
             `Alerts per hour by severity over the last ${data.length} hours. Totals: ` +
             SEVERITIES.map((s) => `${totals[s]} ${s}`).join(', ')
           }>

        {/* Recessive gridlines; every label names a value the scale reaches. */}
        {[0, 1, 2, 3, 4].map((i) => {
          const v = (top / 4) * i;
          return (
            <g key={i}>
              <line x1={PAD.left} x2={W - RIGHT} y1={y(v)} y2={y(v)}
                    stroke="var(--grid)" strokeWidth="1" />
              <text x={PAD.left - 8} y={y(v) + 4} textAnchor="end"
                    fontSize="11" fill="var(--muted-fg)"
                    style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</text>
            </g>
          );
        })}

        {SEVERITIES.map((sev) => {
          const path = data
            .map((d, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(at(d, sev))}`)
            .join(' ');
          const stroke = SEVERITY_STROKE[sev];
          return (
            <path key={sev} d={path} fill="none" stroke={SEVERITY_COLOR[sev]}
                  strokeWidth={stroke.width} strokeDasharray={stroke.dash ?? undefined}
                  strokeLinejoin="round" strokeLinecap="round" />
          );
        })}

        {/* Emphasised endpoints, ringed in the surface colour so overlapping
            marks stay separable. */}
        {SEVERITIES.map((sev) => (
          <circle key={sev} cx={x(data.length - 1)} cy={y(at(last, sev))} r="3.5"
                  fill={SEVERITY_COLOR[sev]} stroke="var(--chart-bg)" strokeWidth="2" />
        ))}

        {endLabels.map((l) => (
          <text key={l.sev} x={W - RIGHT + 8} y={l.y + 4} fontSize="11" fontWeight="600"
                fill={SEVERITY_COLOR[l.sev]}>
            {l.sev}
          </text>
        ))}

        {/* Hit targets wider than the marks. */}
        {data.map((d, i) => (
          <rect key={i} x={x(i) - plotW / data.length / 2} y={PAD.top}
                width={plotW / data.length} height={plotH} fill="transparent"
                onMouseEnter={() => setHovered(i)} onMouseLeave={() => setHovered(null)} />
        ))}

        {hovered !== null && (
          <>
            <line x1={x(hovered)} x2={x(hovered)} y1={PAD.top} y2={PAD.top + plotH}
                  stroke="var(--axis)" strokeWidth="1" strokeDasharray="3 3" />
            {SEVERITIES.map((sev) => (
              <circle key={sev} cx={x(hovered)} cy={y(at(data[hovered], sev))} r="4.5"
                      fill={SEVERITY_COLOR[sev]} stroke="var(--chart-bg)" strokeWidth="2" />
            ))}
            <Tooltip x={x(hovered)} y={PAD.top + 46} width={W - RIGHT} height={104}>
              <div style={{ opacity: 0.75, marginBottom: 3 }}>
                {new Date(data[hovered].hour).toLocaleString('en-ZA',
                  { hour: '2-digit', minute: '2-digit', day: 'numeric', month: 'short' })}
              </div>
              {SEVERITIES.map((sev) => (
                <div key={sev} style={{ display: 'flex', justifyContent: 'space-between',
                                        gap: 12 }}>
                  <span>{sev}</span>
                  <strong style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {at(data[hovered], sev)}
                  </strong>
                </div>
              ))}
            </Tooltip>
          </>
        )}

        <line x1={PAD.left} x2={W - RIGHT} y1={PAD.top + plotH} y2={PAD.top + plotH}
              stroke="var(--axis)" strokeWidth="1" />
        {data.length > 1 && [0, data.length - 1].map((i) => (
          <text key={i} x={x(i)} y={H - 8} textAnchor={i === 0 ? 'start' : 'end'}
                fontSize="11" fill="var(--muted-fg)">
            {hourLabel(data[i].hour)}
          </text>
        ))}
      </svg>

      {/* Legend carries the window total for each series, which is what the
          stacked bar used to say. */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 'var(--space-4)',
                    marginTop: 'var(--space-4)' }}>
        {SEVERITIES.map((sev) => (
          <span key={sev} style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                   fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
            <svg aria-hidden="true" width="18" height="8" style={{ flexShrink: 0 }}>
              <line x1="0" y1="4" x2="18" y2="4" stroke={SEVERITY_COLOR[sev]}
                    strokeWidth={SEVERITY_STROKE[sev].width}
                    strokeDasharray={SEVERITY_STROKE[sev].dash ?? undefined}
                    strokeLinecap="round" />
            </svg>
            <strong style={{ color: 'var(--fg)' }}>{sev}</strong>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>{totals[sev]}</span>
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
