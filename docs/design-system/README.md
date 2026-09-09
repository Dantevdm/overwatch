# Design System

Overwatch's UI is built on the **i1 Design System foundations**. This folder holds
the reference kit and the tokens the application actually consumes.

| File | Purpose |
|---|---|
| `tokens.css` | **The one to import.** Every colour, type, spacing, radius, shadow and layout token as CSS custom properties, with light and dark themes. |
| `i1-platform-ui-kit.html` | The original foundations document — open it in a browser to see the full component and screen reference. |
| `support.js` | Supporting script for the UI kit document. |

## Using the tokens

Import once at the application root, then reference tokens by role. Never hardcode a
hex value in a component — that is what breaks the dark theme.

```jsx
// src/main.jsx
import '../docs/design-system/tokens.css';
```

```css
.stat-tile {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  padding: var(--space-4);
}

.stat-value {
  font-size: var(--text-2xl);
  color: var(--fg);
  font-variant-numeric: tabular-nums;
}
```

## Key values

**Brand** `#0067a0`, with a gradient to `#2f9bd4` for the sidebar and hero surfaces.

**Layout** 256px sidebar, 56px topbar. The sidebar is brand-filled; content sits on
`--canvas` with cards on `--bg`.

**Radius** 4 / 6 / 8 / 12px. Cards use `--radius-xl`, controls use `--radius-md`,
badges use `--radius-full`.

**Type** System sans stack throughout; the mono stack for IDs, amounts and anything
tabular. Amounts and table columns set `font-variant-numeric: tabular-nums` so digits
align down a column.

**Status colours** are reserved for state (success / warning / info / danger) and
never reused as chart series colours. Charts draw from `--chart-1` through
`--chart-4`, which are brand-derived.

## Theming

The light palette is defined on bare `:root`. Dark redefines only the token values,
under two scopes — the `prefers-color-scheme` media query for the OS setting, and
`[data-theme="dark"]` for an explicit toggle — so a user's choice wins in both
directions. Because components style through tokens rather than literals, neither
theme needs component-level overrides.
