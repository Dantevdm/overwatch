import { useMemo, useState } from 'react';
import { CATALOGUE, GROUPS, endpointKey } from '../apiCatalogue.js';
import { api } from '../api.js';
import { Button, Card, Code, CopyButton, MethodBadge } from '../components/Primitives.jsx';

/**
 * The API, callable from the page that documents it.
 *
 * <p>There is already an OpenAPI document, and Swagger UI already has a
 * try-it-out button — both are linked from here rather than reimplemented. What
 * neither of them does is answer the question a reviewer actually arrives with,
 * which is not "what fields does this return" but "what do I call, in what
 * order, and what does a real answer look like". A generated schema cannot say
 * that; it does not know which call matters.
 *
 * So this screen is opinionated where Swagger is complete. Endpoints are grouped
 * by the thing they act on, each carries a sentence on why it exists, the
 * defaults are values that return something on a running stack, and every
 * id-shaped parameter has a button that goes and fetches a real one — because a
 * placeholder id is the single most common reason a copied request comes back
 * 404 and reads as a broken API.
 */

/** Where the browser reaches the API from outside this page — for the curl line. */
const PUBLIC_BASE = import.meta.env.VITE_API_PUBLIC_URL || 'http://localhost:8080';

export default function ApiExplorer() {
  const [selected, setSelected] = useState(endpointKey(CATALOGUE[0]));

  // Parameter values live here, keyed by endpoint, so switching endpoints and
  // coming back does not throw away what you typed — including a resolved id
  // you fetched two clicks ago.
  const [values, setValues] = useState(() => initialValues());
  const [bodies, setBodies] = useState(() => initialBodies());

  const endpoint = CATALOGUE.find((e) => endpointKey(e) === selected) ?? CATALOGUE[0];
  const key = endpointKey(endpoint);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>
      <Intro />

      <div style={{ display: 'grid', gap: 'var(--space-5)',
                    gridTemplateColumns: 'minmax(240px, 300px) minmax(0, 1fr)',
                    alignItems: 'start' }}>
        <Catalogue selected={key} onSelect={setSelected} />
        <Endpoint
          endpoint={endpoint}
          values={values[key]}
          body={bodies[key]}
          onValue={(name, value) => setValues((all) => ({
            ...all, [key]: { ...all[key], [name]: value },
          }))}
          onBody={(text) => setBodies((all) => ({ ...all, [key]: text }))}
        />
      </div>
    </div>
  );
}

/** Default parameter values, taken from the catalogue's own suggestions. */
function initialValues() {
  const out = {};
  for (const endpoint of CATALOGUE) {
    out[endpointKey(endpoint)] = Object.fromEntries(
      endpoint.params.map((p) => [p.name, p.value ?? '']));
  }
  return out;
}

function initialBodies() {
  const out = {};
  for (const endpoint of CATALOGUE) out[endpointKey(endpoint)] = endpoint.body ?? '';
  return out;
}

// ---- the header card -------------------------------------------------------

function Intro() {
  return (
    <Card title="Calling this API">
      <p style={{ marginTop: 0, color: 'var(--muted-fg)', fontSize: 'var(--text-sm)',
                  maxWidth: '72ch' }}>
        Every endpoint below runs against the stack you are looking at. Press{' '}
        <strong>Send</strong> and the response is the real one, including its status
        code and how long it took. Requests that write to something are marked, and
        ask twice.
      </p>

      <div style={{ display: 'flex', gap: 'var(--space-3)', flexWrap: 'wrap',
                    marginTop: 'var(--space-4)' }}>
        {/* Plain anchors, not fetch-and-blob. The endpoint already sets
            Content-Disposition: attachment, so the browser saves the file with
            its proper name and nothing has to be held in memory. */}
        <a href="/api/postman/collection" download
           style={downloadLink}>Download Postman collection</a>
        <a href="/api/postman/environment" download
           style={downloadLink}>Download Postman environment</a>
        <a href={`${PUBLIC_BASE}/swagger-ui.html`} target="_blank" rel="noreferrer"
           style={{ ...downloadLink, background: 'var(--bg)', color: 'var(--fg)' }}>
          OpenAPI / Swagger ↗
        </a>
      </div>

      <p style={{ marginBottom: 0, marginTop: 'var(--space-4)',
                  fontSize: 'var(--text-xs)', color: 'var(--muted-fg)', maxWidth: '72ch' }}>
        Import both files into Postman and select the environment, or every request
        resolves to a literal <code>{'{{baseUrl}}'}</code>. The collection is the
        one under version control in <code>/tools/postman</code> — the same bytes,
        served off this service's classpath, so the download cannot drift from the
        repository. Swagger UI is generated from the controllers and is the
        authority on schemas; this page is the authority on what to call first.
      </p>
    </Card>
  );
}

const downloadLink = {
  display: 'inline-block', padding: '7px 14px', borderRadius: 'var(--radius-md)',
  background: 'var(--brand)', color: 'var(--brand-fg)', textDecoration: 'none',
  fontSize: 'var(--text-sm)', fontWeight: 600, border: '1px solid var(--brand)',
};

// ---- the endpoint list -----------------------------------------------------

function Catalogue({ selected, onSelect }) {
  return (
    <Card style={{ position: 'sticky', top: 'calc(var(--topbar-h) + var(--space-6))' }}>
      {GROUPS.map((group) => (
        <div key={group} style={{ marginBottom: 'var(--space-4)' }}>
          <div style={{ fontSize: 'var(--text-xs)', fontWeight: 700, letterSpacing: '.06em',
                        textTransform: 'uppercase', color: 'var(--muted-fg)',
                        marginBottom: 'var(--space-2)' }}>
            {group}
          </div>
          {CATALOGUE.filter((e) => e.group === group).map((endpoint) => {
            const key = endpointKey(endpoint);
            const active = key === selected;
            return (
              <button key={key} type="button" onClick={() => onSelect(key)}
                      style={{
                        display: 'flex', alignItems: 'center', gap: 8, width: '100%',
                        textAlign: 'left', font: 'inherit', cursor: 'pointer',
                        padding: '5px 8px', marginBottom: 2,
                        borderRadius: 'var(--radius-md)',
                        border: `1px solid ${active ? 'var(--border)' : 'transparent'}`,
                        background: active ? 'var(--surface)' : 'transparent',
                        color: 'var(--fg)',
                      }}>
                <MethodBadge method={endpoint.method} />
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)',
                               overflow: 'hidden', textOverflow: 'ellipsis',
                               whiteSpace: 'nowrap' }}>
                  {endpoint.path.replace('/api', '')}
                </span>
              </button>
            );
          })}
        </div>
      ))}
    </Card>
  );
}

// ---- the endpoint detail ---------------------------------------------------

function Endpoint({ endpoint, values, body, onValue, onBody }) {
  const [response, setResponse] = useState(null);
  const [sending, setSending] = useState(false);
  const [armed, setArmed] = useState(false);
  const [filling, setFilling] = useState(null);

  const url = useMemo(() => buildUrl(endpoint, values), [endpoint, values]);
  const unresolved = endpoint.params
    .filter((p) => p.in === 'path' && !values[p.name])
    .map((p) => p.name);

  const send = async () => {
    if (endpoint.danger && !armed) { setArmed(true); return; }
    setArmed(false);
    setSending(true);
    const started = performance.now();
    try {
      const res = await fetch(url, {
        method: endpoint.method,
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body || undefined,
      });
      const text = await res.text();
      setResponse({
        status: res.status, statusText: res.statusText, ok: res.ok,
        ms: Math.round(performance.now() - started),
        bytes: new TextEncoder().encode(text).length,
        body: pretty(text),
      });
    } catch (e) {
      // A network-level failure, not an HTTP status — the API is not answering
      // at all. Reported as such rather than as a status code it never sent.
      setResponse({ status: 0, statusText: 'No response', ok: false,
                    ms: Math.round(performance.now() - started), bytes: 0,
                    body: String(e.message ?? e) });
    } finally {
      setSending(false);
    }
  };

  /** Fetch a real id from the running system for a path parameter. */
  const fill = async (param) => {
    setFilling(param.name);
    try {
      const id = await resolveId(param.fill);
      if (id != null) onValue(param.name, String(id));
    } catch { /* the hint below already says what to do by hand */ }
    finally { setFilling(null); }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-5)' }}>
      <Card>
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)',
                      flexWrap: 'wrap' }}>
          <MethodBadge method={endpoint.method} />
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-base)',
                         fontWeight: 600 }}>
            {endpoint.path}
          </span>
          {endpoint.danger && (
            <span style={{ fontSize: 'var(--text-xs)', fontWeight: 600,
                           color: 'var(--warning-fg)', background: 'var(--warning-bg)',
                           border: '1px solid var(--warning-border)',
                           borderRadius: 'var(--radius-full)', padding: '2px 9px' }}>
              writes
            </span>
          )}
        </div>

        <p style={{ margin: 'var(--space-3) 0 0', fontSize: 'var(--text-sm)' }}>
          {endpoint.summary}
        </p>
        {endpoint.why && (
          <p style={{ margin: 'var(--space-2) 0 0', fontSize: 'var(--text-sm)',
                      color: 'var(--muted-fg)', maxWidth: '72ch' }}>
            {endpoint.why}
          </p>
        )}

        {endpoint.params.length > 0 && (
          <div style={{ marginTop: 'var(--space-5)', display: 'grid',
                        gap: 'var(--space-3)',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}>
            {endpoint.params.map((param) => (
              <Param key={param.name} param={param} value={values[param.name] ?? ''}
                     filling={filling === param.name}
                     onChange={(v) => onValue(param.name, v)}
                     onFill={param.fill ? () => fill(param) : null} />
            ))}
          </div>
        )}

        {endpoint.body !== undefined && endpoint.body !== null && (
          <div style={{ marginTop: 'var(--space-5)' }}>
            <FieldLabel>Request body</FieldLabel>
            <textarea
              value={body} onChange={(e) => onBody(e.target.value)}
              spellCheck={false} rows={Math.min(14, (body.match(/\n/g)?.length ?? 0) + 2)}
              style={{
                width: '100%', resize: 'vertical',
                padding: 'var(--space-3)', borderRadius: 'var(--radius-md)',
                border: '1px solid var(--input-border)',
                background: 'var(--bg)', color: 'var(--fg)',
                fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)', lineHeight: 1.55,
              }} />
          </div>
        )}

        <div style={{ marginTop: 'var(--space-5)', display: 'flex', gap: 'var(--space-3)',
                      alignItems: 'center', flexWrap: 'wrap' }}>
          <Button tone={armed ? 'danger' : 'primary'}
                  disabled={sending || unresolved.length > 0}
                  onClick={send}>
            {sending ? 'Sending…'
              : armed ? `Yes — ${endpoint.method} it`
              : endpoint.danger ? 'Send…' : 'Send'}
          </Button>
          {armed && (
            <>
              <Button onClick={() => setArmed(false)}>Cancel</Button>
              <span style={{ fontSize: 'var(--text-xs)', color: 'var(--warning-fg)' }}>
                This one writes. Nothing has been sent yet.
              </span>
            </>
          )}
          {unresolved.length > 0 && (
            <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
              Needs {unresolved.join(', ')} — use “Fetch one” or paste an id.
            </span>
          )}
        </div>

        <div style={{ marginTop: 'var(--space-5)' }}>
          <div style={{ display: 'flex', alignItems: 'center',
                        justifyContent: 'space-between', marginBottom: 'var(--space-2)' }}>
            <FieldLabel style={{ margin: 0 }}>As curl</FieldLabel>
            <CopyButton text={curlFor(endpoint, url, body)} />
          </div>
          {/* The public base URL, not the same-origin /api this page uses. A curl
              line is for a terminal, where a relative path means nothing. */}
          <Code maxHeight={140}>{curlFor(endpoint, url, body)}</Code>
        </div>
      </Card>

      {response && <Response response={response} />}
    </div>
  );
}

function Param({ param, value, onChange, onFill, filling }) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <span style={{ fontSize: 'var(--text-xs)', fontWeight: 600 }}>
        {param.name}
        <span style={{ color: 'var(--muted-fg)', fontWeight: 400 }}>
          {' '}· {param.in}
        </span>
      </span>

      <div style={{ display: 'flex', gap: 6 }}>
        {param.options ? (
          <select value={value} onChange={(e) => onChange(e.target.value)} style={control}>
            {param.options.map((option) => (
              <option key={option} value={option}>{option || '— any —'}</option>
            ))}
          </select>
        ) : (
          <input value={value} onChange={(e) => onChange(e.target.value)}
                 placeholder={param.in === 'path' ? 'required' : 'optional'}
                 spellCheck={false} style={control} />
        )}
        {onFill && (
          <Button onClick={onFill} disabled={filling}
                  title="Fetch a real id from the running system"
                  style={{ fontSize: 'var(--text-xs)', padding: '0 10px' }}>
            {filling ? '…' : 'Fetch one'}
          </Button>
        )}
      </div>

      <span style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
        {param.hint}
      </span>
    </label>
  );
}

function Response({ response }) {
  const tone = response.ok ? 'var(--success-fg)'
    : response.status >= 400 && response.status < 500 ? 'var(--warning-fg)'
    : 'var(--danger-fg)';

  return (
    <Card
      title="Response"
      action={
        <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-4)',
                      fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
          <span style={{ color: tone, fontWeight: 700, fontFamily: 'var(--font-mono)' }}>
            {response.status || '—'} {response.statusText}
          </span>
          <span>{response.ms} ms</span>
          <span>{response.bytes.toLocaleString('en-ZA')} bytes</span>
          <CopyButton text={response.body} />
        </div>
      }
    >
      <Code maxHeight={520}>{response.body}</Code>
      {/* 403 on a write endpoint is a configuration answer, not a failure — and
          it is the one status somebody will hit without knowing why. */}
      {response.status === 403 && (
        <p style={{ marginBottom: 0, fontSize: 'var(--text-xs)', color: 'var(--muted-fg)' }}>
          This instance has that operation switched off. Both write flags —
          <code> overwatch.api.allow-reset</code> and
          <code> overwatch.api.allow-stream-writes</code> — default to on for the
          demo and are the one line a real deployment turns off.
        </p>
      )}
    </Card>
  );
}

function FieldLabel({ children, style }) {
  return (
    <div style={{ fontSize: 'var(--text-xs)', fontWeight: 600,
                  marginBottom: 'var(--space-2)', ...style }}>
      {children}
    </div>
  );
}

const control = {
  flex: 1, minWidth: 0, height: 30,
  borderRadius: 'var(--radius-md)', border: '1px solid var(--input-border)',
  background: 'var(--bg)', color: 'var(--fg)',
  fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)', padding: '0 8px',
};

// ---- request building ------------------------------------------------------

function buildUrl(endpoint, values) {
  let path = endpoint.path;
  for (const param of endpoint.params.filter((p) => p.in === 'path')) {
    path = path.replace(`{${param.name}}`,
      encodeURIComponent(values[param.name] ?? `{${param.name}}`));
  }
  const query = endpoint.params
    .filter((p) => p.in === 'query')
    .filter((p) => (values[p.name] ?? '') !== '')
    .map((p) => `${encodeURIComponent(p.name)}=${encodeURIComponent(values[p.name])}`)
    .join('&');
  return query ? `${path}?${query}` : path;
}

/**
 * The same request as a curl line, against the API's public address.
 *
 * The page itself fetches a relative path, which the Vite proxy resolves — but a
 * relative path pasted into a terminal is not a request, so this reassembles it
 * against the host and port the browser can actually reach.
 */
function curlFor(endpoint, url, body) {
  const parts = [`curl -i -X ${endpoint.method} '${PUBLIC_BASE}${url}'`];
  if (body) {
    parts.push("  -H 'Content-Type: application/json'");
    parts.push(`  -d '${body.replace(/\n\s*/g, '')}'`);
  }
  return parts.join(' \\\n');
}

/**
 * A real id for a path parameter, fetched from the running system.
 *
 * Alerts and transactions are paged, so the newest row is `content[0]`; rules
 * come back as a plain list because the rule set is small and bounded and paging
 * a dozen rows would be ceremony.
 */
async function resolveId(kind) {
  if (kind === 'alert') {
    const page = await api.alerts({ size: 1 });
    return page.content?.[0]?.id;
  }
  if (kind === 'transaction') {
    const page = await api.transactions({ size: 1 });
    return page.content?.[0]?.id;
  }
  if (kind === 'rule') {
    const rules = await api.rules();
    return rules?.[0]?.id;
  }
  return null;
}

/** Pretty-print JSON, and leave anything else exactly as it arrived. */
function pretty(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text || '(empty body)';
  }
}
