"use client";

import dynamic from "next/dynamic";

/**
 * The single lazy boundary for the CSV import wizard.
 *
 * `ImportDialog` is rendered from two independent places — the records list header
 * (`RecordsActions`) and the global action overlay host — and both want it code-split. Calling
 * `next/dynamic` in each of them split one module across two chunk groups: the overlay host is
 * mounted by the app shell on every route, while `RecordsActions` exists only on the four
 * records browsers. On those four routes the build wrote a per-route loadable entry naming a
 * chunk it never emitted, so every records browser requested a missing
 * `/_next/static/chunks/*.js` and took a 404.
 *
 * Routing both callers through this one boundary keeps the wizard lazily loaded and gives the
 * module a single chunk group. Note that the asymmetry is what mattered — the shell-wide
 * boundaries that `ActionOverlayHost` and `QuickCreateLauncher` duplicate between themselves
 * resolve correctly, because both of those live in the same shell chunk group.
 */
const LazyImportDialog = dynamic(() => import("@/app/components/import/ImportDialog"));

export default LazyImportDialog;
