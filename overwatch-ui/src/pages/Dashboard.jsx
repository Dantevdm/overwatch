import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  api, zar, shortTime, RANGES, DEFAULT_RANGE, bucketLabel, rangeLabel, SEVERITY_COLOR,
} from '../api.js';
import { Card, StatTile, Empty, SegmentedControl, SeverityBadge } from '../components/Primitives.jsx';
import { useNewIds } from '../motion.js';
import { AlertsOverTime, SeverityOverTime, RuleBars, Sparkline, SeverityMix } from '../components/Charts.jsx';

/**
 * The operations view: what the pipeline has seen, what it flagged, and what is
 * on fire right now.
 *
 * Laid out as a grid rather than a column of full-width cards. A dashboard is
 * read by glancing, and glancing only works when the things being compared are
 * on screen together — a stack of 600px-tall cards is a slideshow with extra
 * steps, and it got that way because the charts scaled themselves to whatever
 * width they were given. The grid is two columns from 1100px up and collapses to
 * one on narrow screens, where a stack is the correct answer.
 */
export default function Dashboard() {
  const [stats, setStats] = useState(null);
  const [perf, setPerf] = useState([]);
  const [feed, setFeed] = useState([]);
  const [error, setError] = useState(null);
  const [range, setRange] = useState(DEFAULT_RANGE);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [s, p, f] = await Promise.all([
          api.dashboard({ rangeMinutes: range }),
          api.rulePerformance(),
          // The live feed is the newest alerts regardless of the chart window:
          // it answers "what just happened", which a range filter would only
          // ever make emptier.
          api.alerts({ size: 8 }),
        ]);
        if (!cancelled) { setStats(s); setPerf(p); setFeed(f.content ?? []); setError(null); }
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    };
    load();
    // Polling rather than websockets: five seconds is imperceptible on a
    // dashboard and costs a fraction of the complexity. Recorded as a
    // deliberate trade in the project plan, not an oversight.
    //
    // Five seconds regardless of range. A 5-minute window with 10-second buckets
    // does move visibly between polls, but a faster poll for short windows would
    // mean the refresh rate changing under the reader as they switch range, which
    // is more disorienting than a chart that lags by a few seconds.
    const timer = setInterval(load, 5000);
    // `range` is a dependency: switching window refetches immediately rather
    // than waiting out the current poll interval.
    return () => { cancelled = true; clearInterval(timer); };
  }, [range]);

  if (error) {
    return <Empty>Could not reach the API — {error}</Empty>;
  }
  if (!stats) {
    return <Empty>Loading…</Empty>;
  }

  // The server echoes back what it actually applied after clamping, so the
  // control and the axis labels always describe the data on screen rather than
  // the request that produced it.
  const bucketName = bucketLabel(stats.bucketSeconds);

  const ruleRows = perf
    .filter((r) => r.timesFired > 0 || r.shadowHits > 0)
    .map((r) => ({
      label: r.name,
      value: r.state === 'SHADOW' ? r.shadowHits : r.timesFired,
      shadow: r.state === 'SHADOW',
    }))
    .sort((a, b) => b.value - a.value);

  // Categories by volume, top eight. The tail of a merchant-category
  // distribution is long and uninformative, and eight bars is what fits beside
  // the rules card without either one scrolling.
  const categoryRows = Object.entries(stats.transactionsByCategory ?? {})
    .map(([label, value]) => ({ label, value }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 8);

  // Alert rate within the window, which is the number a fraud team actually
  // tunes against — a lifetime rate hides today entirely.
  const windowAlerts = sum(stats.alertsOverTime);
  const windowTransactions = sum(stats.transactionsOverTime);
  const windowRate = windowTransactions > 0 ? (windowAlerts / windowTransactions) * 100 : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      {/* The range control governs every chart below, so it sits above them all
          rather than inside whichever card happened to be first. */}
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
                    gap: 'var(--space-4)', flexWrap: 'wrap' }}>
        <p style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
          Charts show the last {rangeLabel(stats.rangeMinutes)}, bucketed every {bucketName},
          by when each transaction happened. The tiles keep their own fixed periods.
        </p>
        <SegmentedControl
          label="Time range for the charts"
          value={stats.rangeMinutes}
          onChange={setRange}
          options={RANGES.map((r) => ({
            value: r.minutes, label: r.label, title: `Last ${r.label}`,
          }))}
        />
      </div>

      <div style={{ display: 'grid', gap: 'var(--space-4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))' }}>
        {/* The figures roll when they change, and the tiles enter in reading
            order 60ms apart. minDelta per tile, because "worth animating" is
            about the quantity: a count that moves in the hundreds should roll,
            and a mean that moves in the third decimal should not. */}
        <StatTile label="Transactions" delay={0}
                  numeric={stats.totalTransactions} format={count} minDelta={0}
                  sub={`${stats.transactionsLastHour.toLocaleString('en-ZA')} in the last hour`}
                  chart={<Sparkline data={stats.transactionsOverTime} color="var(--chart-2)"
                                    label={`Transaction volume per ${bucketName}`} />} />
        <StatTile label="Alerts raised" delay={60}
                  numeric={stats.totalAlerts} format={count}
                  sub={windowRate === null
                    ? 'no traffic in this window'
                    : `${windowRate.toFixed(2)}% of the last ${rangeLabel(stats.rangeMinutes)}`}
                  chart={<Sparkline data={stats.alertsOverTime}
                                    label={`Alerts per ${bucketName}`} />} />
        <StatTile label="Open alerts" delay={120}
                  numeric={stats.openAlerts} format={count}
                  tone={stats.openAlerts > 0 ? 'warning' : undefined}
                  sub="awaiting an analyst" />
        <StatTile label="Flagged value" delay={180}
                  numeric={stats.flaggedLast24hZar} format={zar}
                  tone="danger" sub="last 24 hours of activity" />
        {/* Two decimals, so anything under 0.005 is invisible anyway — rolling
            it would animate a digit that does not change. */}
        <StatTile label="Mean risk score" delay={240}
                  numeric={stats.averageRiskScore} format={score} minDelta={0.005}
                  sub="across all alerts" />
      </div>

      {/* Charts two abreast. minmax(0, …) rather than 1fr: a grid track sized
          1fr refuses to shrink below its content, and an SVG measuring itself
          against its track then never gets a chance to get smaller, so the row
          overflows on the way down instead of reflowing. */}
      <div style={{ display: 'grid', gap: 'var(--space-5)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 420px), 1fr))' }}>

        <Card title={`Alerts per ${bucketName}`}>
          <AlertsOverTime data={stats.alertsOverTime}
                          volume={stats.transactionsOverTime}
                          height={180}
                          bucketSeconds={stats.bucketSeconds}
                          rangeMinutes={stats.rangeMinutes}
                          bucketName={bucketName} />
          <p style={caption}>
            The faint bars behind the line are transaction volume, drawn on their
            own scale so a quiet stretch does not read as a detector that stopped
            working. Only the alert count is on the labelled axis.
          </p>
        </Card>

        <Card title="Severity mix">
          <SeverityMix counts={stats.alertsBySeverity} />
          <p style={caption}>
            Every alert ever raised, by severity. The bar is share; the list is
            counts, because a percentage of an unstated total says nothing.
          </p>
        </Card>

        <Card title="Severity over time">
          <SeverityOverTime data={stats.severityOverTime}
                            height={200}
                            bucketSeconds={stats.bucketSeconds}
                            rangeMinutes={stats.rangeMinutes}
                            bucketName={bucketName} />
        </Card>

        <Card title="Latest alerts"
              action={<Link to="/alerts" style={linkStyle}>All alerts →</Link>}>
          <AlertFeed rows={feed} />
        </Card>

        <Card title="Which rules are firing">
          <RuleBars rows={ruleRows} />
          {ruleRows.some((r) => r.shadow) && (
            <p style={caption}>
              Lighter bars are rules in shadow — evaluated against live traffic,
              recording what they would have caught, raising no alerts.
            </p>
          )}
        </Card>

        <Card title="Where the money goes">
          <RuleBars rows={categoryRows} />
          <p style={caption}>
            Transactions by merchant category, top {categoryRows.length}. This is
            the baseline the watchlist rule is an exception to.
          </p>
        </Card>
      </div>
    </div>
  );
}

function sum(series) {
  return (series ?? []).reduce((total, b) => total + b.count, 0);
}

/**
 * The newest alerts, as a list rather than a table.
 *
 * A table needs column headers to be readable, and headers cost a row of height
 * that this card cannot spare beside a chart. Each line carries severity, the
 * amount, which rules fired and how long ago — which is every question someone
 * glancing at a live feed asks before deciding whether to open it.
 */
/**
 * The last few alerts.
 *
 * The feed re-renders wholesale every five seconds, so a genuinely new alert
 * would otherwise just be a row at the top of a list of eight, indistinguishable
 * from the seven that were already there. useNewIds finds the arrivals and each
 * one flashes once, then decays — a highlight that stays becomes a category
 * rather than an event. Nothing flashes on first load, when every row is new.
 */
function AlertFeed({ rows }) {
  const fresh = useNewIds((rows ?? []).map((a) => a.id));
  if (!rows || rows.length === 0) {
    return <div style={{ color: 'var(--muted-fg)', fontSize: 'var(--text-sm)' }}>
      Nothing flagged yet.
    </div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column' }}>
      {rows.map((a, i) => (
        <Link key={a.id} to={`/alerts?focus=${a.id}`}
              className={fresh.has(a.id) ? 'ow-flash' : undefined}
              style={{
                display: 'grid', gridTemplateColumns: 'auto 1fr auto',
                alignItems: 'center', gap: 'var(--space-3)',
                padding: 'var(--space-3) 0',
                borderTop: i === 0 ? 'none' : '1px solid var(--border)',
                textDecoration: 'none', color: 'inherit',
                // The flash paints the row's own background, so it needs a
                // paint box that covers the full width of the card's padding.
                marginLeft: 'calc(var(--space-3) * -1)',
                marginRight: 'calc(var(--space-3) * -1)',
                paddingLeft: 'var(--space-3)', paddingRight: 'var(--space-3)',
                borderRadius: 'var(--radius-sm)',
              }}>
          {/* A severity stripe as well as the badge: at a glance down the list
              the stripe is what makes a run of CRITICALs visible as a block. */}
          <span style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
            <span aria-hidden="true"
                  style={{ width: 3, height: 26, borderRadius: 2,
                           background: SEVERITY_COLOR[a.severity] ?? 'var(--muted-fg)' }} />
            <SeverityBadge severity={a.severity} />
          </span>
          <span style={{ minWidth: 0 }}>
            <span style={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
              {zar(a.amount)}
            </span>
            <span style={{ color: 'var(--muted-fg)', fontSize: 'var(--text-xs)',
                           display: 'block', overflow: 'hidden',
                           textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              score {a.riskScore} · {a.status.toLowerCase()}
            </span>
          </span>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                         whiteSpace: 'nowrap' }}>
            {shortTime(a.occurredAt)}
          </span>
        </Link>
      ))}
    </div>
  );
}

const caption = {
  fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
  marginTop: 'var(--space-4)', marginBottom: 0,
};

const linkStyle = {
  fontSize: 'var(--text-sm)', color: 'var(--brand)', textDecoration: 'none',
};

/** Whole numbers, grouped. Rounded because a count-up passes through fractions. */
function count(n) {
  return Math.round(n).toLocaleString('en-ZA');
}

function score(n) {
  return n.toFixed(2);
}
