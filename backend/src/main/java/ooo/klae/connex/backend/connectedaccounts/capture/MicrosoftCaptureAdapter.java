package ooo.klae.connex.backend.connectedaccounts.capture;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;

/**
 * Microsoft Graph calendar and mail delta implementation of the shared capture contract.
 */
@Component
@RequiredArgsConstructor
public class MicrosoftCaptureAdapter implements ProviderCaptureAdapter {
    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String GRAPH_HOST = "graph.microsoft.com";
    private static final String GRAPH_PATH = "/v1.0/";
    static final String CALENDAR_FULL_SCAN_CURSOR = "microsoft-calendar-full-scan:v1";
    private static final String MAIL_BOOTSTRAP_CURSOR = "microsoft-mail-bootstrap:v1:";
    private static final String MAIL_DELTA_CURSOR = "microsoft-mail-delta:v1:";

    private final ProviderCaptureHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String provider() {
        return ConnectedAccountProviders.MICROSOFT;
    }

    @Override
    public ProviderCapturePage fetch(ProviderCaptureRequest request) {
        return "calendar".equals(request.stream())
            ? fetchCalendar(request)
            : fetchMail(request);
    }

    private ProviderCapturePage fetchCalendar(ProviderCaptureRequest request) {
        if (request.stableCursor() != null
                && !CALENDAR_FULL_SCAN_CURSOR.equals(request.stableCursor())) {
            throw new ProviderCaptureException(
                "cursor_invalid", true, true,
                "Microsoft calendar scan cursor is invalid");
        }
        URI uri = request.pageCursor() == null
            ? initialCalendarUri(request)
            : opaqueCalendarList(request.pageCursor());
        JsonNode response = getPage(uri, request);
        List<ProviderCaptureItem> items = new ArrayList<>();
        for (JsonNode value : response.path("value")) {
            ProviderCaptureItem item = ProviderCaptureWindow.enforce(
                calendarItem(value, false),
                request.from(),
                request.to());
            items.add(request.includeBodies()
                    && !item.tombstone()
                    && request.bodyAccess().allows(item)
                ? withBody(item, eventBody(item.sourceId(), request))
                : item);
        }
        String next = validatedCalendarListCursor(response, "@odata.nextLink");
        return new ProviderCapturePage(
            items,
            next,
            next == null ? CALENDAR_FULL_SCAN_CURSOR : null,
            null);
    }

    private ProviderCapturePage fetchMail(ProviderCaptureRequest request) {
        if (request.pageCursor() != null
                && request.pageCursor().startsWith(MAIL_BOOTSTRAP_CURSOR)) {
            MailBootstrapCursor cursor =
                decodeBootstrap(request.pageCursor(), request.stream());
            return "anchor".equals(cursor.phase())
                ? fetchMailAnchor(request, cursor)
                : fetchMailBackfill(request, cursor);
        }
        if (request.stableCursor() == null && request.pageCursor() == null) {
            return fetchMailAnchor(
                request,
                new MailBootstrapCursor(
                    request.stream(),
                    "anchor",
                    null,
                    null,
                    mailFolderId(request),
                    null));
        }
        String cursor = request.pageCursor() == null
            ? request.stableCursor()
            : request.pageCursor();
        MailDeltaCursor delta = decodeDelta(cursor, request.stream());
        return fetchMailDelta(request, delta);
    }

    private ProviderCapturePage fetchMailAnchor(
            ProviderCaptureRequest request, MailBootstrapCursor cursor) {
        URI uri = cursor.providerCursor() == null
            ? initialMailAnchorUri(request, cursor.folderId())
            : opaqueProviderDelta(cursor.providerCursor(), cursor.folderId());
        JsonNode response = getPage(uri, request);
        String next = validatedProviderDeltaCursor(
            response, "@odata.nextLink", cursor.folderId());
        if (next != null) {
            return new ProviderCapturePage(
                List.of(),
                encodeBootstrap(new MailBootstrapCursor(
                    request.stream(),
                    "anchor",
                    next,
                    null,
                    cursor.folderId(),
                    null)),
                null,
                null);
        }
        String stable = validatedProviderDeltaCursor(
            response, "@odata.deltaLink", cursor.folderId());
        if (stable == null) {
            throw new ProviderCaptureException(
                "cursor_missing", true, true,
                "Microsoft mail delta anchor omitted its continuation cursor");
        }
        return new ProviderCapturePage(
            List.of(),
            encodeBootstrap(new MailBootstrapCursor(
                request.stream(),
                "backfill",
                null,
                stable,
                cursor.folderId(),
                Instant.now().toString())),
            null,
            null);
    }

    private ProviderCapturePage fetchMailBackfill(
            ProviderCaptureRequest request, MailBootstrapCursor cursor) {
        URI uri = cursor.providerCursor() == null
            ? initialMailBackfillUri(
                request, cursor.folderId(), Instant.parse(cursor.backfillTo()))
            : opaqueMailList(cursor.providerCursor(), cursor.folderId());
        JsonNode response = getPage(uri, request);
        List<ProviderCaptureItem> items = mailItems(
            response, request, false, Instant.parse(cursor.backfillTo()));
        String next = validatedMailListCursor(
            response, "@odata.nextLink", cursor.folderId());
        return new ProviderCapturePage(
            items,
            next == null
                ? null
                : encodeBootstrap(
                    new MailBootstrapCursor(
                        request.stream(),
                        "backfill",
                        next,
                        cursor.stableCursor(),
                        cursor.folderId(),
                        cursor.backfillTo())),
            next == null
                ? encodeDelta(
                    request.stream(),
                    cursor.stableCursor(),
                    cursor.folderId())
                : null,
            null);
    }

    private ProviderCapturePage fetchMailDelta(
            ProviderCaptureRequest request, MailDeltaCursor cursor) {
        JsonNode response = getPage(
            opaqueProviderDelta(cursor.providerCursor(), cursor.folderId()),
            request);
        return new ProviderCapturePage(
            mailItems(response, request, true, request.to()),
            validatedDeltaCursor(
                response,
                "@odata.nextLink",
                request.stream(),
                cursor.folderId()),
            validatedDeltaCursor(
                response,
                "@odata.deltaLink",
                request.stream(),
                cursor.folderId()),
            null);
    }

    private List<ProviderCaptureItem> mailItems(
            JsonNode response,
            ProviderCaptureRequest request,
            boolean hydrateDeltaItems,
            Instant to) {
        List<ProviderCaptureItem> items = new ArrayList<>();
        for (JsonNode value : response.path("value")) {
            JsonNode message = value;
            if (hydrateDeltaItems && !value.has("@removed")) {
                try {
                    message = mailMetadata(requiredText(value, "id"), request);
                } catch (ProviderCaptureException exception) {
                    if (exception.isCursorInvalid()) {
                        items.add(tombstone(requiredText(value, "id")));
                        continue;
                    }
                    throw exception;
                }
            }
            ProviderCaptureItem item = ProviderCaptureWindow.enforce(
                mailItem(message, false),
                request.from(),
                to);
            items.add(request.includeBodies()
                    && !item.tombstone()
                    && request.bodyAccess().allows(item)
                ? withBody(item, messageBody(item.sourceId(), request))
                : item);
        }
        return items;
    }

    private static URI initialCalendarUri(ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("startDateTime", request.from().toString());
        parameters.put("endDateTime", request.to().toString());
        parameters.put("$top", Integer.toString(request.pageSize()));
        parameters.put(
            "$select",
            "id,changeKey,seriesMasterId,subject,sensitivity,isCancelled,start,end,organizer,attendees");
        return ProviderCaptureUris.build(GRAPH_BASE + "/me/calendarView", parameters);
    }

    private static URI initialMailAnchorUri(
            ProviderCaptureRequest request, String folderId) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("$top", Integer.toString(request.pageSize()));
        parameters.put("$select", "id");
        parameters.put("$filter", "receivedDateTime ge " + request.from());
        return ProviderCaptureUris.build(
            GRAPH_BASE + "/me/mailFolders/" + pathSegment(folderId) + "/messages/delta",
            parameters);
    }

    private static URI initialMailBackfillUri(
            ProviderCaptureRequest request,
            String folderId,
            Instant to) {
        String timeField = "mail_sent".equals(request.stream())
            ? "sentDateTime"
            : "receivedDateTime";
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("$top", Integer.toString(request.pageSize()));
        parameters.put(
            "$select",
            "id,changeKey,conversationId,subject,sensitivity,receivedDateTime,sentDateTime,from,toRecipients,ccRecipients");
        parameters.put(
            "$filter",
            timeField + " ge " + request.from() + " and "
                + timeField + " lt " + to);
        parameters.put("$orderby", timeField + " asc");
        return ProviderCaptureUris.build(
            GRAPH_BASE + "/me/mailFolders/" + pathSegment(folderId) + "/messages",
            parameters);
    }

    private String mailFolderId(ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("$select", "id");
        JsonNode response = getMicrosoft(
            ProviderCaptureUris.build(
                GRAPH_BASE + "/me/mailFolders/" + folder(request.stream()),
                parameters),
            request,
            1);
        return requiredText(response, "id");
    }

    private JsonNode getPage(URI uri, ProviderCaptureRequest request) {
        try {
            return getMicrosoft(uri, request, request.pageSize());
        } catch (ProviderCaptureException exception) {
            if (!"response_too_large".equals(exception.getCode())
                    || request.pageSize() == 1) {
                throw exception;
            }
            return getMicrosoft(uri, request, 1);
        }
    }

    private JsonNode mailMetadata(String messageId, ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put(
            "$select",
            "id,changeKey,conversationId,subject,sensitivity,receivedDateTime,sentDateTime,from,toRecipients,ccRecipients");
        URI uri = ProviderCaptureUris.build(
            GRAPH_BASE + "/me/messages/" + pathSegment(messageId), parameters);
        return getMicrosoft(uri, request, 1);
    }

    private String messageBody(String messageId, ProviderCaptureRequest request) {
        return optionalBody(
            GRAPH_BASE + "/me/messages/" + pathSegment(messageId), request);
    }

    private String eventBody(String eventId, ProviderCaptureRequest request) {
        return optionalBody(
            GRAPH_BASE + "/me/events/" + pathSegment(eventId), request);
    }

    private String optionalBody(String base, ProviderCaptureRequest request) {
        Map<String, String> parameters = ProviderCaptureUris.parameters();
        parameters.put("$select", "body");
        try {
            JsonNode response = getMicrosoft(
                ProviderCaptureUris.build(base, parameters),
                request,
                1);
            return content(response.path("body"));
        } catch (ProviderCaptureException exception) {
            if ("response_too_large".equals(exception.getCode())
                    || exception.isCursorInvalid()) {
                return null;
            }
            throw exception;
        }
    }

    private JsonNode getMicrosoft(
            URI uri, ProviderCaptureRequest request, int pageSize) {
        request.lease().renew();
        return httpClient.getMicrosoft(uri, request.accessToken(), pageSize);
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

    private static ProviderCaptureItem tombstone(String sourceId) {
        return new ProviderCaptureItem(
            sourceId,
            null,
            null,
            "email",
            null,
            null,
            Instant.EPOCH,
            null,
            false,
            true,
            List.of());
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static ProviderCaptureItem calendarItem(JsonNode event, boolean includeBodies) {
        boolean tombstone = event.has("@removed")
            || event.path("isCancelled").asBoolean(false);
        List<ProviderCaptureParticipant> participants = new ArrayList<>();
        appendRecipient(participants, "organizer", event.path("organizer"));
        for (JsonNode attendee : event.path("attendees")) {
            appendRecipient(participants, "attendee", attendee);
        }
        return new ProviderCaptureItem(
            requiredText(event, "id"),
            text(event, "changeKey"),
            text(event, "seriesMasterId"),
            "meeting",
            text(event, "subject"),
            includeBodies ? content(event.path("body")) : null,
            tombstone ? Instant.EPOCH : graphTime(event.path("start")),
            tombstone ? null : graphTime(event.path("end")),
            privateSensitivity(event, tombstone),
            tombstone,
            participants);
    }

    private static ProviderCaptureItem mailItem(JsonNode message, boolean includeBodies) {
        boolean tombstone = message.has("@removed");
        List<ProviderCaptureParticipant> participants = new ArrayList<>();
        appendRecipient(participants, "from", message.path("from"));
        appendRecipients(participants, "to", message.path("toRecipients"));
        appendRecipients(participants, "cc", message.path("ccRecipients"));
        String occurred = text(message, "sentDateTime");
        if (occurred == null) {
            occurred = text(message, "receivedDateTime");
        }
        return new ProviderCaptureItem(
            requiredText(message, "id"),
            text(message, "changeKey"),
            text(message, "conversationId"),
            "email",
            text(message, "subject"),
            includeBodies ? content(message.path("body")) : null,
            tombstone || occurred == null ? Instant.EPOCH : Instant.parse(occurred),
            null,
            privateSensitivity(message, tombstone),
            tombstone,
            participants);
    }

    private static boolean privateSensitivity(JsonNode item, boolean tombstone) {
        if (tombstone) {
            return false;
        }
        String sensitivity = text(item, "sensitivity");
        return sensitivity == null || !"normal".equalsIgnoreCase(sensitivity);
    }

    private static void appendRecipients(
            List<ProviderCaptureParticipant> participants, String role, JsonNode recipients) {
        for (JsonNode recipient : recipients) {
            appendRecipient(participants, role, recipient);
        }
    }

    private static void appendRecipient(
            List<ProviderCaptureParticipant> participants, String role, JsonNode recipient) {
        JsonNode address = recipient.path("emailAddress");
        String email = text(address, "address");
        if (email != null) {
            participants.add(new ProviderCaptureParticipant(role, text(address, "name"), email));
        }
    }

    private static String content(JsonNode body) {
        return "text".equalsIgnoreCase(text(body, "contentType"))
            ? text(body, "content")
            : null;
    }

    private static Instant graphTime(JsonNode value) {
        String dateTime = text(value, "dateTime");
        if (dateTime == null) {
            return Instant.EPOCH;
        }
        String timeZone = text(value, "timeZone");
        if ("UTC".equalsIgnoreCase(timeZone) || dateTime.endsWith("Z")) {
            return dateTime.endsWith("Z")
                ? Instant.parse(dateTime)
                : LocalDateTime.parse(dateTime).toInstant(ZoneOffset.UTC);
        }
        try {
            return LocalDateTime.parse(dateTime)
                .atZone(java.time.ZoneId.of(timeZone))
                .toInstant();
        } catch (RuntimeException exception) {
            return LocalDateTime.parse(dateTime).toInstant(ZoneOffset.UTC);
        }
    }

    private static URI opaqueProviderDelta(String cursor, String folderId) {
        URI uri = ProviderCaptureUris.requireOpaqueCursor(
            cursor, "https", GRAPH_HOST, GRAPH_PATH);
        if (!folderId.equals(mailFolderSelector(uri, "/messages/delta"))) {
            throw new ProviderCaptureException(
                "cursor_rejected", false, false,
                "Microsoft cursor does not match the active capture stream");
        }
        return uri;
    }

    private static URI opaqueMailList(String cursor, String folderId) {
        URI uri = ProviderCaptureUris.requireOpaqueCursor(
            cursor, "https", GRAPH_HOST, GRAPH_PATH);
        if (!folderId.equals(mailFolderSelector(uri, "/messages"))) {
            throw new ProviderCaptureException(
                "cursor_rejected", false, false,
                "Microsoft cursor does not match a mail-list continuation");
        }
        return uri;
    }

    private static URI opaqueCalendarList(String cursor) {
        URI uri = ProviderCaptureUris.requireOpaqueCursor(
            cursor, "https", GRAPH_HOST, GRAPH_PATH);
        if (!uri.getPath().equalsIgnoreCase(GRAPH_PATH + "me/calendarView")) {
            throw new ProviderCaptureException(
                "cursor_rejected", false, false,
                "Microsoft cursor does not match the calendar-list continuation");
        }
        return uri;
    }

    private static String mailFolderSelector(URI uri, String suffix) {
        String path = uri.getPath();
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        String slashPrefix = GRAPH_PATH + "me/mailfolders/";
        String functionPrefix = GRAPH_PATH + "me/mailfolders('";
        String slashSuffix = suffix;
        String functionSuffix = "')" + suffix;
        if (normalized.startsWith(slashPrefix) && normalized.endsWith(slashSuffix)) {
            return requiredFolderSelector(
                path.substring(
                    slashPrefix.length(),
                    path.length() - slashSuffix.length()));
        }
        if (normalized.startsWith(functionPrefix) && normalized.endsWith(functionSuffix)) {
            return requiredFolderSelector(
                path.substring(
                    functionPrefix.length(), path.length() - functionSuffix.length()));
        }
        throw new ProviderCaptureException(
            "cursor_rejected", false, false,
            "Microsoft cursor does not match a mail collection");
    }

    private static String requiredFolderSelector(String selector) {
        if (selector.isBlank()) {
            throw new ProviderCaptureException(
                "cursor_rejected", false, false,
                "Microsoft cursor omitted its mail folder");
        }
        return selector;
    }

    private String validatedDeltaCursor(
            JsonNode response,
            String field,
            String stream,
            String folderId) {
        String cursor = text(response, field);
        return cursor == null ? null : encodeDelta(stream, cursor, folderId);
    }

    private static String validatedProviderDeltaCursor(
            JsonNode response, String field, String folderId) {
        String cursor = text(response, field);
        return cursor == null
            ? null
            : opaqueProviderDelta(cursor, folderId).toString();
    }

    private static String validatedMailListCursor(
            JsonNode response, String field, String folderId) {
        String cursor = text(response, field);
        return cursor == null
            ? null
            : opaqueMailList(cursor, folderId).toString();
    }

    private static String validatedCalendarListCursor(JsonNode response, String field) {
        String cursor = text(response, field);
        return cursor == null ? null : opaqueCalendarList(cursor).toString();
    }

    private String encodeBootstrap(MailBootstrapCursor cursor) {
        return MAIL_BOOTSTRAP_CURSOR
            + Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(cursor));
    }

    private MailBootstrapCursor decodeBootstrap(String value, String stream) {
        try {
            MailBootstrapCursor cursor = objectMapper.readValue(
                Base64.getUrlDecoder().decode(
                    value.substring(MAIL_BOOTSTRAP_CURSOR.length())),
                MailBootstrapCursor.class);
            boolean anchor = "anchor".equals(cursor.phase());
            boolean backfill = "backfill".equals(cursor.phase());
            if (!stream.equals(cursor.stream())
                    || cursor.folderId() == null
                    || cursor.folderId().isBlank()
                    || anchor && cursor.providerCursor() == null
                    || backfill
                        && (cursor.stableCursor() == null
                            || cursor.stableCursor().isBlank()
                            || cursor.backfillTo() == null)
                    || !anchor && !backfill) {
                throw new IllegalArgumentException();
            }
            if (cursor.backfillTo() != null) {
                Instant.parse(cursor.backfillTo());
            }
            if (cursor.providerCursor() != null) {
                if (anchor) {
                    opaqueProviderDelta(
                        cursor.providerCursor(), cursor.folderId());
                } else {
                    opaqueMailList(
                        cursor.providerCursor(), cursor.folderId());
                }
            }
            if (cursor.stableCursor() != null) {
                opaqueProviderDelta(
                    cursor.stableCursor(), cursor.folderId());
            }
            return cursor;
        } catch (RuntimeException exception) {
            throw new ProviderCaptureException(
                "cursor_invalid", true, true,
                "Microsoft mail bootstrap continuation is invalid");
        }
    }

    private String encodeDelta(
            String stream, String providerCursor, String folderId) {
        opaqueProviderDelta(providerCursor, folderId);
        return MAIL_DELTA_CURSOR
            + Base64.getUrlEncoder().withoutPadding().encodeToString(
                objectMapper.writeValueAsBytes(
                    new MailDeltaCursor(stream, providerCursor, folderId)));
    }

    private MailDeltaCursor decodeDelta(String value, String stream) {
        try {
            if (value == null || !value.startsWith(MAIL_DELTA_CURSOR)) {
                throw new IllegalArgumentException();
            }
            MailDeltaCursor cursor = objectMapper.readValue(
                Base64.getUrlDecoder().decode(
                    value.substring(MAIL_DELTA_CURSOR.length())),
                MailDeltaCursor.class);
            if (!stream.equals(cursor.stream())
                    || cursor.providerCursor() == null
                    || cursor.folderId() == null
                    || cursor.folderId().isBlank()) {
                throw new IllegalArgumentException();
            }
            opaqueProviderDelta(
                cursor.providerCursor(), cursor.folderId());
            return cursor;
        } catch (RuntimeException exception) {
            throw new ProviderCaptureException(
                "cursor_invalid", true, true,
                "Microsoft mail delta continuation is invalid");
        }
    }

    private static String folder(String stream) {
        return switch (stream) {
            case "mail_inbox" -> "inbox";
            case "mail_sent" -> "sentitems";
            default -> throw new ProviderCaptureException(
                "unsupported_stream", false, false, "Unsupported Microsoft capture stream");
        };
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

    private record MailBootstrapCursor(
        String stream,
        String phase,
        String providerCursor,
        String stableCursor,
        String folderId,
        String backfillTo
    ) {
    }

    private record MailDeltaCursor(
        String stream,
        String providerCursor,
        String folderId
    ) {
    }
}
