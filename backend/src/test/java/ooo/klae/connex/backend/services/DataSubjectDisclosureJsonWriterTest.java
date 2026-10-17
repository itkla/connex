package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import lombok.Data;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.*;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureNotes;
import ooo.klae.connex.backend.exceptions.DataSubjectDisclosureStreamException;

/** Pins legacy bytes and failure framing independently of the HTTP and database layers. */
class DataSubjectDisclosureJsonWriterTest {
    @Test
    void bothPassesMatchLegacyBytesIncludingPropertyOrderUnicodeEscapesDatesAndNulls() throws Exception {
        for (ObjectMapper mapper : List.of(new ObjectMapper(), JsonMapper.builder()
                .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL).build())) {
            DataSubjectDisclosureDto disclosure = fixture();
            NoteDto note = note(7);
            note.setContent("日本語 😀 \"quoted\"\nline\t\\slash\u0001");
            note.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000));
            AtomicInteger passes = new AtomicInteger();
            disclosure.setNotes(new DataSubjectDisclosureNotes(consumer -> {
                passes.incrementAndGet();
                consumer.accept(note);
            }));
            LegacyDisclosure legacy = new LegacyDisclosure();
            BeanUtils.copyProperties(disclosure, legacy, "notes");
            legacy.setNotes(mapper.writeValueAsString(List.of(note)));
            byte[] expected = mapper.writeValueAsBytes(legacy);
            var writer = new DataSubjectDisclosureJsonWriter(mapper);
            var measured = writer.measure(disclosure);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writer.write(disclosure, measured, fingerprint -> output);

            assertEquals(2, passes.get());
            assertArrayEquals(expected, output.toByteArray());
            assertEquals(expected.length, measured.byteLength());
            assertEquals(hash(expected), measured.sha256());
            assertEquals(hash(mapper.writeValueAsBytes(List.of(note))), measured.notesSha256());
            assertEquals(1, measured.noteCount());
            assertEquals(1, mapper.readTree(output.toByteArray()).path("notes").size());
        }
    }

    @Test
    void emptyArrayHasTheOrdinaryArrayFingerprint() throws Exception {
        var writer = new DataSubjectDisclosureJsonWriter(new ObjectMapper());
        var measured = writer.measure(fixture());
        assertEquals(0, measured.noteCount());
        assertEquals(hash("[]".getBytes(StandardCharsets.UTF_8)), measured.notesSha256());
    }

    @Test
    void pageLeaseProxyProvesAtMostOnePageAndIncrementalNonRetainingOutput() {
        AtomicInteger leases = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger pages = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicLong lastPageBytes = new AtomicLong();
        OutputStream sink = new OutputStream() {
            @Override public void write(int value) { bytes.incrementAndGet(); }
            @Override public void write(byte[] data, int offset, int length) { bytes.addAndGet(length); }
        };
        SimpleModule module = new SimpleModule();
        module.addSerializer(NoteDto.class, new ValueSerializer<NoteDto>() {
            @Override
            public void serialize(NoteDto value, JsonGenerator generator, SerializationContext context) {
                generator.writeStartObject();
                generator.writeNumberProperty("id", value.getId());
                generator.writeStringProperty("content", value.getContent());
                generator.writeEndObject();
                if (value.getId() % 100 == 0) {
                    assertEquals(1, leases.getAndDecrement());
                }
            }
        });
        var writer = new DataSubjectDisclosureJsonWriter(JsonMapper.builder().addModule(module).build());
        DataSubjectDisclosureDto disclosure = fixture();
        disclosure.setNotes(new DataSubjectDisclosureNotes(consumer -> {
            for (int page = 0; page < 5; page++) {
                assertEquals(0, leases.get(), "The preceding page must be fully consumed");
                if (pages.get() >= 5 && page > 0) {
                    assertTrue(bytes.get() > lastPageBytes.get(), "Output must advance before another page");
                }
                lastPageBytes.set(bytes.get());
                maximum.accumulateAndGet(leases.incrementAndGet(), Math::max);
                pages.incrementAndGet();
                for (int offset = 1; offset <= 100; offset++) {
                    NoteDto value = note(page * 100 + offset);
                    value.setContent("x".repeat(16_384));
                    consumer.accept(value);
                }
            }
            assertEquals(0, leases.get());
        }));
        var measured = writer.measure(disclosure);
        writer.write(disclosure, measured, fingerprint -> sink);
        assertEquals(500, measured.noteCount());
        assertEquals(measured.byteLength(), bytes.get());
        assertEquals(10, pages.get());
        assertEquals(1, maximum.get(), "Peak concurrent page leases");
    }

    @Test
    void mismatchedSecondPassWithholdsTheFinalDelimiterAndDoesNotCloseTarget() {
        var mapper = new ObjectMapper();
        var writer = new DataSubjectDisclosureJsonWriter(mapper);
        DataSubjectDisclosureDto disclosure = fixture();
        AtomicInteger pass = new AtomicInteger();
        disclosure.setNotes(new DataSubjectDisclosureNotes(consumer -> consumer.accept(note(pass.incrementAndGet()))));
        var measured = writer.measure(disclosure);
        AtomicInteger closes = new AtomicInteger();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void close() { closes.incrementAndGet(); }
        };
        assertThrows(DataSubjectDisclosureStreamException.class,
            () -> writer.write(disclosure, measured, fingerprint -> output));
        assertEquals(0, closes.get());
        assertFalse(output.toString(StandardCharsets.UTF_8).endsWith("}"));
        assertThrows(RuntimeException.class, () -> mapper.readTree(output.toByteArray()));
    }

    @Test
    void serializationFailureNeverAutoCompletesContentOrAppendsAnError() {
        var mapper = new ObjectMapper();
        var writer = new DataSubjectDisclosureJsonWriter(mapper);
        DataSubjectDisclosureDto disclosure = fixture();
        AtomicInteger passes = new AtomicInteger();
        disclosure.setNotes(new DataSubjectDisclosureNotes(consumer -> {
            consumer.accept(note(1));
            if (passes.incrementAndGet() == 2) {
                throw new IllegalStateException("injected body read failure");
            }
        }));
        var measured = writer.measure(disclosure);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(DataSubjectDisclosureStreamException.class,
            () -> writer.write(disclosure, measured, fingerprint -> output));
        String partial = output.toString(StandardCharsets.UTF_8);
        assertTrue(partial.contains("\"notes\":["));
        assertFalse(partial.endsWith("}"));
        assertFalse(partial.contains("injected"));
        assertThrows(RuntimeException.class, () -> mapper.readTree(output.toByteArray()));
    }

    @Test
    void targetFailurePropagatesWithoutClosingTheTarget() {
        var writer = new DataSubjectDisclosureJsonWriter(new ObjectMapper());
        DataSubjectDisclosureDto disclosure = fixture();
        var measured = writer.measure(disclosure);
        AtomicInteger closes = new AtomicInteger();
        OutputStream target = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("disconnected"); }
            @Override public void close() { closes.incrementAndGet(); }
        };
        assertThrows(DataSubjectDisclosureStreamException.class,
            () -> writer.write(disclosure, measured, fingerprint -> target));
        assertEquals(0, closes.get());
    }

    private static DataSubjectDisclosureDto fixture() {
        DataSubjectDisclosureDto disclosure = new DataSubjectDisclosureDto();
        disclosure.setRequestId(1733);
        disclosure.setSubjectWorkspaceId(4);
        disclosure.setSubjectPersonId(5);
        disclosure.setGeneratedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000));
        PersonDto person = new PersonDto();
        person.setId(5);
        person.setName("Subject 内容");
        disclosure.setPerson(person);
        disclosure.setIdentities(List.of());
        disclosure.setThirdPartyProvisions(List.of());
        disclosure.setNotes(new DataSubjectDisclosureNotes(consumer -> {}));
        return disclosure;
    }

    private static NoteDto note(int id) {
        NoteDto note = new NoteDto();
        note.setId(id);
        note.setContent("body");
        note.setVisibility("private");
        return note;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** The pre-streaming DTO's complete property declaration and raw-array representation. */
    @Data
    public static class LegacyDisclosure {
        private long requestId;
        private int subjectWorkspaceId;
        private int subjectPersonId;
        private LocalDateTime generatedAt;
        private PersonDto person;
        private List<PersonIdentityDto> identities;
        private List<TagDto> tags;
        private List<CustomFieldValueDto> customFieldValues;
        private List<ActivityDto> activities;
        private List<ProviderCaptureEvidenceDto> providerCaptureEvidence;
        @JsonRawValue
        private String notes;
        private List<RecordCommentThreadDisclosureDto> recordCommentThreads;
        private List<TaskDto> tasks;
        private List<AttachmentDto> attachments;
        private List<EmploymentDto> employmentHistory;
        private List<LifecycleTransitionDto> lifecycleHistory;
        private List<QualificationAnswerDto> qualificationAnswers;
        private List<LifecyclePassDto> lifecyclePasses;
        private List<RelationshipEdgeDto> relationshipEdges;
        private List<DealAssociationDto> dealAssociations;
        private List<IntroductionDto> introductions;
        private List<ThirdPartyProvisionDto> thirdPartyProvisions;
        private List<ConsentStateDto> consentState;
        private List<ConsentHistoryDto> consentHistory;
        private List<AudienceExportEvidenceDto> audienceExportEvidence;
        private List<AuditEntryDto> auditTrail;
        private long auditTrailTotal;
    }
}
