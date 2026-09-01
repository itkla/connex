/**
 * Design tokens for Connex transactional email. Values mirror the application
 * theme in {@code frontend/app/globals.css}: the neutral ramp, the brand lime,
 * and the 10px corner radius. Colours are hex because email clients do not
 * support oklch or CSS custom properties in inline styles.
 */
export const palette = {
    brand: "#73d200",
    brandInk: "#0a0a0a",
    ink: "#0a0a0a",
    body: "#3f3f46",
    muted: "#71717a",
    mutedOnPage: "#68686f",
    hairline: "#e4e4e7",
    surface: "#ffffff",
    surfaceSunken: "#fafaf9",
    page: "#f2f3ef",
    masthead: "#0a0a0a",
    mastheadInk: "#fafafa",
    mastheadMuted: "#8f8f8f",
} as const;

export const fontStack = {
    latin: "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif",
    japanese:
        "-apple-system,BlinkMacSystemFont,'Hiragino Kaku Gothic ProN','Noto Sans JP','Yu Gothic','Segoe UI',Meiryo,sans-serif",
} as const;

/** Card width in pixels. 560 keeps body copy near a 60-character measure at 16px. */
export const cardWidth = 560;

/**
 * Progressive-enhancement stylesheet injected into {@code <head>}: responsive
 * padding, and a dark-mode palette for clients that honour
 * {@code prefers-color-scheme}. Every rule here is an override; the inline
 * styles carry the light-mode design on their own, so clients that strip head
 * styles (some Gmail configurations) still render the intended email.
 */
export const enhancementCss = `
:root { color-scheme: light dark; supported-color-schemes: light dark; }
body { -webkit-text-size-adjust: 100%; -ms-text-size-adjust: 100%; }
a { text-decoration: none; }
.cx-figure-value { font-variant-numeric: tabular-nums; }
@media only screen and (max-width: 600px) {
  .cx-pad { padding-left: 24px !important; padding-right: 24px !important; }
  .cx-heading { font-size: 25px !important; }
  .cx-cta { display: block !important; text-align: center !important; }
  .cx-figure { display: block !important; width: 100% !important; padding-right: 0 !important; }
  .cx-figure + .cx-figure { padding-top: 20px !important; }
}
@media (prefers-color-scheme: dark) {
  .cx-page { background-color: #0d0d0d !important; }
  .cx-card { background-color: #161616 !important; border-color: #2b2b2b !important; }
  .cx-masthead { background-color: #000000 !important; }
  .cx-heading, .cx-figure-value, .cx-panel-value { color: #fafafa !important; }
  .cx-lead { color: #c8c8c8 !important; }
  .cx-muted, .cx-footnote, .cx-panel-label, .cx-figure-label, .cx-fallback { color: #909090 !important; }
  .cx-fallback-link { color: #b0b0b0 !important; }
  .cx-panel { background-color: #1f1f1f !important; border-color: #2f2f2f !important; }
  .cx-rule { border-color: #2b2b2b !important; }
}
`.trim();
