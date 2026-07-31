package ooo.klae.connex.backend.connectedaccounts.capture;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;

/**
 * Google Calendar and Gmail implementation of the shared bounded capture contract.
 */
@Component
@RequiredArgsConstructor
public class GoogleCaptureAdapter implements ProviderCaptureAdapter {
    private static final int MAX_GMAIL_MESSAGES_PER_PAGE = 3;
    private static final Duration GMAIL_ROUND_OVERLAP =
        Duration.ofMinutes(1);
    private static final String GMAIL_HISTORY_CURSOR_PREFIX =
        "connex:gmail-history:";
    private static final String GMAIL_BACKFILL_CURSOR_PREFIX =
        "connex:gmail-backfill:";
    private static final String CALENDAR_EVENTS =
        "https://www.googleapis.com/calendar/v3/calendars/primary/events";
    private static final String GMAIL_MESSAGES =
        "https://gmail.googleapis.com/gmail/v1/users/me/messages";
    private static final String GMAIL_HISTORY =
        "https://gmail.googleapis.com/gmail/v1/users/me/history";
    private static final String GMAIL_PROFILE =
        "https://gmail.googleapis.com/gmail/v1/users/me/profile";

    private final ProviderCaptureHttpClient httpClient;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Override
    public String provider() {
        return ConnectedAccountProviders.GOOGLE;
    }

    @Override
    public ProviderCapturePage fetch(ProviderCaptureRequest request) {
        return switch (request.stream()) {
            case "calendar" -> fetchCalendar(request);
            case "mail_inbox", "mail_sent" -> fetchMail(request);
            default -> throw new ProviderCaptureException(
                "unsupported_stream", false, false, "Unsupported Google capture stream");
        };
    }

    private ProviderCapturePage fetchCalendar(ProviderCaptureRequest request) {
        JsonNode response;
        try {
            response = get(
                calendarUri(request, request.pageSize()), request);
        } catch (ProviderCaptureException exception) {
            if (!"response_too_large".equals(exception.getCode())
                    || request.pageSize() == 1) {
                throw exception;
            }
            response = get(calendarUri(request, 1), request);
        }
        List<ProviderCaptureItem> items = new ArrayList<>();
        String calendarTimeZone = text(response, "timeZone");
        for (JsonNode event : response.path("items")) {
            ProviderCaptureItem item = ProviderCaptureWindow.enforce(
                calendarItem(event, calendarTimeZone),
                request.from(),
                request.to());
            items.add(request.includeBodies()
                    && !item.tombstone()
                    && request.bodyAccess().allows(item)
                ? withBody(
                    item,
                    calendarDescription(item.sourceId(), request))
                : item);
        }
        return new ProviderCapturePage(
            items,
            text(response, "nextPageToken"),
            text(response, "nextSyncToken"),
            null);
    }

    private static URI calendarUri(
            ProviderCaptureRequest request, int pageSize) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("maxResults", Integer.toString(pageSize));
        parameters.put("singleEvents", "true");
        parameters.put("showDeleted", "true");
        parameters.put(
            "fields",
            "nextPageToken,nextSyncToken,timeZone,items(id,etag,status,summary,visibility,recurringEventId,start,end,organizer,attendees)");
        if (request.pageCursor() != null) {
            parameters.put("pageToken", request.pageCursor());
        }
        if (request.stableCursor() != null) {
            parameters.put("syncToken", request.stableCursor());
        } else {
            parameters.put("timeMin", request.from().toString());
            parameters.put("timeMax", request.to().toString());
            parameters.put("orderBy", "startTime");
        }
        return ProviderCaptureUris.build(CALENDAR_EVENTS, parameters);
    }

    private ProviderCapturePage fetchMail(ProviderCaptureRequest request) {
        if (request.stableCursor() != null) {
            return fetchMailHistory(request);
        }
        GmailBackfillCursor backfill = request.pageCursor() != null
                && request.pageCursor().startsWith(GMAIL_BACKFILL_CURSOR_PREFIX)
            ? decodeBackfillCursor(request.pageCursor())
            : startBackfill(request);
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("labelIds", label(request.stream()));
        parameters.put(
            "maxResults",
            Integer.toString(Math.min(request.pageSize(), MAX_GMAIL_MESSAGES_PER_PAGE)));
        parameters.put("pageToken", backfill.pageToken());
        parameters.put(
            "q",
            "after:" + request.from().getEpochSecond()
                + " before:" + mailUpperBound(request).getEpochSecond());
        JsonNode response = get(
            ProviderCaptureUris.build(GMAIL_MESSAGES, parameters), request);
        List<ProviderCaptureItem> items = new ArrayList<>();
        for (JsonNode reference : response.path("messages")) {
            String messageId = text(reference, "id");
            if (messageId != null) {
                try {
                    JsonNode message = getMessageMetadata(messageId, request);
                    ProviderCaptureItem item = ProviderCaptureWindow.enforce(
                        mailItem(
                            message,
                        request.stream(),
                            false,
                            false),
                        request.from(),
                        mailUpperBound(request));
                    items.add(withAuthorizedMailBody(item, request));
                } catch (ProviderCaptureException exception) {
                    if (exception.isCursorInvalid()) {
                        items.add(tombstone(messageId, null));
                    } else {
                        throw exception;
                    }
                }
            }
        }
        String providerNextPage = text(response, "nextPageToken");
        String nextPage = providerNextPage == null
            ? null
            : encodeBackfillCursor(
                new GmailBackfillCursor(providerNextPage, backfill.historyId()));
        return new ProviderCapturePage(
            items,
            nextPage,
            nextPage == null ? backfill.historyId() : null,
            response.hasNonNull("resultSizeEstimate")
                ? response.get("resultSizeEstimate").asLong()
                : null);
    }

    private GmailBackfillCursor startBackfill(ProviderCaptureRequest request) {
        JsonNode profile = get(URI.create(GMAIL_PROFILE), request);
        String historyId = text(profile, "historyId");
        if (historyId == null) {
            throw new ProviderCaptureException(
                "cursor_missing", true, true,
                "Gmail profile omitted the history anchor");
        }
        return new GmailBackfillCursor(null, historyId);
    }

    private String encodeBackfillCursor(GmailBackfillCursor cursor) {
        byte[] json = objectMapper.writeValueAsBytes(cursor);
        return GMAIL_BACKFILL_CURSOR_PREFIX
            + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private GmailBackfillCursor decodeBackfillCursor(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(
                cursor.substring(GMAIL_BACKFILL_CURSOR_PREFIX.length()));
            GmailBackfillCursor decoded =
                objectMapper.readValue(json, GmailBackfillCursor.class);
            if (decoded.pageToken() == null || decoded.historyId() == null) {
                throw new IllegalArgumentException();
            }
            return decoded;
        } catch (RuntimeException exception) {
            throw new ProviderCaptureException(
                "cursor_rejected", false, false,
                "Gmail backfill continuation is invalid");
        }
    }

    private ProviderCapturePage fetchMailHistory(ProviderCaptureRequest request) {
        if (request.pageCursor() != null
                && request.pageCursor().startsWith(GMAIL_HISTORY_CURSOR_PREFIX)) {
            GmailHistoryCursor cursor = decodeHistoryCursor(request.pageCursor());
            if (!cursor.operations().isEmpty()) {
                return continueMailHistory(request, cursor);
            }
            return fetchMailHistoryPage(
                request,
                cursor.nextPageToken(),
                cursor.deferred(),
                Instant.parse(cursor.lowerBound()),
                Instant.parse(cursor.upperBound()));
        }
        return fetchMailHistoryPage(
            request,
            request.pageCursor(),
            false,
            request.from(),
            mailUpperBound(request));
    }

    private ProviderCapturePage fetchMailHistoryPage(
            ProviderCaptureRequest request,
            String pageToken,
            boolean deferred,
            Instant lowerBound,
            Instant upperBound) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("startHistoryId", request.stableCursor());
        parameters.put("maxResults", "1");
        parameters.put("pageToken", pageToken);
        JsonNode response = get(
            ProviderCaptureUris.build(GMAIL_HISTORY, parameters), request);
        Map<String, GmailHistoryOperation> operations = new LinkedHashMap<>();
        for (JsonNode history : response.path("history")) {
            appendHistoryOperations(
                operations, history.path("messagesAdded"), request, false, false);
            appendHistoryOperations(
                operations, history.path("labelsAdded"), request, false, false);
            appendHistoryOperations(
                operations, history.path("messagesDeleted"), request, true, false);
            appendHistoryOperations(
                operations, history.path("labelsRemoved"), request, true, true);
        }
        return continueMailHistory(
            request,
            new GmailHistoryCursor(
                List.copyOf(operations.values()),
                text(response, "nextPageToken"),
                text(response, "historyId"),
                deferred,
                lowerBound.toString(),
                upperBound.toString()));
    }

    private void appendHistoryOperations(
            Map<String, GmailHistoryOperation> operations,
            JsonNode entries,
            ProviderCaptureRequest request,
            boolean removal,
            boolean requireStreamLabel) {
        for (JsonNode entry : entries) {
            if (requireStreamLabel
                    && !containsText(entry.path("labelIds"), label(request.stream()))) {
                continue;
            }
            JsonNode reference = entry.path("message");
            String messageId = text(reference, "id");
            if (messageId == null) {
                continue;
            }
            operations.put(messageId, new GmailHistoryOperation(messageId, removal));
        }
    }

    private ProviderCapturePage continueMailHistory(
            ProviderCaptureRequest request,
            GmailHistoryCursor cursor) {
        int end = Math.min(MAX_GMAIL_MESSAGES_PER_PAGE, cursor.operations().size());
        int processed = 0;
        boolean deferred = cursor.deferred();
        List<ProviderCaptureItem> items = new ArrayList<>();
        for (GmailHistoryOperation operation : cursor.operations().subList(0, end)) {
            if (operation.removal()) {
                items.add(tombstone(operation.messageId(), null));
                processed++;
                continue;
            }
            JsonNode message;
            try {
                message = getMessageMetadata(operation.messageId(), request);
            } catch (ProviderCaptureException exception) {
                if (exception.isCursorInvalid()) {
                    items.add(tombstone(operation.messageId(), null));
                    processed++;
                    continue;
                }
                throw exception;
            }
            if (hasLabel(message, label(request.stream()))) {
                ProviderCaptureItem metadata = mailItem(
                    message,
                    request.stream(),
                    false,
                    false);
                Instant upperBound = Instant.parse(cursor.upperBound());
                if (!metadata.occurredAt().isBefore(upperBound)) {
                    deferred = true;
                    processed++;
                    continue;
                }
                ProviderCaptureItem item = ProviderCaptureWindow.enforce(
                    metadata,
                    Instant.parse(cursor.lowerBound()),
                    upperBound);
                items.add(withAuthorizedMailBody(item, request));
            } else {
                items.add(tombstone(
                    operation.messageId(), text(message, "historyId")));
            }
            processed++;
        }
        List<GmailHistoryOperation> remaining =
            cursor.operations().subList(processed, cursor.operations().size());
        String nextCursor;
        if (!remaining.isEmpty()) {
            nextCursor = encodeHistoryCursor(new GmailHistoryCursor(
                List.copyOf(remaining),
                cursor.nextPageToken(),
                cursor.historyId(),
                deferred,
                cursor.lowerBound(),
                cursor.upperBound()));
        } else if (cursor.nextPageToken() != null) {
            nextCursor = encodeHistoryCursor(new GmailHistoryCursor(
                List.of(),
                cursor.nextPageToken(),
                cursor.historyId(),
                deferred,
                cursor.lowerBound(),
                cursor.upperBound()));
        } else {
            nextCursor = null;
        }
        String stableCursor = nextCursor == null
            ? deferred ? request.stableCursor() : cursor.historyId()
            : null;
        return new ProviderCapturePage(items, nextCursor, stableCursor, null);
    }

    private String encodeHistoryCursor(GmailHistoryCursor cursor) {
        byte[] json = objectMapper.writeValueAsBytes(cursor);
        return GMAIL_HISTORY_CURSOR_PREFIX
            + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private GmailHistoryCursor decodeHistoryCursor(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(
                cursor.substring(GMAIL_HISTORY_CURSOR_PREFIX.length()));
            GmailHistoryCursor decoded =
                objectMapper.readValue(json, GmailHistoryCursor.class);
            if (decoded.operations() == null
                    || decoded.operations().isEmpty()
                        && decoded.nextPageToken() == null
                    || decoded.historyId() == null
                    || decoded.lowerBound() == null
                    || decoded.upperBound() == null) {
                throw new IllegalArgumentException();
            }
            Instant.parse(decoded.lowerBound());
            Instant.parse(decoded.upperBound());
            return new GmailHistoryCursor(
                List.copyOf(decoded.operations()),
                decoded.nextPageToken(),
                decoded.historyId(),
                decoded.deferred(),
                decoded.lowerBound(),
                decoded.upperBound());
        } catch (RuntimeException exception) {
            throw new ProviderCaptureException(
                "cursor_rejected", false, false,
                "Gmail history continuation is invalid");
        }
    }

    private static ProviderCaptureItem tombstone(
            String messageId, String sourceVersion) {
        return new ProviderCaptureItem(
            messageId, sourceVersion, null, "email", null, null, Instant.EPOCH, null,
            false, true, List.of());
    }

    private JsonNode getMessageMetadata(
            String messageId, ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("format", "metadata");
        URI metadataUri = ProviderCaptureUris.build(
            GMAIL_MESSAGES + "/" + pathSegment(messageId), parameters);
        return get(metadataUri, request);
    }

    private String messageBody(
            String messageId, ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("format", "full");
        URI bodyUri = ProviderCaptureUris.build(
            GMAIL_MESSAGES + "/" + pathSegment(messageId), parameters);
        try {
            return body(get(bodyUri, request).path("payload"));
        } catch (ProviderCaptureException exception) {
            if ("response_too_large".equals(exception.getCode())) {
                return null;
            }
            throw exception;
        }
    }

    private ProviderCaptureItem withAuthorizedMailBody(
            ProviderCaptureItem item, ProviderCaptureRequest request) {
        if (!request.includeBodies()
                || item.tombstone()
                || !request.bodyAccess().allows(item)) {
            return item;
        }
        return withBody(item, messageBody(item.sourceId(), request));
    }

    private static Instant mailUpperBound(ProviderCaptureRequest request) {
        return request.to().plus(GMAIL_ROUND_OVERLAP);
    }

    private String calendarDescription(
            String eventId, ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("fields", "description");
        try {
            JsonNode event = get(
                ProviderCaptureUris.build(
                    CALENDAR_EVENTS + "/" + pathSegment(eventId), parameters),
                request);
            return text(event, "description");
        } catch (ProviderCaptureException exception) {
            if ("response_too_large".equals(exception.getCode())
                    || exception.isCursorInvalid()) {
                return null;
            }
            throw exception;
        }
    }

    private JsonNode get(URI uri, ProviderCaptureRequest request) {
        request.lease().renew();
        return httpClient.get(uri, request.accessToken());
    }

    private static ProviderCaptureItem withBody(
            ProviderCaptureItem item, String body) {
        return new ProviderCaptureItem(
            item.sourceId(),
            item.sourceVersion(),
            item.conversationId(),
            item.interactionType(),
            item.subject(),
            body,
            item.occurredAt(),
            item.endedAt(),
            item.privateItem(),
            item.tombstone(),
            item.participants());
    }

    private static ProviderCaptureItem calendarItem(
            JsonNode event,
            String calendarTimeZone) {
        boolean tombstone = "cancelled".equals(text(event, "status"));
        JsonNode start = event.path("start");
        JsonNode end = event.path("end");
        List<ProviderCaptureParticipant> participants = new ArrayList<>();
        appendCalendarParticipant(participants, "organizer", event.path("organizer"));
        for (JsonNode attendee : event.path("attendees")) {
            appendCalendarParticipant(participants, "attendee", attendee);
        }
        return new ProviderCaptureItem(
            requiredText(event, "id"),
            text(event, "etag"),
            text(event, "recurringEventId"),
            "meeting",
            text(event, "summary"),
            null,
            tombstone ? Instant.EPOCH : calendarTime(start, calendarTimeZone),
            tombstone ? null : calendarTime(end, calendarTimeZone),
            isGooglePrivateVisibility(text(event, "visibility")),
            tombstone,
            participants);
    }

    private static ProviderCaptureItem mailItem(
            JsonNode message, String stream, boolean includeBodies, boolean tombstone) {
        JsonNode payload = message.path("payload");
        Map<String, String> headers = headers(payload.path("headers"));
        List<ProviderCaptureParticipant> participants = new ArrayList<>();
        appendAddresses(participants, "from", headers.get("from"));
        appendAddresses(participants, "to", headers.get("to"));
        appendAddresses(participants, "cc", headers.get("cc"));
        long internalDate = parseLong(text(message, "internalDate"));
        return new ProviderCaptureItem(
            requiredText(message, "id"),
            text(message, "historyId"),
            text(message, "threadId"),
            "email",
            headers.get("subject"),
            includeBodies ? body(payload) : null,
            internalDate > 0 ? Instant.ofEpochMilli(internalDate) : Instant.EPOCH,
            null,
            isSensitiveMail(headers.get("sensitivity")),
            tombstone,
            participants);
    }

    private static boolean isGooglePrivateVisibility(String visibility) {
        return "private".equalsIgnoreCase(visibility)
            || "confidential".equalsIgnoreCase(visibility);
    }

    private static boolean isSensitiveMail(String sensitivity) {
        return sensitivity != null
            && !"normal".equalsIgnoreCase(sensitivity);
    }

    private static void appendCalendarParticipant(
            List<ProviderCaptureParticipant> participants, String role, JsonNode value) {
        String email = text(value, "email");
        if (email != null) {
            participants.add(new ProviderCaptureParticipant(role, text(value, "displayName"), email));
        }
    }

    private static void appendAddresses(
            List<ProviderCaptureParticipant> participants, String role, String value) {
        if (value == null) {
            return;
        }
        try {
            for (InternetAddress address : InternetAddress.parseHeader(value, false)) {
                if (address.getAddress() != null && !address.getAddress().isBlank()) {
                    participants.add(new ProviderCaptureParticipant(
                        role, address.getPersonal(), address.getAddress()));
                }
            }
        } catch (AddressException exception) {
            participants.add(new ProviderCaptureParticipant(role, null, value));
        }
    }

    private static Map<String, String> headers(JsonNode values) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (JsonNode header : values) {
            String name = text(header, "name");
            String value = text(header, "value");
            if (name != null && value != null) {
                headers.put(name.toLowerCase(Locale.ROOT), value);
            }
        }
        return headers;
    }

    private static String body(JsonNode part) {
        String filename = text(part, "filename");
        String disposition = headers(part.path("headers")).get("content-disposition");
        if (filename != null && !filename.isBlank()
                || disposition != null
                    && disposition.toLowerCase(Locale.ROOT).contains("attachment")) {
            return null;
        }
        String mimeType = text(part, "mimeType");
        String data = text(part.path("body"), "data");
        if (data != null && (mimeType == null || mimeType.startsWith("text/plain"))) {
            try {
                return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        for (JsonNode child : part.path("parts")) {
            String body = body(child);
            if (body != null) {
                return body;
            }
        }
        return null;
    }

    private static Instant calendarTime(JsonNode value, String calendarTimeZone) {
        String dateTime = text(value, "dateTime");
        if (dateTime != null) {
            return Instant.parse(dateTime);
        }
        String date = text(value, "date");
        if (date == null) {
            return Instant.EPOCH;
        }
        String zone = text(value, "timeZone");
        if (zone == null) {
            zone = calendarTimeZone;
        }
        return LocalDate.parse(date)
            .atStartOfDay(zone == null
                ? ZoneOffset.UTC
                : java.time.ZoneId.of(zone))
            .toInstant();
    }

    private static boolean hasLabel(JsonNode message, String label) {
        return containsText(message.path("labelIds"), label);
    }

    private static boolean containsText(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asString())) {
                return true;
            }
        }
        return false;
    }

    private static String label(String stream) {
        return "mail_inbox".equals(stream) ? "INBOX" : "SENT";
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new ProviderCaptureException(
                "provider_malformed", false, false, "Provider item is missing its stable id");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private record GmailHistoryOperation(String messageId, boolean removal) {
    }

    private record GmailHistoryCursor(
        List<GmailHistoryOperation> operations,
        String nextPageToken,
        String historyId,
        boolean deferred,
        String lowerBound,
        String upperBound
    ) {
    }

    private record GmailBackfillCursor(String pageToken, String historyId) {
    }
}
