import { useCallback, useEffect, useRef, useState } from 'react';
import { useEntered } from '../motion.js';
import { SEVERITIES, SEVERITY_COLOR, rangeLabel } from '../api.js';

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

/**
 * The pixel width of the element this is attached to.
 *
 * Charts here draw into a viewBox and then have to be sized. The obvious pairing
 * — a fixed viewBox with `width: 100%; height: auto` — scales the whole drawing
 * to the container: a 720x220 chart in a 1600px card renders 1600x489, so a
 * dashboard on a wide screen became one enormous chart per screenful. Every text
 * label and stroke scaled with it, so it did not even look like a bigger chart,
 * it looked like a zoomed one.
 *
 * Measuring instead keeps the viewBox at the real pixel size: the height stays
 * what was asked for, strokes stay 2px, and the extra width becomes more chart
 * rather than more magnification.
 *
 * Falls back to `fallback` before the first measurement, so the first paint is a
 * reasonable chart rather than a zero-width one.
 *
 * `minimum` is a floor, and it belongs to the chart rather than to the hook. A
 * plot with a labelled axis stops being readable below about 240px and the right
 * answer there is to overflow and let the card scroll. A sparkline has no axis
 * and no minimum worth speaking of — it lives inside a 200px stat tile, and a
 * 240px floor is how its line came to be drawn straight out through the side of
 * the card.
 */
function useMeasuredWidth(fallback = 720, minimum = 240) {
  const ref = useRef(null);
  const [width, setWidth] = useState(fallback);

  const measure = useCallback((node) => {
    if (node) setWidth(Math.max(minimum, Math.round(node.getBoundingClientRect().width)));
  }, [minimum]);

  useEffect(() => {
    const node = ref.current;
    if (!node) return undefined;
    measure(node);
    // ResizeObserver rather than a window resize listener: the card can change
    // width without the window doing so — the sidebar collapsing, a grid
    // reflowing, a neighbouring panel appearing.
    const observer = new ResizeObserver(() => measure(node));
    observer.observe(node);
    return () => observer.disconnect();
  }, [measure]);

  return [ref, width];
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
/**
 * Time formatting that follows the bucket width.
 *
 * A 10-second bucket labelled "14:31" is indistinguishable from the five buckets
 * either side of it, and a 6-hour bucket labelled "14:31" implies a precision the
 * number does not have. So seconds appear only under a minute, and the date
 * appears only once buckets are an hour or more.
 */
function bucketFormat(bucketSeconds) {
  if (bucketSeconds < 60) {
    return { hour: '2-digit', minute: '2-digit', second: '2-digit' };
  }
  if (bucketSeconds < 3600) {
    return { hour: '2-digit', minute: '2-digit' };
  }
  return { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' };
}

/**
 * The two end labels for a time axis. Both ends can land in the same bucket
 * label -- a one-hour window with hourly buckets prints "11:00" twice -- so the
 * format steps up in resolution until the two read differently, and if even
 * seconds cannot separate them the right-hand one is dropped rather than
 * printed as a duplicate.
 */
export function axisEnds(data, rangeMinutes, bucketSeconds) {
  if (!data || data.length < 2) return [];
  const first = data[0].bucket;
  const last = data[data.length - 1].bucket;
  const formats = [
    tickFormat(rangeMinutes, bucketSeconds),
    { hour: '2-digit', minute: '2-digit', second: '2-digit' },
    { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' },
  ];
  for (const fmt of formats) {
    const a = new Date(first).toLocaleString('en-ZA', fmt);
    const b = new Date(last).toLocaleString('en-ZA', fmt);
    if (a !== b) return [{ i: 0, label: a }, { i: data.length - 1, label: b }];
  }
  return [{ i: 0, label: new Date(first).toLocaleString('en-ZA', formats[0]) }];
}

/** Short axis-end tick: the date only when the window spans more than a day. */
function tickFormat(rangeMinutes, bucketSeconds) {
  // Minutes included: "02 Sept, 14" reads as a year, "02 Sept, 14:00" does not.
  if (rangeMinutes > 1440) return { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' };
  if (bucketSeconds < 60) return { hour: '2-digit', minute: '2-digit', second: '2-digit' };
  return { hour: '2-digit', minute: '2-digit' };
}

export function AlertsOverTime({ data, volume, height = 220, bucketSeconds = 3600,
                                rangeMinutes = 1440, bucketName = 'hour' }) {
  const [hovered, setHovered] = useHover();
  const [box, W] = useMeasuredWidth();
  const H = height;
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;

  if (!data || data.length === 0) {
    // The ref goes on the empty state too. Without it the container is never
    // observed while empty, so the first render with data draws at the fallback
    // width and only corrects on the next resize.
    return <div ref={box} style={{ height, display: 'grid', placeItems: 'center',
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

  // Transaction volume behind the alert line, on its own scale.
  //
  // Two series on one plot with two scales is normally a way to imply a
  // correlation neither axis supports, so this one is deliberately not a second
  // line: it is a recessive backdrop with no axis of its own, there to answer
  // "was it quiet, or did we stop catching things" and nothing more. The alert
  // line keeps the only labelled scale, and the caption says so.
  const volumeMax = Math.max(1, ...(volume ?? []).map((d) => d.count));
  const barW = volume && volume.length > 1
    ? Math.max(1, (plotW / (volume.length - 1)) * 0.62)
    : 6;

  return (
    // The measured box, not the SVG, is what fills the card. The chart is then
    // drawn at that many real pixels rather than being scaled up to fit.
    <div ref={box}>
      <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} style={{ display: 'block' }}
           role="img" aria-label={`Alerts per ${bucketName} over the last ${rangeLabel(rangeMinutes)}, ${data.length} buckets`}>
        <defs>
          <linearGradient id="alertFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--chart-1)" stopOpacity="0.22" />
            <stop offset="100%" stopColor="var(--chart-1)" stopOpacity="0.02" />
          </linearGradient>
        </defs>

        {volume && volume.length > 1 && volume.map((d, i) => {
          const h = (d.count / volumeMax) * plotH;
          return h < 0.5 ? null : (
            <rect key={`v${i}`} x={x(i) - barW / 2} y={PAD.top + plotH - h}
                  width={barW} height={h} rx="1"
                  fill="var(--muted-fg)" opacity="0.10" />
          );
        })}

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

        <path className="ow-fade-in" d={area} fill="url(#alertFill)"
              style={{ animationDelay: '520ms' }} />
        {/* pathLength="1" is what makes one CSS rule draw any path: it
            renormalises the dash units, so `stroke-dasharray: 1` is the whole
            line whatever its actual length in user units. Without it every
            chart would need its own measured constant. */}
        <path className="ow-draw" pathLength="1"
              d={line} fill="none" stroke="var(--chart-1)" strokeWidth="2"
              strokeLinejoin="round" strokeLinecap="round" />

        {/* Emphasised endpoint, per mark specs. Faded in after the line has
            finished drawing, so the dot does not sit alone at the end of a
            line that has not arrived yet. */}
        <circle className="ow-fade-in"
                style={{ animationDelay: '860ms' }}
                cx={x(data.length - 1)} cy={y(data[data.length - 1].count)} r="4"
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
                {new Date(data[hovered].bucket).toLocaleString('en-ZA', bucketFormat(bucketSeconds))}
              </span>
            </Tooltip>
          </>
        )}

        <line x1={PAD.left} x2={W - PAD.right} y1={PAD.top + plotH} y2={PAD.top + plotH}
              stroke="var(--axis)" strokeWidth="1" />
        {axisEnds(data, rangeMinutes, bucketSeconds).map(({ i, label }) => (
          <text key={i} x={x(i)} y={H - 8} textAnchor={i === 0 ? 'start' : 'end'}
                fontSize="11" fill="var(--muted-fg)">{label}</text>
        ))}
      </svg>
    </div>
  );
}

/**
 * Severity over time — one line per severity across the same window and buckets
 * as the chart above.
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

export function SeverityOverTime({ data, height = 240, bucketSeconds = 3600, rangeMinutes = 1440, bucketName = 'hour' }) {
  const [hovered, setHovered] = useHover();
  const [box, W] = useMeasuredWidth();
  const H = height;
  const RIGHT = 74;                       // room for the direct end labels
  const plotW = W - PAD.left - RIGHT;
  const plotH = H - PAD.top - PAD.bottom;

  if (!data || data.length === 0) {
    return <div ref={box} style={{ height, display: 'grid', placeItems: 'center',
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

  return (
    <div ref={box}>
      <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} style={{ display: 'block' }}
           role="img" aria-label={
             `Alerts per ${bucketName} by severity over the last ${rangeLabel(rangeMinutes)}. Totals: ` +
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
                  strokeLinejoin="round" strokeLinecap="round"
                  // Only the solid bands draw themselves. A band whose identity
                  // IS its dash pattern cannot also use the dash array to
                  // animate, and overriding it would draw the line and then
                  // change its appearance — worse than not animating it.
                  className={stroke.dash ? 'ow-fade-in' : 'ow-draw'}
                  pathLength={stroke.dash ? undefined : 1}
                  style={{ animationDelay: `${SEVERITIES.indexOf(sev) * 110}ms` }} />
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
                {new Date(data[hovered].bucket).toLocaleString('en-ZA', bucketFormat(bucketSeconds))}
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
        {axisEnds(data, rangeMinutes, bucketSeconds).map(({ i, label }) => (
          <text key={i} x={x(i)} y={H - 8} textAnchor={i === 0 ? 'start' : 'end'}
                fontSize="11" fill="var(--muted-fg)">{label}</text>
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
  // Bars grow out of the left on first paint. A transition rather than a
  // keyframe, because the target width is a value only JavaScript knows.
  const entered = useEntered();
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
            <div className="ow-grow" style={{
              width: entered ? `${(r.value / max) * 100}%` : 0, height: '100%',
              background: r.shadow ? 'var(--chart-3)' : 'var(--chart-1)',
              borderRadius: 4, minWidth: r.value > 0 && entered ? 4 : 0,
              opacity: hovered === null || hovered === i ? 1 : 0.6,
              // Two transitions with different durations: the width is the
              // entrance and wants easing, the opacity is a hover response and
              // wants to feel immediate. Staggered down the list, capped so a
              // long list does not end with a bar arriving a second late.
              transition: 'opacity .12s, width 620ms cubic-bezier(.22,.72,.28,1)',
              transitionDelay: `0s, ${Math.min(i * 45, 400)}ms`,
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

/**
 * A sparkline: shape only, no axes, no labels.
 *
 * It goes inside a stat tile, where the number already carries the value and the
 * only thing left to add is the direction it came from. Drawn with a baseline so
 * a flat series still reads as a line rather than as an empty tile, and with the
 * last point marked, because "where it is now" is the one point on a sparkline
 * anyone actually looks for.
 */
export function Sparkline({ data, height = 30, color = 'var(--chart-1)', label }) {
  const [box, W] = useMeasuredWidth(120, 40);
  const values = (data ?? []).map((d) => (typeof d === 'number' ? d : d.count));

  if (values.length < 2) {
    return <div ref={box} style={{ height, overflow: 'hidden' }} />;
  }

  // Inset by the marker's radius on every side, so the last point's dot lands
  // inside the box rather than half-outside it.
  const R = 3;
  const max = Math.max(1, ...values);
  const min = Math.min(...values);
  const x = (i) => R + (i / (values.length - 1)) * (W - R * 2);
  // A series that never changes draws along the middle. Scaled from zero it
  // would sit hard against the top edge and read as a chart that is clipped.
  const y = max === min
    ? () => height / 2
    : (v) => height - R - (v / max) * (height - R * 2);

  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(v)}`).join(' ');
  const area = `${line} L${x(values.length - 1)},${height} L${x(0)},${height} Z`;
  const lastX = x(values.length - 1);
  const lastY = y(values[values.length - 1]);

  return (
    <div ref={box} style={{ height, overflow: 'hidden' }}>
      <svg viewBox={`0 0 ${W} ${height}`} width={W} height={height}
           style={{ display: 'block' }}
           role="img" aria-label={label ?? `Trend over the last ${values.length} buckets`}>
        <path className="ow-fade-in" d={area} fill={color} opacity="0.12"
              style={{ animationDelay: '400ms' }} />
        <path className="ow-draw" pathLength="1"
              d={line} fill="none" stroke={color} strokeWidth="1.75"
              strokeLinejoin="round" strokeLinecap="round" />
        <circle cx={lastX} cy={lastY} r="2.5" fill={color} />
      </svg>
    </div>
  );
}

/**
 * The severity mix as one full-width bar, with the counts listed beneath it.
 *
 * A single stacked bar rather than a pie: the question is what share of alerts is
 * at the serious end, and comparing arc lengths is measurably worse at that than
 * comparing lengths along a common baseline. Segments narrower than a couple of
 * pixels are dropped from the bar rather than drawn as a sliver that reads as a
 * rendering artefact — the list below always carries every severity, so nothing
 * disappears, it just stops pretending to be visible.
 */
export function SeverityMix({ counts, height = 14 }) {
  const total = SEVERITIES.reduce((sum, s) => sum + (counts?.[s] ?? 0), 0);
  const entered = useEntered();

  if (total === 0) {
    return <div style={{ color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
      No alerts raised yet.
    </div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
      <div role="img"
           aria-label={`Severity mix of ${total} alerts: ` +
             SEVERITIES.map((s) => `${counts[s] ?? 0} ${s}`).join(', ')}
           style={{ display: 'flex', gap: 2, height, borderRadius: 4, overflow: 'hidden' }}>
        {SEVERITIES.map((s) => {
          const share = (counts?.[s] ?? 0) / total;
          if (share <= 0.004) return null;
          // flex-grow is animatable, so the whole bar unfolds left to right
          // from nothing without any width arithmetic.
          return <div key={s} className="ow-grow"
                      title={`${s}: ${(share * 100).toFixed(1)}%`}
                      style={{ flex: entered ? share : 0,
                               background: SEVERITY_COLOR[s],
                               transition: 'flex-grow 700ms cubic-bezier(.22,.72,.28,1)' }} />;
        })}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
        {SEVERITIES.map((s) => {
          const n = counts?.[s] ?? 0;
          return (
            <div key={s} style={{ display: 'grid', gridTemplateColumns: '10px 1fr auto auto',
                                  alignItems: 'center', gap: 'var(--space-3)',
                                  fontSize: 'var(--text-xs)' }}>
              <span style={{ width: 10, height: 10, borderRadius: 2,
                             background: SEVERITY_COLOR[s] }} />
              <span style={{ color: 'var(--fg)' }}>{s}</span>
              <span style={{ fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
                {n.toLocaleString('en-ZA')}
              </span>
              <span style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--muted-fg)',
                             minWidth: 44, textAlign: 'right' }}>
                {((n / total) * 100).toFixed(1)}%
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
