package ooo.klae.connex.backend.ai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.PersonEmployment;
import ooo.klae.connex.backend.dto.PersonConnectionDto;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.ConnectionService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.PersonService;

/**
 * Assembles the masked relationship-graph and history context shared by the deal-brief and
 * risk-rationale prompts — account win/loss history, stakeholder employment history, and network
 * connections — the differentiated signal a deterministic summary cannot synthesize. Every block is
 * best-effort: a fetch failure appends {@code none} rather than breaking the feature, and every
 * identifier is tokenized through {@link MaskingEngine} exactly as the assemblers do.
 */
@Service
@RequiredArgsConstructor
public class AiRelationshipContext {
    static final int MAX_ACCOUNT_DEALS = 6;
    static final int MAX_EMPLOYMENT = 4;
    static final int MAX_CONNECTIONS = 5;
    static final int MAX_ALLOWED_TEXT_CHARS = 120;
    static final int MAX_FREE_TEXT_CHARS = 200;

    private final DealService dealService;
    private final PersonService personService;
    private final ConnectionService connectionService;
    private final CompanyService companyService;
    private final Clock clock;

    /**
     * Appends the company's industry, when known.
     * @param prompt prompt under construction
     * @param companyId company to profile
     * @param context request-local masking context
     */
    public void appendCompanyProfile(StringBuilder prompt, int companyId, MaskingContext context) {
        if (companyId <= 0) {
            return;
        }
        try {
            Company company = companyService.getCompanyById(companyId);
            String industry = company == null ? "" : maskAllowed(company.getIndustry(), context);
            if (!isBlank(industry)) {
                prompt.append("Industry: ").append(industry).append('\n');
            }
        } catch (RuntimeException exception) {
            return;
        }
    }

    /**
     * Appends prior and concurrent deals with the same company and their outcomes.
     * @param prompt prompt under construction
     * @param companyId company whose deal history to summarize
     * @param currentDealId deal being analysed, excluded from the history
     * @param context request-local masking context
     */
    public void appendAccountHistory(StringBuilder prompt, int companyId, int currentDealId, MaskingContext context) {
        prompt.append("\nACCOUNT_HISTORY\n");
        List<String> lines = accountHistoryLines(companyId, currentDealId, context);
        if (lines.isEmpty()) {
            prompt.append("- none\n");
            return;
        }
        lines.forEach(prompt::append);
    }

    /**
     * Appends a stakeholder's employment history and strongest network connections under their token.
     * @param prompt prompt under construction
     * @param personId stakeholder person id
     * @param personToken the stakeholder's issued mask token
     * @param context request-local masking context
     */
    public void appendStakeholderBackground(
            StringBuilder prompt, int personId, String personToken, MaskingContext context) {
        if (personId <= 0 || isBlank(personToken)) {
            return;
        }
        List<String> lines = stakeholderBackgroundLines(personId, context);
        if (lines.isEmpty()) {
            return;
        }
        prompt.append("- Person: ").append(personToken).append('\n');
        lines.forEach(prompt::append);
    }

    private List<String> accountHistoryLines(int companyId, int currentDealId, MaskingContext context) {
        if (companyId <= 0) {
            return List.of();
        }
        try {
            List<Deal> deals = safeList(
                dealService.getAccountHistoryDeals(companyId, currentDealId, MAX_ACCOUNT_DEALS));
            List<String> lines = new ArrayList<>();
            for (Deal deal : deals) {
                lines.add(accountHistoryLine(deal, context));
                if (lines.size() == MAX_ACCOUNT_DEALS) {
                    break;
                }
            }
            return lines;
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private String accountHistoryLine(Deal deal, MaskingContext context) {
        StringBuilder line = new StringBuilder("- Outcome: ").append(outcome(deal.getWon()));
        double value = deal.getWon() != null && deal.getWon() && deal.getActualValue() > 0
                ? deal.getActualValue()
                : deal.getValue();
        if (Double.isFinite(value) && value > 0) {
            line.append("; Value: ").append(amount(value, deal.getCurrency(), context));
        }
        if (deal.getWon() != null) {
            appendInline(line, "Closed", relativeAge(deal.getClosedAt()));
        } else {
            appendInline(line, "Expected close", relativeAge(deal.getExpectedCloseDate()));
        }
        appendInline(line, "Reason", maskFree(deal.getClosedReason(), context));
        return line.append('\n').toString();
    }

    private List<String> stakeholderBackgroundLines(int personId, MaskingContext context) {
        List<String> lines = new ArrayList<>();
        appendEmploymentLines(lines, personId, context);
        appendConnectionLines(lines, personId, context);
        return lines;
    }

    private void appendEmploymentLines(List<String> lines, int personId, MaskingContext context) {
        try {
            int appended = 0;
            for (PersonEmployment employment : safeList(personService.getEmploymentHistory(personId))) {
                if (employment == null || isBlank(employment.getCompanyName())) {
                    continue;
                }
                StringBuilder line = new StringBuilder("  Employment: ")
                        .append(MaskingEngine.maskField(EntityKind.COMPANY, employment.getCompanyName(), context));
                appendInline(line, "Title", maskAllowed(employment.getTitle(), context));
                appendInline(line, "Started", relativeAge(employment.getStartedAt()));
                if (isBlank(employment.getEndedAt())) {
                    appendInline(line, "Status", "current");
                } else {
                    appendInline(line, "Left", relativeAge(employment.getEndedAt()));
                }
                lines.add(line.append('\n').toString());
                if (++appended == MAX_EMPLOYMENT) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            return;
        }
    }

    private void appendConnectionLines(List<String> lines, int personId, MaskingContext context) {
        try {
            List<PersonConnectionDto> connections = new ArrayList<>();
            for (PersonConnectionDto connection : safeList(
                    connectionService.getTopConnections(personId, MAX_CONNECTIONS))) {
                if (connection != null && !isBlankConnectionName(connection.getPersonName())
                        && connection.getSuspendedAt() == null
                        && connection.getProvisionCeasedAt() == null) {
                    connections.add(connection);
                }
            }
            connections.sort(Comparator.comparingInt(PersonConnectionDto::getStrength).reversed());
            int appended = 0;
            for (PersonConnectionDto connection : connections) {
                StringBuilder line = new StringBuilder("  Connection: ")
                        .append(MaskingEngine.maskField(EntityKind.PERSON, connection.getPersonName(), context));
                appendInline(line, "Type", maskAllowed(connection.getType(), context));
                line.append("; Strength: ").append(connection.getStrength());
                appendInline(line, "Note", maskFree(connection.getNote(), context));
                lines.add(line.append('\n').toString());
                if (++appended == MAX_CONNECTIONS) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            return;
        }
    }

    private String relativeAge(String isoDate) {
        LocalDate date = parseDate(isoDate);
        if (date == null) {
            return "";
        }
        LocalDate today = LocalDate.now(clock);
        long days = ChronoUnit.DAYS.between(date, today);
        if (days == 0) {
            return "today";
        }
        long magnitude = Math.abs(days);
        String span;
        if (magnitude < 45) {
            span = magnitude + " days";
        } else {
            span = Math.abs(ChronoUnit.MONTHS.between(date.withDayOfMonth(1), today.withDayOfMonth(1))) + " months";
        }
        return days > 0 ? span + " ago" : "in " + span;
    }

    private static LocalDate parseDate(String value) {
        if (isBlank(value) || value.strip().length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().substring(0, 10));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String outcome(Boolean won) {
        if (won == null) {
            return "open";
        }
        return won ? "won" : "lost";
    }

    private static void appendInline(StringBuilder line, String label, String value) {
        if (!isBlank(value)) {
            line.append("; ").append(label).append(": ").append(value);
        }
    }

    private static String amount(double value, String currency, MaskingContext context) {
        String amount = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        String safeCurrency = maskAllowedStatic(currency, context);
        return isBlank(safeCurrency) ? amount : amount + " " + safeCurrency;
    }

    private String maskAllowed(String value, MaskingContext context) {
        return maskAllowedStatic(value, context);
    }

    private String maskFree(String value, MaskingContext context) {
        if (isBlank(value)) {
            return "";
        }
        return truncate(MaskingEngine.maskFreeText(normalize(value), context), MAX_FREE_TEXT_CHARS);
    }

    private static String maskAllowedStatic(String value, MaskingContext context) {
        if (isBlank(value)) {
            return "";
        }
        return truncate(MaskingEngine.maskFreeText(normalize(value), context), MAX_ALLOWED_TEXT_CHARS);
    }

    private static String normalize(String value) {
        return value.strip().replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints - 1);
        return value.substring(0, end) + "…";
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isBlankConnectionName(String value) {
        return value == null || value.codePoints().allMatch(AiRelationshipContext::isConnectionWhitespace);
    }

    private static boolean isConnectionWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0x0085;
    }
}
