---
name: web-ui-check
description: Rules and a browser check for any page, landing, site or app screen the owner asks for. Use when building or changing a site, landing, promo page, links page, video scene or UI mock, and before saying it is done.
---

# Web / UI work

The owner's standing rule: no template slop (dark gradient + three glass cards + purple button). Use the right tool for the task, not all at once.

## Before writing
1. Context first: read the brand and existing pages (`docs/`, `docs/design/`, `CLAUDE.md`). Colors, fonts and tone come from there.
2. Library facts (Tailwind, Next.js, shadcn/ui, Supabase ...) must come from current docs, not memory. When a docs MCP (Context7) is connected, use it; otherwise say which version the fact is from.
3. Components: take a proven base (shadcn/ui blocks, Aceternity UI hero/backgrounds) when the project is React. `docs/` is plain static HTML: no framework there, no new dependency for one page.

## Rules (web design guidelines)
- Accessibility: text contrast, visible focus, labels on inputs, alt text, semantic landmarks, `prefers-reduced-motion`.
- Mobile first: 360 px wide, no horizontal scroll, tap targets at least 44 px, safe 16 px side gutters.
- States: loading, empty, error, disabled, hover/active for every control.
- Forms: real input types, autocomplete, inline errors, never block paste.
- Motion: short, purposeful, never the only way to see content.
- Speed: no unused libraries, no huge images, lazy-load below the fold. For React/Next: no needless re-renders, split heavy bundles.
- Public text never says "VPN", "обход", "блокировки", "белые списки"; no competitor branding.

## Before saying "done"
Open the page in a real browser and look at it: Playwright is installed (`/opt/node22/lib/node_modules/playwright`, Chromium `/opt/pw-browsers/chromium`). Take screenshots at 390 px and 1280 px, click the main buttons, read the result. Report what was seen and what was not.
