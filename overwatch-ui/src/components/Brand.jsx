/**
 * The brand furniture: a logo mark, a wordmark, and the lock-up of the two.
 *
 * The mark is drawn here rather than loaded as an image file, for two reasons.
 * A path inherits `currentColor` and scales to any size without a second asset,
 * so it works on the navy sidebar, on the white sign-in card and at 16px in a
 * favicon slot. And it keeps the approximation honest: this is a shape matched
 * by eye from public material — two arcs, navy carrying most of the ring and
 * red closing it — not the official artwork. Anything actually shown to Capitec
 * should replace it from the brand guide, and this comment is where whoever
 * does that will look.
 *
 * `CAPITEC` is set in the app's own sans stack, letter-spaced to sit like a
 * wordmark. Their real wordmark is a bespoke face; substituting a system font
 * is the visible tell that this is a demo skin, which is the right way round.
 */

/**
 * The mark alone. Two-tone by default; pass `mono` on a coloured ground where a
 * red arc on navy has nowhere near enough contrast to read as a shape.
 */
export function Mark({ size = 28, mono = false }) {
  const navy = mono ? 'currentColor' : 'var(--brand)';
  const red = mono ? 'currentColor' : 'var(--accent)';
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none"
         aria-hidden="true" focusable="false" style={{ flexShrink: 0, display: 'block' }}>
      {/* The ring opens to the upper right — the gap is what makes it read as a
          C rather than as an O, and the red arc closes toward it. */}
      <path d="M38 12.5A18 18 0 1 0 38 35.5"
            stroke={navy} strokeWidth="7" strokeLinecap="round" />
      <path d="M31 6.5A18 18 0 0 1 42.5 17"
            stroke={red} strokeWidth="7" strokeLinecap="round"
            // Slight extra weight is visually necessary: a short arc at the same
            // stroke width reads thinner than a long one at the same width.
            opacity={mono ? 0.55 : 1} />
    </svg>
  );
}

/** The wordmark alone. `tone` picks navy on white, or inherit on a dark ground. */
export function Wordmark({ size = 'var(--text-lg)', tone = 'var(--brand)' }) {
  return (
    <span style={{
      fontSize: size, fontWeight: 700, letterSpacing: '.02em',
      color: tone, whiteSpace: 'nowrap',
    }}>
      CAPITEC
    </span>
  );
}

/**
 * Mark plus wordmark plus the product name.
 *
 * The bank's name is the brand; "Overwatch" is the product, so it sits after a
 * hairline divider at lighter weight rather than competing with it. That is the
 * usual convention for an internal tool and it also keeps the claim modest: the
 * bank is not the author of this thing.
 */
export function Lockup({ mono = false, size = 26, showProduct = true }) {
  const tone = mono ? 'currentColor' : 'var(--brand)';
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
      <Mark size={size} mono={mono} />
      <Wordmark tone={tone} />
      {showProduct && (
        <>
          <span aria-hidden="true" style={{
            width: 1, height: Math.round(size * 0.6),
            background: 'currentColor', opacity: 0.28,
          }} />
          <span style={{
            fontSize: 'var(--text-base)', fontWeight: 500, color: tone,
            opacity: mono ? 0.82 : 1, whiteSpace: 'nowrap',
          }}>
            Overwatch
          </span>
        </>
      )}
    </span>
  );
}
