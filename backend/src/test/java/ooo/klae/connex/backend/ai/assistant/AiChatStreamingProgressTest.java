package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;

class AiChatStreamingProgressTest {

    private static AiChatQueuedTurn turn(AiPrivacyMode privacyMode) {
        return new AiChatQueuedTurn(
                7, 11, 13, 17, 19, 1, 23L, false, List.of(), List.of(), privacyMode, true);
    }

    /**
     * A masked turn's requester receives the same demasked answer once the turn settles, so the
     * streamed batches they read on the way there must carry the same names rather than the
     * placeholders that were sent to the provider.
     */
    @Test
    void aMaskedTurnStreamsDemaskedBatchesToItsRequester() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        MaskingContext context = new MaskingContext();
        String token = MaskingEngine.maskField(EntityKind.PERSON, "Ada Lovelace", context);
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.MASKED), persistenceService, context);

        AiChatStreamingProgress.Observer observer = progress.observer(true);
        observer.onContentDelta("{\"text\":\"Ask " + token + " about it.\"}");
        observer.finish("Ask Ada Lovelace about it.");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Ask Ada Lovelace about it.", batches.getValue());
        assertTrue(!batches.getValue().contains(token));
    }

    /**
     * Providers commonly deliver a space or a newline as its own delta. The demasker answers a
     * blank input with the empty string, so demasking one would silently run the words together.
     */
    @Test
    void aWhitespaceOnlyDeltaSurvivesDemasking() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        MaskingContext context = new MaskingContext();
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.MASKED), persistenceService, context);

        AiChatStreamingProgress.Observer observer = progress.observer(true);
        observer.onContentDelta("{\"text\":\"Two");
        observer.onContentDelta(" ");
        observer.onContentDelta("words\"}");
        observer.finish("Two words");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Two words", batches.getValue());
    }

    /**
     * A stream stopped at the durable bound is one attempt's state, not the turn's. A malformed
     * attempt whose demasked text crossed the bound is reset and retried, and the repaired
     * attempt must stream — a truncation left standing would swallow every delta it produces and
     * skip the settle-time comparison built to notice exactly that.
     */
    @Test
    void aResetClearsTheTruncationTheFailedAttemptReached() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED),
                persistenceService,
                new MaskingContext(AiPrivacyMode.UNMASKED));

        AiChatStreamingProgress.Observer first = progress.observer(true);
        first.onContentDelta("{\"text\":\"" + "x".repeat(17_000));
        progress.reset();

        AiChatStreamingProgress.Observer second = progress.observer(true);
        second.onContentDelta("{\"text\":\"Recovered answer.\"}");
        second.finish("Recovered answer.");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Recovered answer.", batches.getValue());
    }

    /**
     * An unmasked turn's context holds no bindings, so demasking it would only rewrite a literal
     * brace pair the model typed into an unknown-reference marker. Its stream stays untouched.
     */
    @Test
    void anUnmaskedTurnStreamsItsTextUntouched() {
        AiChatTurnPersistenceService persistenceService =
                mock(AiChatTurnPersistenceService.class);
        when(persistenceService.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> ((Integer) call.getArgument(1))
                        + ((String) call.getArgument(2)).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED),
                persistenceService,
                new MaskingContext(AiPrivacyMode.UNMASKED));

        AiChatStreamingProgress.Observer observer = progress.observer(true);
        observer.onContentDelta("{\"text\":\"Braces {{P1}} stay literal.\"}");
        observer.finish("Braces {{P1}} stay literal.");

        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals("Braces {{P1}} stay literal.", batches.getValue());
    }

    @Test
    void splitAndDemaskedTaskHandlesNeverRemainInDurablePartialContent() {
        for (boolean masked : List.of(false, true)) {
            AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
            when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                    invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
            MaskingContext context = new MaskingContext(masked ? AiPrivacyMode.MASKED : AiPrivacyMode.UNMASKED);
            String token = masked ? MaskingEngine.maskField(EntityKind.PERSON, "t1", context) : "t1";
            AiChatStreamingProgress progress = new AiChatStreamingProgress(
                    turn(context.privacyMode()), persistence, context);
            var observer = progress.observer(false);
            observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + "Safe words. ".repeat(30));
            if (masked) {
                observer.onContentDelta(token);
            } else {
                observer.onContentDelta("t");
                observer.onContentDelta("1");
            }
            observer.onContentDelta(" is done.\"}}");
            org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                    () -> observer.finish("Safe words. ".repeat(30) + "t1 is done."));
            verify(persistence).resetPartialContent(any(), anyInt());
            ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
            verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
            assertTrue(batches.getAllValues().stream().noneMatch(AiAssistantStepGuard::containsTaskHandle));
        }
    }

    @Test
    void aTaskLikePrefixMayFinishAsAnOrdinaryWordAcrossChunks() {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + "Safe words. ".repeat(30) + "t1");
        observer.onContentDelta("alpha is a name.\"}}");
        String expected = "Safe words. ".repeat(30) + "t1alpha is a name.";
        assertEquals(expected, observer.finish(expected));
        verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals(expected, String.join("", batches.getAllValues()));
    }

    @Test
    void truncationNeverFlushesAHeldPrefixAsACompleteTaskHandle() {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        String prefix = "x".repeat(15_995) + " ";
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + prefix + "t1");
        observer.onContentDelta("alpha continues past the stream limit.\"}}");
        observer.finish(prefix + "t1alpha continues past the stream limit.");
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertTrue(!AiAssistantStepGuard.containsTaskHandle(String.join("", batches.getAllValues())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[t](record:r1)1", "ｔ１", "[ｔ](record:r1)１", "[t](record:r1)[1](record:r2)"})
    void canonicalTaskPrefixesMayFinishAsOrdinaryWords(String taskPrefix) {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        String safe = "Safe words. ".repeat(30);
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + safe);
        for (int offset = 0; offset < taskPrefix.length(); offset++) {
            observer.onContentDelta(taskPrefix.substring(offset, offset + 1));
        }
        verify(persistence).appendPartialBatch(any(), anyInt(), org.mockito.ArgumentMatchers.eq(safe));
        verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
        observer.onContentDelta("alpha is a name.\"}}");
        String expected = safe + taskPrefix + "alpha is a name.";
        assertEquals(expected, observer.finish(expected));
        verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals(expected, String.join("", batches.getAllValues()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[t](record:r1)1", "ｔ１"})
    void canonicalTaskPrefixesAreRefusedAtADelimiterOrFinish(String taskPrefix) {
        for (String ending : List.of(" ", "")) {
            AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
            when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                    invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
            AiChatStreamingProgress progress = new AiChatStreamingProgress(
                    turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
            var observer = progress.observer(false);
            String safe = "Safe words. ".repeat(30);
            observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + safe + taskPrefix);
            verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
            observer.onContentDelta(ending + "\"}}");
            org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                    () -> observer.finish(safe + taskPrefix + ending));
            if (!ending.isEmpty()) {
                verify(persistence).resetPartialContent(any(), org.mockito.ArgumentMatchers.eq(safe.length()));
            }
            ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
            verify(persistence).appendPartialBatch(any(), anyInt(), batches.capture());
            assertEquals(safe, batches.getValue());
            assertTrue(!AiAssistantStepGuard.containsTaskHandle(batches.getValue()));
        }
    }

    @Test
    void anUnclosedCompatibilityLinkStaysPendingAfterEarlierLinkBatches() {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        String safe = "[label](record:r2) words. ".repeat(12);
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + safe);
        String openLink = "［t］(record:r1 " + "x".repeat(256) + " ";
        observer.onContentDelta(openLink);
        ArgumentCaptor<String> initialBatches = ArgumentCaptor.forClass(String.class);
        verify(persistence).appendPartialBatch(any(), anyInt(), initialBatches.capture());
        assertEquals(safe, initialBatches.getValue());
        observer.onContentDelta(")1alpha.\"}}");
        String expected = safe + openLink + ")1alpha.";
        assertEquals(expected, observer.finish(expected));
        verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals(expected, String.join("", batches.getAllValues()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[Note] ", "［Note］ ", "﹇Note﹈ "})
    void settledBracketedProseStreamsBeforeFinish(String annotation) {
        assertStreamsBeforeFinish(annotation + "Safe words. ".repeat(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"通常の文章です。", "通常の文章です", "Safe　words　", "tttttttt", "at1at1at1", "áááá"})
    void proseWithoutAsciiWhitespaceStreamsBeforeFinish(String phrase) {
        assertStreamsBeforeFinish(phrase.repeat(40));
    }

    private static void assertStreamsBeforeFinish(String expected) {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + expected);
        ArgumentCaptor<String> initialBatches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce())
                .appendPartialBatch(any(), anyInt(), initialBatches.capture());
        String streamed = String.join("", initialBatches.getAllValues());
        assertTrue(streamed.length() >= 256);
        assertTrue(expected.startsWith(streamed));
        observer.onContentDelta("\"}}");
        assertEquals(expected, observer.finish(expected));
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals(expected, String.join("", batches.getAllValues()));
        verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ｔ１｛", "ｔ１｝", "[t](record:r1)１｛"})
    void aCancellingBraceDoesNotPrematurelyRefuseAPermittedAnswer(String taskPrefix) {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        String safe = "Safe words. ".repeat(30);
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + safe + taskPrefix);
        String ending = taskPrefix.substring(taskPrefix.length() - 1) + "alpha.";
        observer.onContentDelta(ending + "\"}}");
        String expected = safe + taskPrefix + ending;
        assertEquals(expected, observer.finish(expected));
        verify(persistence, org.mockito.Mockito.never()).resetPartialContent(any(), anyInt());
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals(expected, String.join("", batches.getAllValues()));
        assertTrue(batches.getAllValues().stream().noneMatch(AiAssistantStepGuard::containsTaskHandle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "t", "t1"})
    void batchesRetainTheWordContextThatMakesATaskShapedSuffixPermitted(String firstSuffix) {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(1) + invocation.<String>getArgument(2).length());
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(false);
        String prefix = "a".repeat(300) + firstSuffix;
        observer.onContentDelta("{\"tool\":null,\"final\":{\"text\":\"" + prefix);
        String ending = "t1".substring(firstSuffix.length()) + " " + "Safe words. ".repeat(30);
        observer.onContentDelta(ending + "\"}}");
        assertEquals(prefix + ending, observer.finish(prefix + ending));
        ArgumentCaptor<String> batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), batches.capture());
        assertEquals(prefix + ending, String.join("", batches.getAllValues()));
        assertTrue(batches.getAllValues().stream().noneMatch(AiAssistantStepGuard::containsTaskHandle));
    }

    @Test
    void nativeProjectionRefusesAHandleWithoutAppendingAnyBatch() {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(true);
        String safe = "Safe words. ".repeat(30);
        observer.onContentDelta("{\"text\":\"" + safe + "t1");
        org.mockito.Mockito.verifyNoInteractions(persistence);
        observer.onContentDelta(" is done.\"}");
        org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                () -> observer.finish(safe + "t1 is done."));
        verify(persistence).resetPartialContent(any(), org.mockito.ArgumentMatchers.eq(0));
        verify(persistence, org.mockito.Mockito.never()).appendPartialBatch(any(), anyInt(), any());
    }

    @Test
    void nativeProjectionOverTheStreamLimitDoesNotAppendAnyBatch() {
        AiChatTurnPersistenceService persistence = mock(AiChatTurnPersistenceService.class);
        AiChatStreamingProgress progress = new AiChatStreamingProgress(
                turn(AiPrivacyMode.UNMASKED), persistence, new MaskingContext(AiPrivacyMode.UNMASKED));
        var observer = progress.observer(true);
        String prefix = "x".repeat(15_995) + " ";
        observer.onContentDelta("{\"text\":\"" + prefix + "t1");
        org.mockito.Mockito.verifyNoInteractions(persistence);
        String ending = "alpha continues past the stream limit.";
        observer.onContentDelta(ending + "\"}");
        assertEquals(prefix + "t1" + ending, observer.finish(prefix + "t1" + ending));
        verify(persistence, org.mockito.Mockito.never()).appendPartialBatch(any(), anyInt(), any());
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> splitRefusedMemberSpellings() {
        return java.util.stream.Stream.of(false, true).flatMap(nativeProtocol -> java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(nativeProtocol, "{{m", "1}}"),
                org.junit.jupiter.params.provider.Arguments.of(nativeProtocol, "｛｛ｍ", "１｝｝"),
                org.junit.jupiter.params.provider.Arguments.of(nativeProtocol, "﹛﹛Ｍ", "1﹜﹜")));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("splitRefusedMemberSpellings")
    void splitNoncanonicalMemberTokensNeverReachPartialFrames(boolean nativeProtocol, String start, String end) {
        var persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
        var context = new MaskingContext();
        context.tokenFor(EntityKind.MEMBER, "Unused Member");
        var progress = new AiChatStreamingProgress(turn(AiPrivacyMode.MASKED), persistence, context);
        var observer = progress.observer(nativeProtocol);
        String safe = "Safe answer words. ".repeat(40);
        String opening = nativeProtocol ? "{\"text\":\"" : "{\"tool\":null,\"final\":{\"text\":\"";
        observer.onContentDelta(opening + safe + start);
        var batches = ArgumentCaptor.forClass(String.class);
        verify(persistence, org.mockito.Mockito.atMost(4)).appendPartialBatch(any(), anyInt(), batches.capture());
        for (String batch : batches.getAllValues()) {
            assertTrue(!batch.contains(start));
            assertTrue(!batch.contains("{{") && !batch.contains("｛") && !batch.contains("﹛"));
        }
        observer.onContentDelta(end + (nativeProtocol ? "\"}" : "\"}}"));
        var refused = org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                () -> observer.finish(safe + start + end));
        assertEquals("malformed_output", refused.terminalReason());
        assertEquals("invented_placeholder", refused.detailReason());
        verify(persistence, org.mockito.Mockito.atMost(4)).appendPartialBatch(any(), anyInt(), batches.capture());
        assertTrue(batches.getAllValues().stream().noneMatch(batch -> batch.contains(start) || batch.contains(end)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexposedMemberRefusalSurvivesRealTransportAndResetsAlreadyFlushedFrames(boolean nativeProtocol) throws Exception {
        var persistence = mock(AiChatTurnPersistenceService.class);
        when(persistence.appendPartialBatch(any(), anyInt(), any()))
                .thenAnswer(call -> (Integer) call.getArgument(1) + ((String) call.getArgument(2)).length());
        var context = new MaskingContext();
        context.registerMemberCandidate("Unused Member");
        var queued = turn(AiPrivacyMode.MASKED);
        var progress = new AiChatStreamingProgress(queued, persistence, context);
        var observer = progress.observer(nativeProtocol);
        String prefix = "Safe answer words. ".repeat(40);
        String answer = prefix + "{{M1}}";
        String body = AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, answer);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        var client = ooo.klae.connex.backend.ai.egress.FixedAiProviderClientTestSupport.loopback();
        try {
            String host = "stream.example.test";
            var response = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> client.postStream(
                    java.net.URI.create("http://" + host + ":" + server.getAddress().getPort() + "/stream"),
                    java.util.Set.of(host), java.util.Map.of(), org.apache.hc.core5.http.ContentType.APPLICATION_JSON,
                    new byte[0], ooo.klae.connex.backend.ai.egress.AiRequestDeadline.afterMillis(10_000),
                    "member stream fixture", observer, input -> {
                        String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        int token = text.indexOf("{{M1}}");
                        observer.onContentDelta(text.substring(0, token));
                        observer.onContentDelta(text.substring(token));
                        return text;
                    }));
            assertEquals(200, response.statusCode());
            if (nativeProtocol) {
                verify(persistence, org.mockito.Mockito.never()).appendPartialBatch(any(), anyInt(), any());
            } else {
                verify(persistence, org.mockito.Mockito.atLeastOnce()).appendPartialBatch(any(), anyInt(), any());
            }
            var refused = org.junit.jupiter.api.Assertions.assertThrows(AiAssistantLoopException.class,
                    () -> observer.finish(answer));
            assertEquals("malformed_output", refused.terminalReason());
            assertEquals("invented_placeholder", refused.detailReason());
            progress.reset();
            verify(persistence).resetPartialContent(org.mockito.ArgumentMatchers.eq(queued),
                    org.mockito.ArgumentMatchers.intThat(offset -> nativeProtocol ? offset == 0 : offset > 0));
            var repaired = progress.observer(nativeProtocol);
            repaired.onContentDelta(AiAssistantMaskingTestSupport.finalOutput(nativeProtocol, "Ready"));
            assertEquals("Ready", repaired.finish("Ready"));
        } finally {
            ooo.klae.connex.backend.ai.egress.FixedAiProviderClientTestSupport.close(client);
            server.stop(0);
        }
    }

}
