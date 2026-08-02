-- Repairs the rows the won-to-lost defect wrote. Before this release only the close dialog zeroed
-- actual_value on a loss: a deal lost through the form, a Kanban drag, a bulk stage change, a rule
-- action or a CSV import kept the figure it was won with. deal_metrics.closed_revenue and the
-- revenue series both sum actual_value across every closed deal, admitting won = FALSE explicitly,
-- so each of those rows has been inflating reported revenue by its full booking value.
--
-- A lost deal records zero realized revenue (see docs/DEAL_VALUE_CONTRACT.md). Its booking value is
-- untouched and remains visible through deal.value and its line items, so nothing is lost here that
-- the deal does not still carry.
--
-- Administrators will observe closed revenue DROP for any workspace that lost a previously won
-- deal outside the close dialog. That is the correction, not a regression.
UPDATE deal
SET actual_value = 0.00
WHERE won = FALSE
  AND actual_value <> 0;
