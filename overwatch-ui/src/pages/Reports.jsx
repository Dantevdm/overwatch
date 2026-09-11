import { useEffect, useState } from 'react';
import { Card, SegmentedControl, Button } from '../components/Primitives.jsx';
import { api, zar, RANGES, DEFAULT_RANGE, rangeLabel } from '../api.js';

/**
 * The dashboard as something you can send to someone.
 *
 * Everything on this screen exists elsewhere in the console; what does not exist
 * elsewhere is a file. A fraud lead's Monday morning ends in a document attached
 * to an email, and until there is an export button that ends in a screenshot of a
 * browser tab — which loses the numbers, the window they cover, and the date it
 * was taken.
 *
 * The files are built by the API rather than here. Same figures as the dashboard
 * because it is the same service, reachable from `curl` and Postman as well as
 * this button, and the browser never has to hold a workbook in memory.
 */
export default function Reports() {
  const [range, setRange] = useState(DEFAULT_RANGE);
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);

  // The same call the report is built from, so the panel below is a preview of
  // the document rather than a second opinion about it.
  useEffect(() => {
    let live = true;
    api.dashboard({ rangeMinutes: range })
      .then((d) => { if (live) setPreview(d); })
      .catch(() => { if (live) setPreview(null); });
    return () => { live = false; };
  }, [range]);

  async function download(format) {
    setBusy(format);
    setError(null);
    try {
      const res = await fetch(`/api/reports/fraud-summary.${format}?rangeMinutes=${range}`);
      if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);

      // Saved through a blob and a synthetic link rather than by pointing the
      // window at the URL. A plain navigation to a file the server marks as an
      // attachment works, but any failure lands as a blank tab or a downloaded
      // error page; this way a non-200 stays on this screen and says so.
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filenameFrom(res) || `overwatch-fraud-report.${format}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(`Could not generate the ${format.toUpperCase()}: ${e.message}`);
    } finally {
      setBusy(null);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>

      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
                    gap: 'var(--space-4)', flexWrap: 'wrap' }}>
        <p style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
          Both reports cover the last {rangeLabel(range)}. The headline totals carry
          their own fixed periods, the same way the dashboard's tiles do.
        </p>
        <SegmentedControl
          label="Window the report covers"
          value={range}
          onChange={setRange}
          options={RANGES.map((r) => ({
            value: r.minutes, label: r.label, title: `Last ${r.label}`,
          }))}
        />
      </div>

      <div style={{ display: 'grid', gap: 'var(--space-4)',
                    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))' }}>
        <Format
          title="Workbook"
          extension="xlsx"
          busy={busy === 'xlsx'}
          disabled={busy !== null}
          onDownload={() => download('xlsx')}
          blurb="Five sheets — summary, activity, severity over time, merchant
                 categories and rule performance."
          points={[
            'Charts are real Excel charts bound to the cells, not pictures. Re-sort a table and the chart follows.',
            'Figures are numbers with a cell format, so a column can be summed or pasted into a model.',
            'The rule sheet opens with a filter and a frozen header — it is the one people work in.',
          ]}
        />
        <Format
          title="Document"
          extension="pdf"
          busy={busy === 'pdf'}
          disabled={busy !== null}
          onDownload={() => download('pdf')}
          blurb="Three pages, in the order the questions get asked: what happened,
                 how it moved, and which rules did it."
          points={[
            'Charts are drawn as vectors, so the axis labels survive being printed or zoomed.',
            'Alerts and volume get a chart each — on one axis the alert line would lie flat on zero.',
            'The generation date is on every page, because a figure with no date on it will be quoted in six months.',
          ]}
        />
      </div>

      {error && (
        <p role="alert" style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--danger-fg)' }}>
          {error}
        </p>
      )}

      <Card title="What the report will say"
            action={<span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
              Read at the moment you press a button, not now
            </span>}>
        {preview ? (
          <div style={{ display: 'grid', gap: 'var(--space-4)',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))' }}>
            <Figure label="Transactions" value={count(preview.totalTransactions)} />
            <Figure label="Alerts raised" value={count(preview.totalAlerts)} />
            <Figure label="Still open" value={count(preview.openAlerts)} />
            <Figure label="Alert rate" value={rate(preview)} />
            <Figure label="Mean risk score" value={preview.averageRiskScore.toFixed(2)} />
            <Figure label="Flagged (24h)" value={zar(preview.flaggedLast24hZar)} />
          </div>
        ) : (
          <p style={{ margin: 0, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
            Loading the figures…
          </p>
        )}
      </Card>

      <p style={{ margin: 0, fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
        Both formats are plain GET requests, so a scheduler or a colleague with
        curl can have the same file:{' '}
        <code>/api/reports/fraud-summary.pdf?rangeMinutes={range}</code>
      </p>
    </div>
  );
}

function Format({ title, extension, blurb, points, onDownload, busy, disabled }) {
  return (
    <Card title={title}
          action={
            <Button tone="primary" onClick={onDownload} disabled={disabled}
                    title={`Download the report as a .${extension} file`}>
              {busy ? 'Generating…' : `Download .${extension}`}
            </Button>
          }>
      <p style={{ margin: '0 0 var(--space-3)', fontSize: 'var(--text-sm)' }}>{blurb}</p>
      <ul style={{ margin: 0, paddingLeft: '1.1em', display: 'flex', flexDirection: 'column',
                   gap: 6, fontSize: 'var(--text-sm)', color: 'var(--muted-fg)' }}>
        {points.map((point) => <li key={point}>{point}</li>)}
      </ul>
    </Card>
  );
}

function Figure({ label, value }) {
  return (
    <div>
      <div style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                    textTransform: 'uppercase', letterSpacing: '0.04em' }}>
        {label}
      </div>
      <div style={{ fontSize: 'var(--text-xl)', fontWeight: 600 }}>{value}</div>
    </div>
  );
}

/**
 * The name the server chose, taken from Content-Disposition.
 *
 * It carries the generation timestamp, so two reports downloaded an hour apart do
 * not become "report.pdf" and "report (1).pdf" in a downloads folder.
 */
function filenameFrom(response) {
  const header = response.headers.get('Content-Disposition') || '';
  return /filename="?([^"]+)"?/.exec(header)?.[1] ?? null;
}

const count = (n) => n.toLocaleString('en-ZA').replace(/,/g, ' ');

const rate = (stats) => (stats.totalTransactions === 0
  ? '0.00%'
  : `${(100 * stats.totalAlerts / stats.totalTransactions).toFixed(2)}%`);
