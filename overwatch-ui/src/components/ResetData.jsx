import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';

/**
 * "Clear data" — empties the store so a demonstration can start from nothing.
 *
 * <p>Behind a confirmation, because it is the only control in this UI that
 * destroys anything and it sits in the top bar on every screen, one slip away
 * from a click meant for something else. The dialog names what goes and what
 * stays rather than asking "are you sure?", which is a question nobody reads.
 *
 * The button is quiet until it is opened: a permanently red button in the top
 * bar of every page trains people to ignore red. The danger colour belongs on
 * the confirm action, where the decision actually is.
 */
export default function ResetData({ onReset }) {
    const [open, setOpen] = useState(false);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState(null);
    const [error, setError] = useState(null);
    const panelRef = useRef(null);
    const confirmRef = useRef(null);

    // Escape closes, and focus moves to the confirm button on open so the dialog
    // is operable from the keyboard rather than only by mouse.
    useEffect(() => {
        if (!open) return undefined;
        confirmRef.current?.focus();
        const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
        const onClickAway = (e) => {
            if (panelRef.current && !panelRef.current.contains(e.target)) setOpen(false);
        };
        document.addEventListener('keydown', onKey);
        document.addEventListener('mousedown', onClickAway);
        return () => {
            document.removeEventListener('keydown', onKey);
            document.removeEventListener('mousedown', onClickAway);
        };
    }, [open]);

    // Clear the outcome message a few seconds after it appears. It is a
    // confirmation, not a status the top bar should carry indefinitely.
    useEffect(() => {
        if (!result && !error) return undefined;
        const t = setTimeout(() => { setResult(null); setError(null); }, 6000);
        return () => clearTimeout(t);
    }, [result, error]);

    const run = async () => {
        setBusy(true);
        setError(null);
        try {
            const r = await api.resetData();
            setResult(r);
            setOpen(false);
            onReset?.();
        } catch (e) {
            setError(e.message);
        } finally {
            setBusy(false);
        }
    };

    return (
        <div style={{ position: 'relative' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-3)' }}>
                {(result || error) && (
                    <span role="status" style={{
                        fontSize: 'var(--text-xs)',
                        color: error ? 'var(--danger-fg)' : 'var(--success-fg)',
                        whiteSpace: 'nowrap',
                    }}>
                        {error
                            ? `Reset failed — ${error}`
                            : `Cleared ${(result.removed?.transactions ?? 0).toLocaleString('en-ZA')} `
                              + `transactions, ${(result.removed?.alerts ?? 0).toLocaleString('en-ZA')} alerts`}
                    </span>
                )}
                <button type="button" onClick={() => setOpen((v) => !v)}
                        aria-haspopup="dialog" aria-expanded={open}
                        style={{
                            appearance: 'none', cursor: 'pointer',
                            padding: '4px 11px', borderRadius: 'var(--radius-md)',
                            border: '1px solid var(--border)', background: 'var(--bg)',
                            color: 'var(--muted-fg)', fontSize: 'var(--text-xs)',
                            fontWeight: 500, whiteSpace: 'nowrap',
                        }}>
                    Clear data
                </button>
            </div>

            {open && (
                <div ref={panelRef} role="dialog" aria-modal="false"
                     aria-label="Clear observed data"
                     style={{
                         position: 'absolute', top: 'calc(100% + 8px)', right: 0,
                         width: 330, zIndex: 30,
                         background: 'var(--bg)', border: '1px solid var(--border)',
                         borderRadius: 'var(--radius-lg, var(--radius-md))',
                         boxShadow: 'var(--shadow-lg, var(--shadow-sm))',
                         padding: 'var(--space-5)', textAlign: 'left',
                     }}>
                    <div style={{ fontWeight: 600, marginBottom: 'var(--space-3)' }}>
                        Start from a clean slate?
                    </div>

                    <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                margin: '0 0 var(--space-3)', lineHeight: 1.5 }}>
                        Deletes every stored <strong>transaction</strong>,{' '}
                        <strong>alert</strong> and <strong>rule hit</strong>, and zeroes
                        the simulator&rsquo;s counters. This cannot be undone.
                    </p>
                    <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                margin: '0 0 var(--space-3)', lineHeight: 1.5 }}>
                        Your <strong>rules stay exactly as they are</strong> — states,
                        weights and thresholds included — so a threshold you have just
                        tuned survives clearing the traffic you tuned it against.
                    </p>
                    <p style={{ fontSize: 'var(--text-xs)', color: 'var(--muted-fg)',
                                margin: '0 0 var(--space-4)', lineHeight: 1.5 }}>
                        Grafana will still show the run just cleared. Prometheus counters
                        only ever increase, so resetting them would put a false spike in
                        every panel.
                    </p>

                    <div style={{ display: 'flex', gap: 'var(--space-2)',
                                  justifyContent: 'flex-end' }}>
                        <button type="button" onClick={() => setOpen(false)} disabled={busy}
                                style={{
                                    appearance: 'none', cursor: busy ? 'default' : 'pointer',
                                    padding: '5px 12px', borderRadius: 'var(--radius-md)',
                                    border: '1px solid var(--border)', background: 'var(--bg)',
                                    color: 'var(--fg)', fontSize: 'var(--text-sm)',
                                }}>
                            Cancel
                        </button>
                        <button ref={confirmRef} type="button" onClick={run} disabled={busy}
                                style={{
                                    appearance: 'none', cursor: busy ? 'default' : 'pointer',
                                    padding: '5px 12px', borderRadius: 'var(--radius-md)',
                                    border: '1px solid var(--danger)',
                                    background: busy ? 'var(--muted-fg)' : 'var(--danger)',
                                    color: '#fff', fontSize: 'var(--text-sm)', fontWeight: 600,
                                }}>
                            {busy ? 'Clearing…' : 'Clear data'}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
