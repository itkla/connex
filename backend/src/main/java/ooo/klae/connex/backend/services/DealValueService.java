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
     * Resolves the realized value for a won or lost transition. Only a won deal carries realized
     * value: with line items it derives from the line-item total, otherwise it keeps the supplied or
     * existing amount. A lost or undetermined outcome always resolves to zero regardless of any
     * client-supplied value, because {@code deal_metrics.closed_revenue} sums {@code actual_value}
     * for every closed deal, so crediting a lost deal with booking value would inflate reported
     * revenue.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal resolveActualValueForClose(
            int workspaceId, Deal lockedDeal, Boolean won, BigDecimal requestedActualValue) {
        if (!Boolean.TRUE.equals(won)) {
            return money(null);
        }
        boolean hasLineItems = dealLineItemMapper.countByDealId(workspaceId, lockedDeal.getId()) > 0;
        if (hasLineItems) {
            BigDecimal derived = money(dealLineItemMapper.sumLineTotals(workspaceId, lockedDeal.getId()));
            if (requestedActualValue != null && money(requestedActualValue).compareTo(derived) != 0) {
                throw new ConflictException(LINE_ITEM_VALUE_CONFLICT);
            }
            return derived;
        }
        return requestedActualValue == null
            ? money(lockedDeal.getActualValue())
            : money(requestedActualValue);
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

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
