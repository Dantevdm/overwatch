import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  api, zar, shortTime, PAGE_SIZES, DEFAULT_PAGE_SIZE, TABLE_WINDOWS, DEFAULT_WINDOW,
} from '../api.js';
import {
  Card, Empty, FilterBar, Pagination, Select, TextFilter,
} from '../components/Primitives.jsx';

export default function Transactions() {
  const [data, setData] = useState(null);
  const [categories, setCategories] = useState([]);
  const [category, setCategory] = useState('');
  const [cardId, setCardId] = useState('');
  // Cardholder name, matched as a substring. The card filter is still here and
  // still exact, because the two answer different questions: a card is what a
  // rule fires on, a person is what an investigation is about.
  const [customer, setCustomer] = useState('');
  const [hours, setHours] = useState(DEFAULT_WINDOW);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);

  // Every filter resets to the first page. Changing a filter while on page 12
  // otherwise lands the reader on an empty page of a result set that does have
  // rows, which reads as "no matches" and is not.
  const filter = (set) => (value) => { set(value); setPage(0); };

  const active = Boolean(category || cardId || customer) || hours !== DEFAULT_WINDOW;
  const clear = () => {
    setCategory(''); setCardId(''); setCustomer('');
    setHours(DEFAULT_WINDOW); setPage(0);
  };

  useEffect(() => {
    api.transactionCategories().then(setCategories).catch(() => setCategories([]));
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      api.transactions({ category, cardId, customer, hours, page, size })
        .then(setData).catch(() => setData(null));
    }, 250);   // debounce so typing a card id does not fire a request per keystroke
    return () => clearTimeout(t);
  }, [category, cardId, customer, hours, page, size]);

  return (
    <Card
      title="Transactions"
      action={
        <FilterBar active={active} onClear={clear}>
          <TextFilter value={customer} onChange={filter(setCustomer)}
                      placeholder="Cardholder name…" width={170} />
          <TextFilter value={cardId} onChange={filter(setCardId)}
                      placeholder="Card id…" />
          <Select value={category} onChange={filter(setCategory)}
                  label="All categories" options={categories} />
          <Select value={hours} onChange={(v) => filter(setHours)(Number(v))}
                  options={TABLE_WINDOWS} />
        </FilterBar>
      }
    >
      {!data ? <Empty>Loading…</Empty>
        : data.content.length === 0 ? (
          <Empty>
            No transactions match these filters.
            {active && <> <button onClick={clear} style={linkButton}>Clear them</button>.</>}
          </Empty>
        )
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
          <Pagination page={page} size={size} onPage={setPage} onSize={setSize}
                      sizes={PAGE_SIZES} noun="transactions"
                      totalElements={data.totalElements} totalPages={data.totalPages} />
        </div>
      )}
    </Card>
  );
}

const th = { padding: 'var(--row-pad)', fontWeight: 600 };
const td = { padding: 'var(--row-pad)' };
const linkButton = {
  background: 'none', border: 'none', padding: 0, cursor: 'pointer',
  color: 'var(--brand)', fontWeight: 600, fontSize: 'inherit',
};
