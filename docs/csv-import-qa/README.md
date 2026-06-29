# CSV import/export — QA kit

Sample files and a checklist for manually verifying the CSV import/export feature (#57) in the browser. Run the backend (`./gradlew bootRun`) and frontend (`pnpm dev`), sign in, and pick a workspace.

The samples deliberately include duplicates, invalid rows, custom-field columns, thousands-separators, Japanese text, and formula-injection-shaped values so each path gets exercised.

## 1. Contacts — `contacts-sample.csv`

Contacts list → **Import** → upload the file.

- **Map step:** Name / Email / Phone / Company / Tags should auto-suggest correctly. Map **Budget → Create custom field → Number**. (Note: `Mobile`-style duplicates of an already-mapped field auto-fall to *Ignore* — no two columns map to the same field.)
- **Review step (fill empty):** expect **3 new** (Alice, Bob, 田中 花子), **2 invalid** (the blank-name row, the `not-an-email` row), **1 skip** (the duplicate Alice — same email, within-file dedup).
- Toggle **Skip / Overwrite** → the counts update live.
- **Import** → confirm: contacts created; **companies auto-created** (Acme Inc, Globex); **tags** created (vip, lead, customer); the **Budget** custom field exists with `1000000` (thousands-separators accepted); Japanese name renders with the JP font.
- **Re-import the same file** → expect **updates** (fill-empty), no duplicates, no new companies/tags.

## 2. Companies — `companies-sample.csv`

Companies list → Import.

- Expect **Acme Incorporated skipped** (its `http://www.acme.com/` normalizes to the same host as `https://acme.com`), **Globex Corp + Beta created**.

## 3. Deals — `deals-sample.csv`

Prereq: the workspace has a pipeline named **Sales** with a stage named **Lead** (create one if needed).

- Expect **Big Renewal + Small Deal created**, and **3 invalid**: `Bad Stage Deal` (unknown stage), `No Value Deal` (`abc` is not a number — now flagged in preview, not silently 0), the blank-name row.
- Big Renewal should link the existing contact **alice@acme.test** and store amount `500000`.

## 4. Export round-trip

- Apply a search/filter on Contacts, then **Export** → the CSV should contain only the on-screen rows (companies/deals export the current filtered view too).
- Open in Excel **and** a plain text editor: a formula-shaped cell stays text, and a phone like `+81-90-1111-2222` is **not** corrupted when the exported file is re-imported (leading apostrophe is stripped on import).
- Trigger a failed row, then **Download errors** on the done step → the error CSV lists the failed rows + reasons.

## 5. i18n + accessibility

- Switch language to **日本語** and re-open the wizard → all labels translated.
- Keyboard only: Tab to **Import**, Enter; in the upload step Tab to the dropzone, Enter opens the file picker.
- With OS "reduce motion" on, step transitions fade without sliding.
