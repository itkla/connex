package ooo.klae.connex.backend.ai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * best-effort: missing data and failed fetches remain distinguishable, and every identifier is
 * tokenized through {@link MaskingEngine} exactly as the assemblers do.
 */
@Service
@RequiredArgsConstructor
public class AiRelationshipContext {
    static final int MAX_ACCOUNT_DEALS = 6;
    static final int MAX_EMPLOYMENT = 4;
    static final int MAX_CONNECTIONS = 5;
    static final int MAX_ALLOWED_TEXT_CHARS = 120;
    static final int MAX_FREE_TEXT_CHARS = 200;

    private static final SourceIdProvider NO_SOURCE_IDS = (kind, id) -> "";

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
     * @return whether the optional company fetch failed
     */
    public boolean appendCompanyProfile(StringBuilder prompt, int companyId, MaskingContext context) {
        if (companyId <= 0) {
            prompt.append("Industry: none\n");
            return false;
        }
        try {
            Company company = companyService.getCompanyById(companyId);
            String industry = company == null ? "" : maskAllowed(company.getIndustry(), context);
            if (isUsableMasked(industry)) {
                prompt.append("Industry: ").append(industry).append('\n');
            } else {
                prompt.append("Industry: none\n");
            }
            return false;
        } catch (RuntimeException exception) {
            prompt.append("Industry: unavailable\n");
            return true;
        }
    }

    /**
     * Appends prior and concurrent deals with the same company and their outcomes.
     * @param prompt prompt under construction
     * @param companyId company whose deal history to summarize
     * @param currentDealId deal being analysed, excluded from the history
     * @param context request-local masking context
     * @return whether the optional history fetch failed
     */
    public boolean appendAccountHistory(
            StringBuilder prompt, int companyId, int currentDealId, MaskingContext context) {
        return appendAccountHistory(prompt, companyId, currentDealId, context, NO_SOURCE_IDS);
    }

    /**
     * Appends prior and concurrent deals and assigns positional ids to the records that are emitted.
     * @param prompt prompt under construction
     * @param companyId company whose deal history to summarize
     * @param currentDealId deal being analysed, excluded from the history
     * @param context request-local masking context
     * @param sourceIds positional source-id provider
     * @return whether the optional history fetch failed
     */
    public boolean appendAccountHistory(
            StringBuilder prompt,
            int companyId,
            int currentDealId,
            MaskingContext context,
            SourceIdProvider sourceIds) {
        Objects.requireNonNull(sourceIds, "sourceIds");
        prompt.append("\nACCOUNT_HISTORY\n");
        FetchResult result = accountHistoryLines(companyId, currentDealId, context, sourceIds);
        if (result.failed()) {
            prompt.append("- unavailable\n");
            return true;
        }
        if (result.lines().isEmpty()) {
            prompt.append("- none\n");
        } else {
            result.lines().forEach(prompt::append);
        }
        return false;
    }

    /**
     * Appends a stakeholder's employment history and strongest network connections under their token.
     * @param prompt prompt under construction
     * @param personId stakeholder person id
     * @param personToken the stakeholder's issued mask token
     * @param context request-local masking context
     * @return ids of connection people whose names or notes were appended
     */
    public List<Integer> appendStakeholderBackground(
            StringBuilder prompt, int personId, String personToken, MaskingContext context) {
        Set<Integer> connectionPersonIds = new LinkedHashSet<>();
        appendStakeholderBackground(
                prompt, personId, personToken, context, NO_SOURCE_IDS, connectionPersonIds);
        return connectionPersonIds.stream().sorted().toList();
    }

    /**
     * Appends stakeholder background and assigns positional ids to emitted person records.
     * @param prompt prompt under construction
     * @param personId stakeholder person id
     * @param personToken the stakeholder's issued mask token
     * @param context request-local masking context
     * @param sourceIds positional source-id provider
     * @return whether either optional background fetch failed
     */
    public boolean appendStakeholderBackground(
            StringBuilder prompt,
            int personId,
            String personToken,
            MaskingContext context,
            SourceIdProvider sourceIds) {
        return appendStakeholderBackground(
                prompt, personId, personToken, context, sourceIds, new LinkedHashSet<>());
    }

    private boolean appendStakeholderBackground(
            StringBuilder prompt,
            int personId,
            String personToken,
            MaskingContext context,
            SourceIdProvider sourceIds,
            Set<Integer> connectionPersonIds) {
        Objects.requireNonNull(sourceIds, "sourceIds");
        if (personId <= 0 || isBlank(personToken)) {
            prompt.append("- Person: none\n  Employment: none\n  Connection: none\n");
            return false;
        }
        String stakeholderSourceId = sourceIds.sourceId("person", personId);
        prompt.append("- Person: ").append(personToken);
        appendSource(prompt, stakeholderSourceId);
        prompt.append('\n');
        FetchResult employment = employmentLines(personId, stakeholderSourceId, context);
        appendSubsection(prompt, "Employment", employment);
        FetchResult connections = connectionLines(
                personId, context, sourceIds, connectionPersonIds);
        appendSubsection(prompt, "Connection", connections);
        return employment.failed() || connections.failed();
    }

    private FetchResult accountHistoryLines(
            int companyId,
            int currentDealId,
            MaskingContext context,
            SourceIdProvider sourceIds) {
        if (companyId <= 0) {
            return FetchResult.available(List.of());
        }
        try {
            List<Deal> deals = safeList(
                dealService.getAccountHistoryDeals(companyId, currentDealId, MAX_ACCOUNT_DEALS));
            List<String> lines = new ArrayList<>();
            for (Deal deal : deals) {
                if (deal == null || deal.getId() <= 0) {
                    continue;
                }
                lines.add(accountHistoryLine(
                        deal, context, sourceIds.sourceId("deal", deal.getId())));
                if (lines.size() == MAX_ACCOUNT_DEALS) {
                    break;
                }
            }
            return FetchResult.available(lines);
        } catch (RuntimeException exception) {
            return FetchResult.failure();
        }
    }

    private String accountHistoryLine(Deal deal, MaskingContext context, String sourceId) {
        StringBuilder line = new StringBuilder("- Outcome: ").append(outcome(deal.getWon()));
        BigDecimal actualValue = deal.getActualValue();
        BigDecimal value = Boolean.TRUE.equals(deal.getWon())
                && actualValue != null
                && actualValue.signum() > 0
                ? actualValue
                : deal.getValue();
        if (value != null && value.signum() > 0) {
            line.append("; Value: ").append(amount(value, deal.getCurrency(), context));
        }
        if (deal.getWon() != null) {
            appendInline(line, "Closed", relativeAge(deal.getClosedAt()));
        } else {
            appendInline(line, "Expected close", relativeAge(deal.getExpectedCloseDate()));
        }
        appendInline(line, "Reason", maskFree(deal.getClosedReason(), context));
        appendSource(line, sourceId);
        return line.append('\n').toString();
    }

    private FetchResult employmentLines(
            int personId, String stakeholderSourceId, MaskingContext context) {
        try {
            List<String> lines = new ArrayList<>();
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
                appendSource(line, stakeholderSourceId);
                lines.add(line.append('\n').toString());
                if (++appended == MAX_EMPLOYMENT) {
                    break;
                }
            }
            return FetchResult.available(lines);
        } catch (RuntimeException exception) {
            return FetchResult.failure();
        }
    }

    private FetchResult connectionLines(
            int personId,
            MaskingContext context,
            SourceIdProvider sourceIds,
            Set<Integer> connectionPersonIds) {
        try {
            List<PersonConnectionDto> connections = new ArrayList<>();
            for (PersonConnectionDto connection : safeList(
                    connectionService.getTopConnections(personId, MAX_CONNECTIONS))) {
                if (connection != null && connection.getPersonId() > 0
                        && !isBlankConnectionName(connection.getPersonName())
                        && connection.getSuspendedAt() == null
                        && connection.getProvisionCeasedAt() == null) {
                    connections.add(connection);
                }
            }
            connections.sort(Comparator.comparingInt(PersonConnectionDto::getStrength).reversed());
            List<String> lines = new ArrayList<>();
            int appended = 0;
            for (PersonConnectionDto connection : connections) {
                StringBuilder line = new StringBuilder("  Connection: ")
                        .append(MaskingEngine.maskField(EntityKind.PERSON, connection.getPersonName(), context));
                appendInline(line, "Type", maskAllowed(connection.getType(), context));
                line.append("; Strength: ").append(connection.getStrength());
                appendInline(line, "Note", maskFree(connection.getNote(), context));
                appendSource(line, sourceIds.sourceId("person", connection.getPersonId()));
                lines.add(line.append('\n').toString());
                connectionPersonIds.add(connection.getPersonId());
                if (++appended == MAX_CONNECTIONS) {
                    break;
                }
            }
            return FetchResult.available(lines);
        } catch (RuntimeException exception) {
            return FetchResult.failure();
        }
    }

    private static void appendSubsection(StringBuilder prompt, String label, FetchResult result) {
        if (result.failed()) {
            prompt.append("  ").append(label).append(": unavailable\n");
        } else if (result.lines().isEmpty()) {
            prompt.append("  ").append(label).append(": none\n");
        } else {
            result.lines().forEach(prompt::append);
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
        if (isUsableMasked(value)) {
            line.append("; ").append(label).append(": ").append(value);
        }
    }

    private static void appendSource(StringBuilder line, String sourceId) {
        if (!isBlank(sourceId)) {
            line.append("; Source: ").append(sourceId);
        }
    }

    private static String amount(BigDecimal value, String currency, MaskingContext context) {
        String amount = value.stripTrailingZeros().toPlainString();
        String safeCurrency = maskAllowedStatic(currency, context);
        return isUsableMasked(safeCurrency) ? amount + " " + safeCurrency : amount;
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

    private static boolean isUsableMasked(String value) {
        return !isBlank(value) && !MaskingEngine.OMITTED_BY_POLICY.equals(value);
    }

    private static boolean isBlankConnectionName(String value) {
        return value == null || value.codePoints().allMatch(AiRelationshipContext::isConnectionWhitespace);
    }

    private static boolean isConnectionWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == 0x0085;
    }

    /**
     * Assigns a positional prompt id for one emitted server-side record.
     */
    @FunctionalInterface
    public interface SourceIdProvider {
        /**
         * Returns the positional id for an emitted record.
         * @param kind stable record kind
         * @param id real record id
         * @return positional prompt id, or blank when citations are not requested
         */
        String sourceId(String kind, int id);
    }

    private record FetchResult(List<String> lines, boolean failed) {
        private FetchResult {
            lines = List.copyOf(lines);
        }

        private static FetchResult available(List<String> lines) {
            return new FetchResult(lines, false);
        }

        private static FetchResult failure() {
            return new FetchResult(List.of(), true);
        }
    }
}
