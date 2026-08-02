package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.mappers.DealMapper;

/**
 * The only writer of a deal row. Every route that creates a deal or changes its outcome persists
 * through this component, which reconciles the close state, writes the row and settles realized
 * value as one indivisible step.
 *
 * <p>The containment is the point. {@code actual_value} is never written by the broad update
 * statement, so a route that changed {@code won} without reconciling realized value would leave a
 * lost deal holding the figure it was won with — and {@code deal_metrics.closed_revenue} sums
 * {@code actual_value} across every closed deal, admitting {@code won = FALSE} explicitly. Keeping
 * {@link DealMapper#update} and the deal insert statements reachable only from here means a new
 * route cannot make that mistake: it has no way to write the row at all without reconciling.
 * {@code DealValueContractArchTest} enforces the containment.
 */
@Component
@RequiredArgsConstructor
public class DealOutcomeWriter {
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String NORMAL = "normal";
    private static final String WON = "won";
    private static final String LOST = "lost";

    private final DealMapper dealMapper;
    private final DealValueService dealValueService;

    /**
     * Resolves a stage's outcome, treating a stageless deal as open. Callers that reconcile many
     * deals against the same stages memoize this themselves and pass the result in.
     * @param workspaceId tenant scope
     * @param stageId the stage the deal sits on, or null
     * @return {@code won}, {@code lost} or {@code normal}
     */
    public String stageOutcome(int workspaceId, Integer stageId) {
        return stageId == null ? NORMAL : dealMapper.getStageOutcome(workspaceId, stageId);
    }

    /**
     * Reconciles the deal's close state, persists it and settles realized value.
     * @param workspaceId tenant scope
     * @param deal the deal to write, carrying its stored realized value
     * @param previousOutcome the won flag before this transition
     * @param requestedActualValue a submitted realized value, or null when the route carries none
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(
            int workspaceId, Deal deal, Boolean previousOutcome, BigDecimal requestedActualValue) {
        write(workspaceId, deal, previousOutcome, requestedActualValue,
            stageOutcome(workspaceId, deal.getStageId()));
    }

    /**
     * Reconciles the deal's close state against an already-resolved stage outcome, persists it and
     * settles realized value.
     * @param workspaceId tenant scope
     * @param deal the deal to write, carrying its stored realized value
     * @param previousOutcome the won flag before this transition
     * @param requestedActualValue a submitted realized value, or null when the route carries none
     * @param stageOutcome the resolved outcome of the deal's stage
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(
            int workspaceId, Deal deal, Boolean previousOutcome,
            BigDecimal requestedActualValue, String stageOutcome) {
        applyCloseState(deal, stageOutcome);
        dealMapper.update(deal);
        dealValueService.reconcileRealizedValue(
            workspaceId, deal, previousOutcome, requestedActualValue);
    }

    /**
     * Applies the outcome a new deal is created with and inserts it.
     * @param deal the deal to insert
     * @param requestedActualValue a submitted realized value, or null when the route carries none
     * @param stageOutcome the resolved outcome of the deal's stage
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void create(Deal deal, BigDecimal requestedActualValue, String stageOutcome) {
        prepareForInsert(deal, requestedActualValue, stageOutcome);
        dealMapper.insert(deal);
    }

    /**
     * Applies the outcome each new deal is created with and inserts them in one batch.
     * @param deals the deals to insert, each with the realized value and stage outcome it carries
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createBatch(List<NewDeal> deals) {
        for (NewDeal item : deals) {
            prepareForInsert(item.deal(), item.requestedActualValue(), item.stageOutcome());
        }
        dealMapper.insertBatch(deals.stream().map(NewDeal::deal).toList());
    }

    /**
     * Reconciles a deal's close fields so {@code won} and {@code closedAt} always agree, without
     * persisting anything. Exposed for callers that must diff the reconciled bean against its stored
     * state before deciding whether the row is worth writing; it is idempotent, so a following
     * {@link #write} re-applies it harmlessly.
     *
     * <p>The outcome is explicit and stage-independent: {@code won} (TRUE=won, FALSE=lost, NULL=open)
     * is set by the client and may be set at ANY stage — a deal can win or lose mid-pipeline.
     * {@code closedAt} follows {@code won} (stamped when an outcome exists, cleared when open). As a
     * convenience, a deal sitting on a terminal stage is forced to that outcome — moving a deal onto
     * "Closed Won" still wins it.
     * @param deal the deal whose close fields are reconciled in place
     * @param stageOutcome the resolved outcome of the deal's stage
     */
    public void applyOutcome(Deal deal, String stageOutcome) {
        applyCloseState(deal, stageOutcome);
    }

    private static void applyCloseState(Deal deal, String stageOutcome) {
        if (WON.equals(stageOutcome)) {
            deal.setWon(true);
        } else if (LOST.equals(stageOutcome)) {
            deal.setWon(false);
        }
        if (deal.getWon() == null) {
            deal.setClosedAt(null);
            deal.setClosedReason(null);
        } else if (deal.getClosedAt() == null || deal.getClosedAt().isBlank()) {
            deal.setClosedAt(LocalDateTime.now(ZoneOffset.UTC).format(MYSQL_DATETIME));
        }
    }

    private void prepareForInsert(Deal deal, BigDecimal requestedActualValue, String stageOutcome) {
        applyCloseState(deal, stageOutcome);
        deal.setActualValue(
            dealValueService.resolveRealizedValueForNewDeal(deal.getWon(), requestedActualValue));
    }

    /** A deal awaiting insert, with the realized value and resolved stage outcome it carries. */
    public record NewDeal(Deal deal, BigDecimal requestedActualValue, String stageOutcome) {}
}
