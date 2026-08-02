package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DealLineItemMapper;
import ooo.klae.connex.backend.mappers.DealMapper;

/** Owns the canonical deal-value contract across manual and line-item-derived amounts. */
@Service
@RequiredArgsConstructor
public class DealValueService {
    private static final String MANUAL = "manual";
    private static final String LINE_ITEMS = "line_items";
    private static final String LINE_ITEM_VALUE_CONFLICT =
        "Cannot manually edit the deal value while line items exist; update or remove the line items first";

    private final DealMapper dealMapper;
    private final DealLineItemMapper dealLineItemMapper;

    /** Returns the normalized authoritative value for the deal's current source. */
    @Transactional(readOnly = true)
    public BigDecimal canonicalValue(int workspaceId, Deal deal) {
        if (LINE_ITEMS.equals(deal.getValueSource())) {
            return money(dealLineItemMapper.sumLineTotals(workspaceId, deal.getId()));
        }
        return money(deal.getValue());
    }

    /** Applies a manual value when the locked deal has no conflicting line-item total. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal setManualValue(int workspaceId, Deal lockedDeal, BigDecimal requestedValue) {
        BigDecimal value = money(requestedValue);
        if (dealLineItemMapper.countByDealId(workspaceId, lockedDeal.getId()) > 0) {
            BigDecimal canonical = canonicalValue(workspaceId, lockedDeal);
            if (canonical.compareTo(value) != 0) {
                throw new ConflictException(LINE_ITEM_VALUE_CONFLICT);
            }
            return canonical;
        }
        dealMapper.updateValueAndSource(workspaceId, lockedDeal.getId(), value, MANUAL);
        return value;
    }

    /** Reconciles the locked deal after a line-item mutation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reconcileLineItems(int workspaceId, Deal lockedDeal) {
        if (dealLineItemMapper.countByDealId(workspaceId, lockedDeal.getId()) > 0) {
            BigDecimal total = money(dealLineItemMapper.sumLineTotals(workspaceId, lockedDeal.getId()));
            dealMapper.updateValueAndSource(workspaceId, lockedDeal.getId(), total, LINE_ITEMS);
            return total;
        }
        dealMapper.updateValueSource(workspaceId, lockedDeal.getId(), MANUAL);
        return money(lockedDeal.getValue());
    }

    /**
     * Resolves the realized value a deal takes at the moment it wins, independently of how it got
     * there. A deal with line items derives the line-item total and rejects a differing submitted
     * amount; a deal without them records exactly what was submitted, and zero when nothing was.
     *
     * <p>The derived total never inherits the deal's stored {@code actual_value}. Path-independence
     * is the contract: winning, losing and winning again must land on the same figure as winning the
     * first time, and carrying a stale amount left over from an earlier win would make the result
     * depend on the deal's history rather than on its line items.
     *
     * <p>An amount matching what the deal already holds is an echo rather than an edit, and derives
     * silently. A form edit reads the deal, changes only the stage and saves the whole record back,
     * so the request carries the stored realized value whether or not the operator touched the
     * field; treating that as a manual edit would refuse the ordinary act of dragging a line-item
     * deal onto a won stage. Only an amount that matches neither the stored figure nor the derived
     * total is a real attempt to overwrite line-item revenue by hand.
     */
    private BigDecimal resolveRealizedValueForFreshWin(
            int workspaceId, Deal lockedDeal, BigDecimal requestedActualValue) {
        if (dealLineItemMapper.countByDealId(workspaceId, lockedDeal.getId()) > 0) {
            BigDecimal derived =
                money(dealLineItemMapper.sumLineTotals(workspaceId, lockedDeal.getId()));
            if (isManualOverwrite(requestedActualValue, derived, lockedDeal.getActualValue())) {
                throw new ConflictException(LINE_ITEM_VALUE_CONFLICT);
            }
            return derived;
        }
        return money(requestedActualValue);
    }

    /**
     * Whether a submitted realized value is an operator overwriting the line-item total by hand,
     * rather than a client echoing back a figure it was given.
     */
    private boolean isManualOverwrite(
            BigDecimal requested, BigDecimal derived, BigDecimal stored) {
        if (requested == null) {
            return false;
        }
        BigDecimal submitted = money(requested);
        return submitted.compareTo(derived) != 0 && submitted.compareTo(money(stored)) != 0;
    }

    /**
     * Reconciles and persists realized value for an outcome transition, on every route that can win
     * or lose a deal — the close dialog, a form edit, a Kanban drag, a bulk stage change, a rule
     * action and a CSV import all resolve the same figure here. Three cases:
     * <ul>
     *   <li><b>Lost</b> — always zero, and any client-supplied amount is ignored. This is the whole
     *       reason the reconciliation is centralized: {@code deal_metrics.closed_revenue} sums
     *       {@code actual_value} across every closed deal, so a won-to-lost transition that kept the
     *       won figure would inflate reported revenue by the full value of a deal the business
     *       failed to win.</li>
     *   <li><b>Freshly won</b> — derives from the line-item total, or records the submitted amount
     *       when the deal has no line items. The stored figure is never inherited, so winning a deal
     *       lands on the same value however often it was won and lost before.</li>
     *   <li><b>Already won, or still open</b> — realized value stays frozen at the win and only an
     *       explicit operator edit moves it.</li>
     * </ul>
     * Must run inside the caller's transaction, after the broad update statement that deliberately
     * does not write {@code actual_value}. Updates the passed bean so callers audit the stored figure.
     * @param workspaceId tenant scope
     * @param lockedDeal the deal whose close state was just reconciled, carrying its stored realized value
     * @param previousOutcome the won flag before this transition
     * @param requestedActualValue a client-supplied realized value, or null when the route carries none
     * @return the realized value now stored on the deal
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal reconcileRealizedValue(
            int workspaceId, Deal lockedDeal, Boolean previousOutcome,
            BigDecimal requestedActualValue) {
        if (Boolean.FALSE.equals(lockedDeal.getWon())) {
            return persistRealizedValue(workspaceId, lockedDeal, money(null));
        }
        if (Boolean.TRUE.equals(lockedDeal.getWon()) && !Boolean.TRUE.equals(previousOutcome)) {
            return persistRealizedValue(workspaceId, lockedDeal,
                resolveRealizedValueForFreshWin(workspaceId, lockedDeal, requestedActualValue));
        }
        BigDecimal edited = setRealizedValue(workspaceId, lockedDeal, requestedActualValue);
        lockedDeal.setActualValue(edited);
        return edited;
    }

    /**
     * Resolves the realized value a deal may be created with. A deal created lost or open records
     * zero, so the create endpoint cannot seed reported revenue with an arbitrary amount; a deal
     * created already won keeps the supplied amount, which is the only figure available because a
     * deal has no line items before it exists.
     * @param won the outcome the new deal was reconciled to
     * @param requestedActualValue the realized value the caller supplied, or null when omitted
     * @return the realized value the new deal may be inserted with
     */
    public BigDecimal resolveRealizedValueForNewDeal(Boolean won, BigDecimal requestedActualValue) {
        return Boolean.TRUE.equals(won) ? money(requestedActualValue) : money(null);
    }

    /**
     * Persists an operator's realized-value edit submitted through a full deal update. A won deal
     * whose value is derived from line items rejects a differing amount, the same rule the close
     * dialog applies, so a form can never overwrite a derived realized figure.
     * @param workspaceId tenant scope
     * @param lockedDeal the locked deal carrying its stored realized value
     * @param requestedActualValue the submitted realized value, or null when omitted
     * @return the realized value now stored on the deal
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal setRealizedValue(
            int workspaceId, Deal lockedDeal, BigDecimal requestedActualValue) {
        BigDecimal stored = money(lockedDeal.getActualValue());
        if (requestedActualValue == null) {
            return stored;
        }
        BigDecimal requested = money(requestedActualValue);
        if (requested.compareTo(stored) == 0) {
            return stored;
        }
        if (Boolean.TRUE.equals(lockedDeal.getWon())
                && dealLineItemMapper.countByDealId(workspaceId, lockedDeal.getId()) > 0) {
            throw new ConflictException(LINE_ITEM_VALUE_CONFLICT);
        }
        dealMapper.updateActualValue(workspaceId, lockedDeal.getId(), requested);
        return requested;
    }

    private BigDecimal persistRealizedValue(int workspaceId, Deal lockedDeal, BigDecimal realized) {
        if (realized.compareTo(money(lockedDeal.getActualValue())) != 0) {
            dealMapper.updateActualValue(workspaceId, lockedDeal.getId(), realized);
        }
        lockedDeal.setActualValue(realized);
        return realized;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
