import { useEffect, useState } from 'react';
import { api, zar } from '../api.js';
import { Card, StatTile, Empty } from '../components/Primitives.jsx';
import { AlertsOverTime, SeverityBreakdown, RuleBars } from '../components/Charts.jsx';

export default function Dashboard() {
  const [stats, setStats] = useState(null);
  const [perf, setPerf] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [s, p] = await Promise.all([api.dashboard(), api.rulePerformance()]);
        if (!cancelled) { setStats(s); setPerf(p); setError(null); }
      } catch (e) {
        if (!cancelled) setError(e.message);
      }
    };
    load();
    // Polling rather than websockets: five seconds is imperceptible on a
    // dashboard and costs a fraction of the complexity. Recorded as a
    // deliberate trade in the project plan, not an oversight.
    const timer = setInterval(load, 5000);
    return () => { cancelled = true; clearInterval(timer); };
  }, []);

  if (error) {
    return <Empty>Could not reach the API — {error}</Empty>;
  }
  if (!stats) {
    return <Empty>Loading…</Empty>;
  }

  const ruleRows = perf
    .filter((r) => r.timesFired > 0 || r.shadowHits > 0)
    .map((r) => ({
      label: r.name,
      value: r.state === 'SHADOW' ? r.shadowHits : r.timesFired,
      shadow: r.state === 'SHADOW',
    }))
    .sort((a, b) => b.value - a.value);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      <div style={{ display: 'grid', gap: 'var(--space-4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))' }}>
        <StatTile label="Transactions" value={stats.totalTransactions.toLocaleString('en-ZA')}
                  sub={`${stats.transactionsLastHour.toLocaleString('en-ZA')} in the last hour`} />
        <StatTile label="Alerts raised" value={stats.totalAlerts.toLocaleString('en-ZA')}
                  sub={stats.totalTransactions > 0
                    ? `${((stats.totalAlerts / stats.totalTransactions) * 100).toFixed(2)}% of traffic`
                    : '—'} />
        <StatTile label="Open alerts" value={stats.openAlerts.toLocaleString('en-ZA')}
                  tone={stats.openAlerts > 0 ? 'warning' : undefined}
                  sub="awaiting an analyst" />
        <StatTile label="Flagged value" value={zar(stats.flaggedLast24hZar)}
                  tone="danger" sub="last 24 hours" />
        <StatTile label="Mean risk score" value={stats.averageRiskScore.toFixed(2)}
                  sub="across all alerts" />
      </div>

      <Card title="Alerts per hour">
        <AlertsOverTime data={stats.alertsOverTime} />
      </Card>

      <div style={{ display: 'grid', gap: 'var(--space-5)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))' }}>
        <Card title="Severity">
          <SeverityBreakdown counts={stats.alertsBySeverity} />
        </Card>

        <Card title="Which rules are firing">
          <RuleBars rows={ruleRows} />
          {ruleRows.some((r) => r.shadow) && (
            <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                        marginTop: 'var(--space-4)', marginBottom: 0 }}>
              Lighter bars are rules in shadow — evaluated against live traffic,
              recording what they would have caught, raising no alerts.
            </p>
          )}
        </Card>
      </div>
    </div>
  );
}
