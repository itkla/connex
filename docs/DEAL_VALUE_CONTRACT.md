# Deal Value Contract

> Status: implemented in Wave 4 (WS3). Defines where a deal's monetary value comes from, who may
> write it, and how every reported revenue figure relates to it. Read alongside
> [`FigureReconciliationRegistry`](../backend/src/main/java/ooo/klae/connex/backend/tenant/FigureReconciliationRegistry.java)
> and its arch test, which encode the per-figure claims this document explains in prose.

---

## 1. The contract

Money in Connex is `BigDecimal`, **scale 2, `HALF_UP`**. Every deal has exactly one currency, which
also governs its line items. **There is no FX anywhere**, and aggregates never sum across
currencies — every revenue figure is currency-partitioned.

`deal.value_source` declares where `deal.value` comes from:

| `value_source` | `deal.value` is | Manual edits |
| --- | --- | --- |
| `manual` | operator-entered | allowed |
| `line_items` | derived: `SUM(deal_line_item.line_total)` | rejected with HTTP 409 |

The canonical value is always read through `DealValueService`. Only that service writes a derived
value; no other code may call the value/source mapper statements. The broad `DealMapper.update`
statement deliberately **does not** write `value`, `actual_value` or `value_source`, so the
close/reopen/move paths cannot clobber a freshly reconciled amount with a stale bean.

### Recomputation

`DealLineItemService.create/update/delete` are transactional and always lock the parent deal
before touching a line. After every line write the deal value is recomputed and persisted, and
`value_source` is set to `line_items`.

Deleting the **last** line reverts `value_source` to `manual` and **retains the last derived
total** — the number does not reset to zero, it simply becomes editable again.

### Winning or losing a deal — every path

Realized value (`actual_value`) is resolved the same way on **every** win transition, not just the
close dialog. A deal wins through three paths, and all three agree:

1. The close dialog (`POST /api/deals/{id}/close`).
2. A Kanban drag onto a "Closed Won" stage (`move`).
3. A form edit that sets a won-outcome stage (`update`).

- **Won deals with line items:** `actual_value` is derived from the line-item total on whichever
  path wins the deal. Through the close dialog, supplying a value that differs from the derived
  total is rejected (`LINE_ITEM_VALUE_CONFLICT`, HTTP 409); the drag and edit paths carry no value
  input and simply take the derived total. So the same ¥5M line-item deal reports ¥5M realized
  whether it is won by dialog, drag, or edit — the figure never depends on how it was won.
- **Won deals without line items:** realized value keeps the amount entered in the close dialog, or
  the deal's existing `actual_value` (zero for a deal that was open) when no amount is supplied.
- **Lost deals:** `actual_value` is **always zero**, on every path. A client-supplied value on a
  lost close is ignored, and `CloseDealRequest.actualValue` rejects negatives.

The lost-deal rule exists because the deal-browser `closed_revenue` figure sums `actual_value`
across **all** closed deals, not just won ones. Deriving the booking value into a lost deal would
inflate reported revenue by the full value of every deal the business failed to win. A lost deal
records zero realized revenue; its booking value remains visible through `deal.value` and its line
items.

### Realized value is frozen at the win

`actual_value` is derived **once**, at the moment the deal transitions to won. Editing line items
**after** the win moves the canonical `value` (still `line_items`-sourced) but deliberately does
**not** re-derive `actual_value`. This is intentional: realized revenue is a booking snapshot taken
when the deal closed, so `value` and `actual_value` can legitimately differ on a won deal. Reports
that need the current book value read `value`; reports of realized revenue read the frozen
`actual_value`. Re-deriving on post-win edits would let a later price change silently rewrite a
figure that was already reported.

### Recurring line items — the bookings view

`grandTotal = oneTimeTotal + recurringTotal`. A recurring line contributes its **per-period**
amount exactly once. This is a **bookings** view, not ARR, MRR or TCV.

**Term multiplication is explicitly out of scope.** Connex does not multiply a recurring line by a
contract term, because it does not model contract terms. Do not read a deal value containing
recurring lines as annualized revenue.

---

## 2. The V140 backfill — a historical-number change

`V140__deal_value_source.sql` adds `value_source` and **recomputes `deal.value` for every deal
that has line items**.

**Administrators will observe historical numbers change.** Before Wave 4, adding line items to a
deal never wrote the derived total back to `deal.value`: the deal kept whatever value it was
created with, very often `0`. Those deals reported a stale figure to every consumer — the deal
browser, the pipeline board, the Home chart, and every report. The backfill repairs them.

Expect: pipeline and revenue totals to **increase** for any workspace that used line items, by the
amount those deals were previously under-reporting. This is a correction, not a regression. No
figure that was previously correct changes.

Migration ordering is load-bearing. MySQL 8.4 enforces `CHECK` constraints, so the column is added
with its `'manual'` default (which satisfies the constraint) **before** the backfill runs.

**Operator preflight** — run before deploying, and resolve anything found:

```sql
-- Line items whose currency disagrees with their deal. Application code forces these to match,
-- so any row here is out-of-band data corruption. V140 must not perform FX conversion.
SELECT li.workspace_id, li.deal_id, d.currency AS deal_currency, li.currency AS line_currency,
       COUNT(*) AS line_count
FROM deal_line_item li
JOIN deal d ON d.workspace_id = li.workspace_id AND d.id = li.deal_id
WHERE li.currency <> d.currency
GROUP BY li.workspace_id, li.deal_id, d.currency, li.currency;
```

**Recovery.** MySQL auto-commits DDL, so if the backfill `UPDATE` fails (lock timeout, or a summed
total exceeding `DECIMAL(15,2)`) the column will exist while Flyway records the migration as
failed. Re-running then fails on the duplicate column. To recover, drop the column and let Flyway
re-apply the migration cleanly:

```sql
ALTER TABLE deal DROP CONSTRAINT chk_deal_value_source;
ALTER TABLE deal DROP COLUMN value_source;
```

---

## 3. Figure reconciliation

Different surfaces answer different questions, so they legitimately report different numbers from
the same data. Those differences are **declared**, not accidental:
`FigureReconciliationRegistry` records each figure's value source, archive posture, restriction
posture, sharing posture, period basis and owner basis, and `FigureReconciliationArchTest`
re-derives every claim from the mapper SQL, failing loudly when SQL and declaration drift apart.

### Declared divergences — do not "fix" these

1. **Archived accounts.** The deal browser buckets a deal whose company is archived as
   *unassigned*; reports retain the archived account's name. Reports cover settled periods, and
   dropping or relabelling an account archived after the fact would change a number that was
   already reported.
2. **Filtered vs unfiltered browser metrics.** `dealMetrics` applies neither archive filtering nor
   owner scoping; `dealMetricsFiltered` applies both. They are different questions, not a bug.
3. **Period bounds on open pipeline.** Reports bound open pipeline by expected close date. The
   deal browser and the Home chart do not bound it at all.
4. **Owner grouping is current-owner.** Reassigning a deal retroactively moves its historical
   figures to the new owner. Point-in-time ownership is a 1.1 follow-up.

Note also that the Home pipeline chart mixes bases within one row: won value is period-bounded
while open value is unbounded.

### Owner scope is not an authorization boundary

`MemberScope` ("my deals" / "team deals" / "unassigned") is a **presentational filter**. Any
workspace member may select any scope, and `REPORT_READ` is a base member permission — so reports
expose nothing the deal browser does not already expose to the same user. Do not read the
registry's owner-basis declarations as a confidentiality claim. Per-owner deal confidentiality
does not exist in Connex today; introducing it would require deriving the scope floor from role on
every surface, not just in Reports.

---

## 4. Where to look

| Concern | File |
| --- | --- |
| Canonical value, reconciliation, close resolution | `backend/.../services/DealValueService.java` |
| Line-item computation and totals | `backend/.../services/DealLineItemService.java` |
| Figure declarations | `backend/.../tenant/FigureReconciliationRegistry.java` |
| SQL-derived verification of those declarations | `backend/.../architecture/FigureReconciliationArchTest.java` |
| Contract invariants (no double money, single writer) | `backend/.../architecture/DealValueContractArchTest.java` |
| Schema | `backend/src/main/resources/db/migration/tenant/V140__deal_value_source.sql` |
