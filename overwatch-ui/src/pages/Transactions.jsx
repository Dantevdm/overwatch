import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, zar, shortTime } from '../api.js';
import { Card, Empty } from '../components/Primitives.jsx';

export default function Transactions() {
  const [data, setData] = useState(null);
  const [category, setCategory] = useState('');
  const [cardId, setCardId] = useState('');
  // Cardholder name, matched as a substring. The card filter is still here and
  // still exact, because the two answer different questions: a card is what a
  // rule fires on, a person is what an investigation is about.
  const [customer, setCustomer] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const t = setTimeout(() => {
      api.transactions({ category, cardId, customer, hours: 24, page, size: 25 })
        .then(setData).catch(() => setData(null));
    }, 250);   // debounce so typing a card id does not fire a request per keystroke
    return () => clearTimeout(t);
  }, [category, cardId, customer, page]);

  return (
    <Card
      title="Transactions"
      action={
        <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
          <input value={customer} placeholder="Cardholder name…"
                 onChange={(e) => { setCustomer(e.target.value); setPage(0); }}
                 style={{ ...input, width: 170 }} />
          <input value={cardId} placeholder="Card id…"
                 onChange={(e) => { setCardId(e.target.value); setPage(0); }}
                 style={input} />
          <input value={category} placeholder="Category…"
                 onChange={(e) => { setCategory(e.target.value); setPage(0); }}
                 style={input} />
        </div>
      }
    >
      {!data ? <Empty>Loading…</Empty>
        : data.content.length === 0 ? <Empty>No transactions match.</Empty>
        : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--text-sm)' }}>
            <thead>
              <tr style={{ textAlign: 'left', color: 'var(--muted-fg)',
                           fontSize: 'var(--text-xs)', textTransform: 'uppercase' }}>
                <th style={th}>Merchant</th><th style={th}>Category</th>
                <th style={th}>Cardholder</th>
                <th style={th}>Card</th><th style={th}>Channel</th>
                <th style={{ ...th, textAlign: 'right' }}>Amount</th><th style={th}>When</th>
              </tr>
            </thead>
            <tbody>
              {data.content.map((t) => (
                <tr key={t.id} style={{ borderTop: '1px solid var(--border)' }}>
                  <td style={td}>{t.merchantName}</td>
                  <td style={{ ...td, color: 'var(--muted-fg)' }}>{t.merchantCategory}</td>
                  <td style={td}>
                    {/* Straight through to the 360 view. The whole point of
                        naming the cardholder is that you can then go and look at
                        them, and a name you cannot click is a name you have to
                        retype into another screen. */}
                    {t.customerId
                      ? <Link to={`/customers/${t.customerId}`}
                              style={{ color: 'var(--brand)', textDecoration: 'none' }}>
                          {t.customerName}
                        </Link>
                      : <span style={{ color: 'var(--muted-fg)' }}>—</span>}
                  </td>
                  <td style={{ ...td, fontFamily: 'var(--font-mono)', fontSize: 12 }}>{t.cardId}</td>
                  <td style={{ ...td, color: 'var(--muted-fg)' }}>{t.channel}</td>
                  <td style={{ ...td, textAlign: 'right', fontFamily: 'var(--font-mono)',
                               fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>
                    {zar(t.amount)}
                  </td>
                  <td style={{ ...td, color: 'var(--muted-fg)' }}>{shortTime(t.occurredAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                        paddingTop: 'var(--space-4)', fontSize: 'var(--text-xs)',
                        color: 'var(--muted-fg)' }}>
            <span>{data.totalElements.toLocaleString('en-ZA')} in the last 24 hours</span>
            <span style={{ display: 'flex', gap: 6 }}>
              <button style={btn} disabled={page === 0}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</button>
              <button style={btn} disabled={page >= data.totalPages - 1}
                      onClick={() => setPage((p) => p + 1)}>Next</button>
            </span>
          </div>
        </div>
      )}
    </Card>
  );
}

const th = { padding: 'var(--row-pad)', fontWeight: 600 };
const td = { padding: 'var(--row-pad)' };
const input = {
  height: 30, borderRadius: 'var(--radius-md)', border: '1px solid var(--input-border)',
  background: 'var(--bg)', color: 'var(--fg)', fontSize: 'var(--text-xs)', padding: '0 8px',
  width: 140,
};
const btn = {
  height: 28, padding: '0 10px', borderRadius: 'var(--radius-md)',
  border: '1px solid var(--input-border)', background: 'var(--bg)',
  color: 'var(--fg)', fontSize: 'var(--text-xs)', cursor: 'pointer',
};
