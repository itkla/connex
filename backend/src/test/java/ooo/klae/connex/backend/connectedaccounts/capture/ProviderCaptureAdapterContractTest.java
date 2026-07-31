package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProviderCaptureAdapterContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void googleCalendarMapsPrivateRecurrenceAndStableCursors() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("""
                {
                  "items": [{
                    "id": "event-1",
                    "etag": "version-2",
                    "status": "confirmed",
                    "summary": "Private planning",
                    "visibility": "private",
                    "recurringEventId": "series-1",
                    "start": {"dateTime": "2026-07-30T09:00:00Z"},
                    "end": {"dateTime": "2026-07-30T09:30:00Z"},
                    "organizer": {"email": "owner@example.test"},
                    "attendees": [{
                      "email": "customer@example.net",
                      "displayName": "Customer"
                    }]
                  }],
                  "nextPageToken": "page-2",
                  "nextSyncToken": "sync-9"
                }
                """));
        GoogleCaptureAdapter adapter = new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page = adapter.fetch(request("calendar", null, null, true));

        assertEquals("page-2", page.nextPageCursor());
        assertEquals("sync-9", page.stableCursor());
        ProviderCaptureItem item = page.items().getFirst();
        assertEquals("event-1", item.sourceId());
        assertEquals("version-2", item.sourceVersion());
        assertNull(item.body());
        assertTrue(item.privateItem());
        assertFalse(item.tombstone());
        assertEquals(2, item.participants().size());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).get(uri.capture(), eq("token"));
        assertFalse(uri.getValue().getRawQuery().contains("description"));
    }

    @Test
    void googleCalendarMetadataModeDoesNotRequestDescriptions() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token")))
            .thenReturn(objectMapper.readTree("{\"items\":[]}"));
        GoogleCaptureAdapter adapter = new GoogleCaptureAdapter(httpClient, objectMapper);

        adapter.fetch(request("calendar", null, null, false));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).get(uri.capture(), eq("token"));
        assertFalse(uri.getValue().getRawQuery().contains("description"));
    }

    @Test
    void googleCalendarRetriesAnOversizedPageAndOmitsAnOversizedBody() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token")))
            .thenThrow(new ProviderCaptureException(
                "response_too_large",
                false,
                false,
                "Provider response exceeded the bound"))
            .thenReturn(objectMapper.readTree("""
                {
                  "items":[{
                    "id":"event-large",
                    "etag":"v1",
                    "status":"confirmed",
                    "summary":"Large event",
                    "visibility":"default",
                    "start":{"dateTime":"2026-07-30T09:00:00Z"},
                    "end":{"dateTime":"2026-07-30T10:00:00Z"},
                    "organizer":{"email":"owner@example.test"}
                  }],
                  "nextPageToken":"page-2"
                }
                """))
            .thenThrow(new ProviderCaptureException(
                "response_too_large",
                false,
                false,
                "Provider response exceeded the bound"));
        ProviderCaptureLease lease = mock(ProviderCaptureLease.class);
        GoogleCaptureAdapter adapter = new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page = adapter.fetch(
            request("calendar", null, null, true, lease));

        assertEquals("Large event", page.items().getFirst().subject());
        assertNull(page.items().getFirst().body());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient, times(3)).get(uri.capture(), eq("token"));
        assertTrue(uri.getAllValues().get(0).getRawQuery().contains("maxResults=100"));
        assertTrue(uri.getAllValues().get(1).getRawQuery().contains("maxResults=1"));
        assertTrue(uri.getAllValues().get(2).getRawQuery().contains("description"));
        verify(lease, times(3)).renew();
    }

    @Test
    void googleMailDefaultsToMetadataAndEstablishesHistoryCursor() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("{\"historyId\":\"45\"}"),
            objectMapper.readTree("""
                {"messages":[{"id":"mail-1"}],"resultSizeEstimate":1}
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-1",
                  "historyId":"43",
                  "internalDate":"1785416400000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Hello"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ],
                    "body":{"data":"c2VjcmV0"}
                  }
                }
                """));
        GoogleCaptureAdapter adapter = new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request("mail_inbox", null, null, false));

        assertEquals("45", page.stableCursor());
        assertEquals(1, page.estimatedItems());
        ProviderCaptureItem item = page.items().getFirst();
        assertEquals("Hello", item.subject());
        assertNull(item.body());
        assertEquals(2, item.participants().size());
    }

    @Test
    void googleMailBoundsTheListAndSkipsBodiesOutsideTheWindow() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("{\"historyId\":\"45\"}"),
            objectMapper.readTree("""
                {"messages":[{"id":"mail-new"}],"resultSizeEstimate":1}
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-new",
                  "historyId":"44",
                  "internalDate":"1785456120000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"After boundary"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """));
        GoogleCaptureAdapter adapter =
            new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request("mail_inbox", null, null, true));

        assertTrue(page.items().getFirst().tombstone());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient, times(3)).get(uri.capture(), eq("token"));
        assertTrue(uri.getAllValues().get(1).getRawQuery().contains(
            "before%3A1785456060"));
        assertTrue(uri.getAllValues().get(2).getRawQuery().contains(
            "format=metadata"));
    }

    @Test
    void googleMailOverlapsTheHistoryAnchorWithoutSkippingNewMail() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("{\"historyId\":\"45\"}"),
            objectMapper.readTree("""
                {"messages":[{"id":"mail-overlap"}],"resultSizeEstimate":1}
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-overlap",
                  "historyId":"46",
                  "internalDate":"1785456030000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Anchor overlap"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """),
            objectMapper.readTree("""
                {
                  "payload":{
                    "mimeType":"text/plain",
                    "body":{"data":"c2VjcmV0"}
                  }
                }
                """));
        GoogleCaptureAdapter adapter =
            new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request("mail_inbox", null, null, true));

        assertFalse(page.items().getFirst().tombstone());
        assertEquals("secret", page.items().getFirst().body());
        assertEquals("45", page.stableCursor());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient, times(4)).get(uri.capture(), eq("token"));
        assertTrue(uri.getAllValues().get(1).getRawQuery().contains(
            "before%3A1785456060"));
    }

    @Test
    void googleHistoryDefersMailBeyondTheRoundWithoutAdvancing() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("""
                {
                  "historyId":"52",
                  "history":[{
                    "messagesAdded":[{"message":{"id":"mail-future"}}]
                  }]
                }
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-future",
                  "historyId":"52",
                  "internalDate":"1785456120000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Deferred"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """));
        GoogleCaptureAdapter adapter =
            new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request("mail_inbox", "50", null, false));

        assertTrue(page.items().isEmpty());
        assertNull(page.nextPageCursor());
        assertEquals("50", page.stableCursor());
        verify(httpClient, times(2)).get(any(URI.class), eq("token"));
    }

    @Test
    void googleHistoryDeferredMailDoesNotBlockLaterHistoryPages() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("""
                {
                  "historyId":"52",
                  "nextPageToken":"page-2",
                  "history":[{
                    "messagesAdded":[{"message":{"id":"mail-future"}}]
                  }]
                }
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-future",
                  "historyId":"52",
                  "internalDate":"1785456120000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Deferred"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """),
            objectMapper.readTree("""
                {
                  "historyId":"53",
                  "history":[{
                    "messagesAdded":[
                      {"message":{"id":"mail-later-future"}},
                      {"message":{"id":"mail-current"}}
                    ]
                  }]
                }
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-later-future",
                  "historyId":"53",
                  "internalDate":"1785456120000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Still deferred"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-current",
                  "historyId":"53",
                  "internalDate":"1785416400000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Current"},
                      {"name":"From","value":"Sender <sender@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """));
        GoogleCaptureAdapter adapter =
            new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage deferred =
            adapter.fetch(request("mail_inbox", "50", null, false));
        ProviderCapturePage continued = adapter.fetch(request(
            "mail_inbox",
            "50",
            deferred.nextPageCursor(),
            false,
            Instant.parse("2026-07-31T00:03:00Z")));

        assertTrue(deferred.items().isEmpty());
        assertTrue(deferred.nextPageCursor().startsWith(
            "connex:gmail-history:"));
        assertEquals(1, continued.items().size());
        assertEquals("Current", continued.items().getFirst().subject());
        assertNull(continued.nextPageCursor());
        assertEquals("50", continued.stableCursor());
        verify(httpClient, times(5)).get(any(URI.class), eq("token"));
    }

    @Test
    void googleHistoryCapturesDeferredMailWhenTheRoundCatchesUp() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        JsonNode history = objectMapper.readTree("""
            {
              "historyId":"52",
              "history":[{
                "messagesAdded":[{"message":{"id":"mail-future"}}]
              }]
            }
            """);
        JsonNode message = objectMapper.readTree("""
            {
              "id":"mail-future",
              "historyId":"52",
              "internalDate":"1785456120000",
              "labelIds":["INBOX"],
              "payload":{
                "mimeType":"text/plain",
                "headers":[
                  {"name":"Subject","value":"Deferred"},
                  {"name":"From","value":"Sender <sender@example.net>"},
                  {"name":"To","value":"owner@example.test"}
                ]
              }
            }
            """);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            history, message, history, message);
        GoogleCaptureAdapter adapter =
            new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage deferred =
            adapter.fetch(request("mail_inbox", "50", null, false));
        ProviderCapturePage captured = adapter.fetch(request(
            "mail_inbox",
            "50",
            null,
            false,
            Instant.parse("2026-07-31T00:03:00Z")));

        assertTrue(deferred.items().isEmpty());
        assertEquals("50", deferred.stableCursor());
        assertEquals("Deferred", captured.items().getFirst().subject());
        assertEquals("52", captured.stableCursor());
        verify(httpClient, times(4)).get(any(URI.class), eq("token"));
    }

    @Test
    void providerBodyDenialPreventsGoogleAndMicrosoftBodyRequests() {
        ProviderCaptureHttpClient googleHttp =
            mock(ProviderCaptureHttpClient.class);
        when(googleHttp.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("{\"historyId\":\"45\"}"),
            objectMapper.readTree("""
                {"messages":[{"id":"mail-denied"}],"resultSizeEstimate":1}
                """),
            objectMapper.readTree("""
                {
                  "id":"mail-denied",
                  "historyId":"43",
                  "internalDate":"1785416400000",
                  "labelIds":["INBOX"],
                  "payload":{
                    "mimeType":"text/plain",
                    "headers":[
                      {"name":"Subject","value":"Denied"},
                      {"name":"From","value":"Sender <excluded@example.net>"},
                      {"name":"To","value":"owner@example.test"}
                    ]
                  }
                }
                """));
        GoogleCaptureAdapter google =
            new GoogleCaptureAdapter(googleHttp, objectMapper);

        ProviderCapturePage googlePage = google.fetch(request(
            "mail_inbox", null, null, true, item -> false, () -> {
            }));

        assertNull(googlePage.items().getFirst().body());
        verify(googleHttp, times(3)).get(any(URI.class), eq("token"));

        ProviderCaptureHttpClient microsoftCalendarHttp =
            mock(ProviderCaptureHttpClient.class);
        when(microsoftCalendarHttp.getMicrosoft(
                any(URI.class), eq("token"), eq(100)))
            .thenReturn(objectMapper.readTree("""
                {
                  "value":[{
                    "id":"event-denied",
                    "changeKey":"v1",
                    "subject":"Denied",
                    "sensitivity":"normal",
                    "start":{"dateTime":"2026-07-30T09:00:00","timeZone":"UTC"},
                    "end":{"dateTime":"2026-07-30T10:00:00","timeZone":"UTC"},
                    "organizer":{"emailAddress":{"address":"owner@example.test"}}
                  }]
                }
                """));
        MicrosoftCaptureAdapter microsoftCalendar =
            new MicrosoftCaptureAdapter(microsoftCalendarHttp, objectMapper);

        ProviderCapturePage calendarPage = microsoftCalendar.fetch(request(
            "calendar", null, null, true, item -> false, () -> {
            }));

        assertNull(calendarPage.items().getFirst().body());
        verify(microsoftCalendarHttp).getMicrosoft(
            any(URI.class), eq("token"), eq(100));
        verify(microsoftCalendarHttp, never()).getMicrosoft(
            any(URI.class), eq("token"), eq(1));

        ProviderCaptureHttpClient microsoftMailHttp =
            mock(ProviderCaptureHttpClient.class);
        when(microsoftMailHttp.getMicrosoft(
                any(URI.class), eq("token"), eq(100)))
            .thenReturn(objectMapper.readTree("""
                {
                  "value":[{"id":"mail-denied"}],
                  "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?$deltatoken=mail-5"
                }
                """));
        when(microsoftMailHttp.getMicrosoft(
                any(URI.class), eq("token"), eq(1)))
            .thenReturn(objectMapper.readTree("""
                {
                  "id":"mail-denied",
                  "changeKey":"v1",
                  "conversationId":"thread-denied",
                  "subject":"Denied",
                  "sensitivity":"normal",
                  "receivedDateTime":"2026-07-30T09:00:00Z",
                  "from":{"emailAddress":{"address":"sender@example.net"}},
                  "toRecipients":[{
                    "emailAddress":{"address":"owner@example.test"}
                  }]
                }
                """));
        MicrosoftCaptureAdapter microsoftMail =
            new MicrosoftCaptureAdapter(microsoftMailHttp, objectMapper);

        ProviderCapturePage mailPage = microsoftMail.fetch(request(
            "mail_inbox",
            microsoftDelta(
                "mail_inbox",
                "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?$deltatoken=mail-4"),
            null,
            true,
            item -> false,
            () -> {
            }));

        assertNull(mailPage.items().getFirst().body());
        ArgumentCaptor<URI> mailUri = ArgumentCaptor.forClass(URI.class);
        verify(microsoftMailHttp).getMicrosoft(
            any(URI.class), eq("token"), eq(100));
        verify(microsoftMailHttp).getMicrosoft(
            mailUri.capture(), eq("token"), eq(1));
        assertFalse(mailUri.getValue().getRawQuery().contains("body"));
    }

    @Test
    void googleHistoryTreatsStreamLabelRemovalAsATombstone() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(objectMapper.readTree("""
            {
              "historyId":"51",
              "history":[{
                "labelsRemoved":[{
                  "message":{"id":"mail-2"},
                  "labelIds":["INBOX"]
                }]
              }]
            }
            """));
        GoogleCaptureAdapter adapter = new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request("mail_inbox", "50", null, false));

        assertEquals("51", page.stableCursor());
        assertTrue(page.items().getFirst().tombstone());
    }

    @Test
    void microsoftUsesOneProviderNeutralPageContractForCalendarAndMail() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(100))).thenReturn(
            objectMapper.readTree("""
                {
                  "value":[{
                    "id":"meeting-1",
                    "changeKey":"v3",
                    "subject":"Customer review",
                    "sensitivity":"normal",
                    "start":{"dateTime":"2026-07-30T09:00:00","timeZone":"UTC"},
                    "end":{"dateTime":"2026-07-30T10:00:00","timeZone":"UTC"},
                    "organizer":{"emailAddress":{"address":"owner@example.test"}},
                    "attendees":[{
                      "emailAddress":{"address":"customer@example.net","name":"Customer"}
                    }]
                  }],
                  "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/calendarView/delta?$deltatoken=calendar-2"
                }
                """),
            objectMapper.readTree("""
                {
                  "value":[{"id":"mail-9","@removed":{"reason":"deleted"}}],
                  "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailfolders('opaque-sent-folder-id')/messages/delta?$deltatoken=mail-3"
                }
                """));
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage calendar =
            adapter.fetch(request("calendar", null, null, false));
        ProviderCapturePage mail =
            adapter.fetch(request(
                "mail_sent",
                microsoftDelta(
                    "mail_sent",
                    "https://graph.microsoft.com/v1.0/me/mailfolders('opaque-sent-folder-id')/messages/delta?$deltatoken=mail-2"),
                null,
                false));

        assertEquals("meeting", calendar.items().getFirst().interactionType());
        assertEquals(2, calendar.items().getFirst().participants().size());
        assertEquals(
            MicrosoftCaptureAdapter.CALENDAR_FULL_SCAN_CURSOR,
            calendar.stableCursor());
        assertTrue(mail.items().getFirst().tombstone());
        assertEquals(
            microsoftDelta(
                "mail_sent",
                "https://graph.microsoft.com/v1.0/me/mailfolders('opaque-sent-folder-id')/messages/delta?$deltatoken=mail-3"),
            mail.stableCursor());
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient, times(2))
            .getMicrosoft(uri.capture(), eq("token"), eq(100));
        assertTrue(uri.getAllValues().getFirst().getPath().endsWith("/calendarView"));
        assertFalse(uri.getAllValues().getFirst().getRawQuery().contains("body"));
    }

    @Test
    void microsoftMailBootstrapAnchorsThenListsTheBoundedWindow() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(1)))
            .thenReturn(objectMapper.readTree("""
                {
                  "id":"opaque-inbox-id"
                }
                """));
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(100)))
            .thenReturn(
                objectMapper.readTree("""
                {
                  "value":[],
                  "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailFolders/opaque-inbox-id/messages/delta?$deltatoken=mail-4"
                }
                """),
                objectMapper.readTree("""
                {
                  "value":[],
                  "@odata.nextLink":"https://graph.microsoft.com/v1.0/me/mailFolders/opaque-inbox-id/messages?$skip=100"
                }
                """));
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage anchor =
            adapter.fetch(request("mail_inbox", null, null, false));
        adapter.fetch(request(
            "mail_inbox", null, anchor.nextPageCursor(), false));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).getMicrosoft(uri.capture(), eq("token"), eq(1));
        URI folderUri = uri.getValue();
        verify(httpClient, times(2))
            .getMicrosoft(uri.capture(), eq("token"), eq(100));
        URI anchorUri = uri.getAllValues().get(1);
        URI backfillUri = uri.getAllValues().get(2);
        assertTrue(folderUri.getPath().endsWith("/mailFolders/inbox"));
        assertTrue(anchorUri.getRawQuery().contains("%24select=id"));
        assertTrue(anchorUri.getRawQuery().contains("%24filter"));
        assertTrue(backfillUri.getPath().endsWith("/messages"));
        assertTrue(backfillUri.getRawQuery().contains("%24filter"));
        assertTrue(backfillUri.getRawQuery().contains("%24orderby"));
        assertFalse(backfillUri.getRawQuery().contains(
            "2026-07-31T00%3A00%3A00Z"));
    }

    @Test
    void microsoftRejectsAProviderSuppliedCursorForAnotherHost() {
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(
                mock(ProviderCaptureHttpClient.class), objectMapper);

        ProviderCaptureException exception = assertThrows(
            ProviderCaptureException.class,
            () -> adapter.fetch(request(
                "mail_inbox",
                microsoftDelta(
                    "mail_inbox",
                    "https://attacker.example/v1.0/delta"),
                null,
                false)));

        assertEquals("cursor_invalid", exception.getCode());
    }

    @Test
    void microsoftRejectsACursorBoundToAnotherMailStream() {
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(
                mock(ProviderCaptureHttpClient.class), objectMapper);

        ProviderCaptureException exception = assertThrows(
            ProviderCaptureException.class,
            () -> adapter.fetch(request(
                "mail_inbox",
                microsoftDelta(
                    "mail_sent",
                    "https://graph.microsoft.com/v1.0/me/mailFolders/sentitems/messages/delta?$deltatoken=mail-2"),
                null,
                false)));

        assertEquals("cursor_invalid", exception.getCode());
    }

    @Test
    void microsoftRejectsACursorForAnotherMailFolder() {
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(
                mock(ProviderCaptureHttpClient.class), objectMapper);

        ProviderCaptureException exception = assertThrows(
            ProviderCaptureException.class,
            () -> adapter.fetch(request(
                "mail_inbox",
                microsoftDelta(
                    "mail_inbox",
                    "https://graph.microsoft.com/v1.0/me/mailFolders/drafts/messages/delta?$deltatoken=mail-2",
                    "opaque-inbox-id"),
                null,
                false)));

        assertEquals("cursor_invalid", exception.getCode());
    }

    @Test
    void microsoftDropsNonTextBodiesEvenWhenBodyCaptureIsEnabled() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(100)))
            .thenReturn(objectMapper.readTree("""
                {
                  "value":[{"id":"mail-html"}],
                  "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?$deltatoken=mail-4"
                }
                """));
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(1)))
            .thenReturn(
                objectMapper.readTree("""
                {
                    "id":"mail-html",
                    "changeKey":"v1",
                    "conversationId":"thread-1",
                    "subject":"Tracked",
                    "sensitivity":"normal",
                    "receivedDateTime":"2026-07-30T09:00:00Z",
                    "from":{"emailAddress":{"address":"sender@example.net"}},
                    "toRecipients":[{
                      "emailAddress":{"address":"owner@example.test"}
                    }]
                }
                """),
                objectMapper.readTree("""
                {
                  "body":{
                    "contentType":"html",
                    "content":"<img src=\\"https://tracker.example/pixel\\">"
                  }
                }
                """));
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(httpClient, objectMapper);
        ProviderCaptureLease lease = mock(ProviderCaptureLease.class);

        ProviderCapturePage page =
            adapter.fetch(request(
                "mail_inbox",
                microsoftDelta(
                    "mail_inbox",
                    "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?$deltatoken=mail-3"),
                null,
                true,
                lease));

        assertNull(page.items().getFirst().body());
        verify(lease, times(3)).renew();
    }

    @Test
    void microsoftOversizedBodyFallsBackToMetadataWithoutWedgingTheStream() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(100)))
            .thenReturn(objectMapper.readTree("""
                {
                  "value":[{"id":"mail-large"}],
                  "@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?$deltatoken=mail-5"
                }
                """));
        when(httpClient.getMicrosoft(any(URI.class), eq("token"), eq(1)))
            .thenReturn(objectMapper.readTree("""
                {
                  "id":"mail-large",
                  "changeKey":"v1",
                  "conversationId":"thread-2",
                  "subject":"Large",
                  "sensitivity":"normal",
                  "receivedDateTime":"2026-07-30T09:00:00Z",
                  "from":{"emailAddress":{"address":"sender@example.net"}},
                  "toRecipients":[{
                    "emailAddress":{"address":"owner@example.test"}
                  }]
                }
                """))
            .thenThrow(new ProviderCaptureException(
                "response_too_large",
                false,
                false,
                "Provider response exceeded the bound"));
        MicrosoftCaptureAdapter adapter =
            new MicrosoftCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request(
                "mail_inbox",
                microsoftDelta(
                    "mail_inbox",
                    "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages/delta?$deltatoken=mail-4"),
                null,
                true));

        assertEquals("Large", page.items().getFirst().subject());
        assertNull(page.items().getFirst().body());
    }

    @Test
    void googleOversizedBodyFallsBackToMetadataWithoutWedgingTheStream() {
        ProviderCaptureHttpClient httpClient = mock(ProviderCaptureHttpClient.class);
        JsonNode metadata = objectMapper.readTree("""
            {
              "id":"mail-large",
              "historyId":"43",
              "internalDate":"1785416400000",
              "labelIds":["INBOX"],
              "payload":{
                "mimeType":"text/plain",
                "headers":[
                  {"name":"Subject","value":"Large"},
                  {"name":"From","value":"Sender <sender@example.net>"},
                  {"name":"To","value":"owner@example.test"}
                ]
              }
            }
            """);
        when(httpClient.get(any(URI.class), eq("token"))).thenReturn(
            objectMapper.readTree("{\"historyId\":\"45\"}"),
            objectMapper.readTree("{\"messages\":[{\"id\":\"mail-large\"}]}"),
            metadata
        ).thenThrow(new ProviderCaptureException(
            "response_too_large",
            false,
            false,
            "Provider response exceeded the bound"));
        GoogleCaptureAdapter adapter =
            new GoogleCaptureAdapter(httpClient, objectMapper);

        ProviderCapturePage page =
            adapter.fetch(request("mail_inbox", null, null, true));

        assertEquals("Large", page.items().getFirst().subject());
        assertNull(page.items().getFirst().body());
    }

    private static ProviderCaptureRequest request(
            String stream,
            String stableCursor,
            String pageCursor,
            boolean includeBodies) {
        return request(
            stream,
            stableCursor,
            pageCursor,
            includeBodies,
            item -> !item.privateItem(),
            () -> {
            });
    }

    private static ProviderCaptureRequest request(
            String stream,
            String stableCursor,
            String pageCursor,
            boolean includeBodies,
            ProviderCaptureLease lease) {
        return request(
            stream,
            stableCursor,
            pageCursor,
            includeBodies,
            item -> !item.privateItem(),
            lease);
    }

    private static ProviderCaptureRequest request(
            String stream,
            String stableCursor,
            String pageCursor,
            boolean includeBodies,
            ProviderCaptureBodyAccess bodyAccess,
            ProviderCaptureLease lease) {
        return request(
            stream,
            stableCursor,
            pageCursor,
            includeBodies,
            bodyAccess,
            lease,
            Instant.parse("2026-07-31T00:00:00Z"));
    }

    private static ProviderCaptureRequest request(
            String stream,
            String stableCursor,
            String pageCursor,
            boolean includeBodies,
            Instant to) {
        return request(
            stream,
            stableCursor,
            pageCursor,
            includeBodies,
            item -> !item.privateItem(),
            () -> {
            },
            to);
    }

    private static ProviderCaptureRequest request(
            String stream,
            String stableCursor,
            String pageCursor,
            boolean includeBodies,
            ProviderCaptureBodyAccess bodyAccess,
            ProviderCaptureLease lease,
            Instant to) {
        return new ProviderCaptureRequest(
            "token",
            stream,
            stableCursor,
            pageCursor,
            Instant.parse("2026-07-01T00:00:00Z"),
            to,
            includeBodies,
            bodyAccess,
            100,
            lease);
    }

    private String microsoftDelta(String stream, String providerCursor) {
        String path = URI.create(providerCursor).getPath();
        String slashPrefix = "/v1.0/me/mailFolders/";
        String functionPrefix = "/v1.0/me/mailfolders('";
        int slashStart = path.indexOf(slashPrefix);
        int functionStart = path.toLowerCase().indexOf(functionPrefix);
        String folderId;
        if (slashStart >= 0) {
            folderId = path.substring(
                slashStart + slashPrefix.length(),
                path.indexOf("/messages/delta", slashStart));
        } else if (functionStart >= 0) {
            folderId = path.substring(
                functionStart + functionPrefix.length(),
                path.indexOf("')/messages/delta", functionStart));
        } else {
            folderId = "mail_inbox".equals(stream) ? "inbox" : "sentitems";
        }
        return microsoftDelta(stream, providerCursor, folderId);
    }

    private String microsoftDelta(
            String stream, String providerCursor, String folderId) {
        LinkedHashMap<String, String> cursor = new LinkedHashMap<>();
        cursor.put("stream", stream);
        cursor.put("providerCursor", providerCursor);
        cursor.put("folderId", folderId);
        byte[] json = objectMapper.writeValueAsBytes(cursor);
        return "microsoft-mail-delta:v1:"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(
                json);
    }
}
