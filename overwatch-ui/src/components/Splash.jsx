import { Lockup } from './Brand.jsx';

/**
 * The loading screen, shown on a cold load while the shell waits for its first
 * response from the API.
 *
 * Why it exists at all: this app's first paint is an empty grid of cards that
 * fill in a moment later, and on a cold Docker start the API may not have
 * finished migrating yet, so the moment can be several seconds. Empty cards
 * that then populate looks like a bug; a branded splash that resolves looks
 * like a product.
 *
 * Two rules keep it from being the usual sin of a splash screen. It has a
 * *maximum*, not just a minimum: {@link Shell} stops waiting after a couple of
 * seconds and shows the app whether the first fetch has landed or not, so a
 * dead API can never leave you staring at a logo. And it has a minimum of a few
 * hundred milliseconds, so on a warm reload it fades rather than strobing —
 * a splash visible for 40ms is worse than none.
 *
 * The progress bar is honest about being indeterminate: it eases toward 94% and
 * stops. It is not reporting anything, and pretending to would be worse.
 */
export default function Splash({ leaving = false }) {
  return (
    <div role="status" aria-live="polite"
         style={{
           position: 'fixed', inset: 0, zIndex: 100,
           background: 'var(--brand-gradient)', color: '#ffffff',
           display: 'flex', flexDirection: 'column',
           alignItems: 'center', justifyContent: 'center', gap: 'var(--space-6)',
           overflow: 'hidden',
           // Fades out rather than unmounting hard, so the app appears behind
           // it instead of replacing it in one frame.
           opacity: leaving ? 0 : 1,
           transition: 'opacity 260ms ease-out',
           pointerEvents: leaving ? 'none' : 'auto',
         }}>
      {/* The sweep. Purely atmospheric, sits under everything, and is one
          transformed element so it costs a composite and not a repaint. */}
      <div aria-hidden="true" className="ow-sweep" style={{
        position: 'absolute', top: 0, bottom: 0, width: '45%',
        background: 'linear-gradient(90deg, transparent, rgba(255,255,255,.07), transparent)',
      }} />

      <div className="ow-rise" style={{ display: 'flex', flexDirection: 'column',
                                        alignItems: 'center', gap: 'var(--space-5)' }}>
        <Lockup mono size={46} />

        <div style={{ display: 'flex', alignItems: 'center', gap: 10,
                      fontSize: 'var(--text-sm)', opacity: 0.8 }}>
          <svg className="ow-spin" width="15" height="15" viewBox="0 0 24 24"
               fill="none" aria-hidden="true">
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.5"
                    opacity="0.25" />
            {/* A quarter of the circumference (2πr ≈ 56.5), so the visible arc
                is the gap in the mark's ring, spinning. */}
            <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.5"
                    strokeLinecap="round" strokeDasharray="14 43" />
          </svg>
          Connecting to the fraud pipeline…
        </div>
      </div>

      <div aria-hidden="true" style={{
        width: 'min(260px, 60vw)', height: 3, borderRadius: 'var(--radius-full)',
        background: 'rgba(255,255,255,.18)', overflow: 'hidden',
      }}>
        <div className="ow-fill" style={{
          height: '100%', width: '100%', background: 'var(--accent)',
          borderRadius: 'inherit',
        }} />
      </div>
    </div>
  );
}
