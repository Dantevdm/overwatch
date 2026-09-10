import { useEffect, useRef, useState } from 'react';

/**
 * The motion helpers. Two rules run through all of them.
 *
 * Nothing here carries information. Every animation is a nicer way to show a
 * value that is already correct in the DOM on the first frame, so switching it
 * all off costs nothing but polish — which is what makes honouring
 * prefers-reduced-motion a one-line branch rather than a redesign.
 *
 * And nothing animates on a poll. This dashboard re-fetches every five seconds;
 * if cards re-entered or figures rolled on every response the screen would
 * never be still, and a number that is always mid-count is a number you cannot
 * read. Entrances fire on mount only, and the count-up fires only when a value
 * actually changes.
 */

/**
 * The OS "reduce motion" setting, live.
 *
 * Read once synchronously so the first render is already correct — deciding on
 * an effect would play one frame of the animation to exactly the people who
 * asked not to see it — then subscribed to, because macOS and Windows both let
 * you change it without reloading the page.
 */
export function usePrefersReducedMotion() {
  const [reduced, setReduced] = useState(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return false;
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  });

  useEffect(() => {
    if (!window.matchMedia) return undefined;
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = (e) => setReduced(e.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  return reduced;
}

/**
 * Roll a number from its previous value to a new one.
 *
 * Returns the number to display, so the caller keeps its own formatting — this
 * hook must not know that one tile is currency and another is a count.
 *
 * Three guards, each earned:
 *
 *  - On first mount it returns the target immediately. A dashboard whose figures
 *    all count up from zero on load is a stock-photo dashboard; the roll is
 *    meant to draw the eye to a *change*, and if everything animates on arrival
 *    then nothing signals anything.
 *  - A change smaller than `minDelta` snaps. With a five-second poll the
 *    transaction count moves by a few hundred and the mean risk score by 0.001,
 *    and animating the latter is 400ms of jitter in the last decimal place.
 *  - Eased with the same curve as the CSS, and driven by timestamps rather than
 *    a per-frame increment, so a backgrounded tab that stops calling rAF
 *    resumes at the right place instead of finishing 4000 frames late.
 */
export function useCountUp(target, { duration = 650, minDelta = 0 } = {}) {
  const reduced = usePrefersReducedMotion();
  const [shown, setShown] = useState(target);
  const from = useRef(target);
  const frame = useRef(0);
  const mounted = useRef(false);

  useEffect(() => {
    const finish = () => { from.current = target; setShown(target); };

    if (!mounted.current) {
      mounted.current = true;
      finish();
      return undefined;
    }
    if (reduced || !Number.isFinite(target) || !Number.isFinite(from.current)
        || Math.abs(target - from.current) <= minDelta) {
      finish();
      return undefined;
    }

    const start = performance.now();
    const origin = from.current;
    const step = (now) => {
      const t = Math.min(1, (now - start) / duration);
      // easeOutCubic — fast off the mark and settling, which is what makes it
      // read as the number arriving rather than as a progress bar.
      const eased = 1 - Math.pow(1 - t, 3);
      setShown(origin + (target - origin) * eased);
      if (t < 1) frame.current = requestAnimationFrame(step);
      else from.current = target;
    };
    frame.current = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame.current);
  }, [target, duration, minDelta, reduced]);

  return shown;
}

/**
 * True once, shortly after mount — the switch an entrance animation flips.
 *
 * Used where the animation is a CSS *transition* rather than a keyframe: render
 * the collapsed state, then flip on the next frame so there is something to
 * transition from. Two nested rAFs, not one: a single frame is not reliably
 * enough for the browser to have committed the initial style, and skipping the
 * commit means the element simply appears in its final state.
 */
export function useEntered() {
  const reduced = usePrefersReducedMotion();
  const [entered, setEntered] = useState(reduced);

  useEffect(() => {
    if (reduced) { setEntered(true); return undefined; }
    let inner = 0;
    const outer = requestAnimationFrame(() => {
      inner = requestAnimationFrame(() => setEntered(true));
    });
    return () => { cancelAnimationFrame(outer); cancelAnimationFrame(inner); };
  }, [reduced]);

  return entered;
}

/**
 * The ids in `items` that were not there last time, for a one-shot highlight.
 *
 * The alert feed re-renders wholesale every five seconds, so without this a new
 * alert simply appears at the top of a list of twelve and is indistinguishable
 * from the eleven that were already there. With it, the arrival flashes once.
 *
 * Returns an empty set on the first pass: on load *every* row is new, and
 * flashing all of them says nothing.
 */
export function useNewIds(ids) {
  const seen = useRef(null);
  const [fresh, setFresh] = useState(() => new Set());

  useEffect(() => {
    const current = new Set(ids);
    if (seen.current === null) {
      seen.current = current;
      return undefined;
    }
    const added = ids.filter((id) => !seen.current.has(id));
    seen.current = current;
    if (added.length === 0) return undefined;

    setFresh(new Set(added));
    // Cleared rather than left set, so a row that stays on screen for an hour
    // is not permanently marked as new.
    const t = window.setTimeout(() => setFresh(new Set()), 1600);
    return () => window.clearTimeout(t);
    // Joined rather than passed as an array: a fresh array identity every poll
    // would re-run this on every render and never settle.
  }, [ids.join(',')]);   // eslint-disable-line react-hooks/exhaustive-deps

  return fresh;
}
