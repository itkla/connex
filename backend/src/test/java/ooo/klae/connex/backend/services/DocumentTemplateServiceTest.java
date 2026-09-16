package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.controllers.DealDocumentController;
import ooo.klae.connex.backend.controllers.DocumentDeliveryController;
import ooo.klae.connex.backend.controllers.DocumentTemplateController;
import ooo.klae.connex.backend.dto.DealLineItemRequest;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.GlobalExceptionHandler;
import ooo.klae.connex.backend.observability.ErrorReporter;
import ooo.klae.connex.backend.signature.DocumentSignatureEmailService;

class DocumentTemplateServiceTest extends AbstractCommittedDocumentDeliveryServiceTest {
    private static final String INLINE_BODY = """
        {"type":"doc","content":[{"type":"paragraph","content":[
          {"type":"text","text":"Total payable: USD 100."},{"type":"lineItems"}]}]}
        """;
    private static final String BLOCK_BODY = """
        {"type":"doc","content":[
          {"type":"paragraph","content":[{"type":"text","text":"Total payable: USD 100."}]},
          {"type":"lineItems"}]}
        """;
    private static final String MALFORMED_MARKS_BODY = """
        {"type":"doc","content":[
          {"type":"paragraph","content":[{"type":"text","text":"Terms","marks":{}}]},
          {"type":"lineItems"}]}
        """;
    private static final String MALFORMED_MARKS_MESSAGE =
        "Document node marks must be an array at body.content[0].content[0].marks";

    @Autowired private ObjectMapper objectMapper;
    @Autowired private ErrorReporter errorReporter;
    @Autowired private DealLineItemService lineItemService;
    @MockitoBean private DocumentSignatureEmailService emailService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpHttpBoundary() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DocumentTemplateController(templateService),
                new DealDocumentController(documentService),
                new DocumentDeliveryController(deliveryService))
            .setControllerAdvice(new GlobalExceptionHandler(errorReporter, tenantContext))
            .build();
    }

    @Test
    void rejectsInlineLineItemsOnCreateAndUpdateWithoutPersisting() throws Exception {
        mockMvc.perform(post("/api/document-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateRequest(INLINE_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "Line items must appear as a block, outside paragraphs and other text containers"));
        assertTrue(templateService.getAll().isEmpty());

        DocumentTemplate saved = templateService.create(template(BLOCK_BODY));
        mockMvc.perform(put("/api/document-templates/{id}", saved.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateRequest(INLINE_BODY)))
            .andExpect(status().isBadRequest());
        assertEquals(objectMapper.readTree(BLOCK_BODY),
            objectMapper.readTree(templateService.getById(saved.getId()).getBody()));
    }

    @Test
    void rejectsMalformedMarksOnCreateAndUpdateWithNodePathWithoutPersisting() throws Exception {
        mockMvc.perform(post("/api/document-templates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateRequest(MALFORMED_MARKS_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(MALFORMED_MARKS_MESSAGE));
        assertTrue(templateService.getAll().isEmpty());

        DocumentTemplate saved = templateService.create(template(BLOCK_BODY));
        mockMvc.perform(put("/api/document-templates/{id}", saved.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateRequest(MALFORMED_MARKS_BODY)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(MALFORMED_MARKS_MESSAGE));
        assertEquals(objectMapper.readTree(BLOCK_BODY),
            objectMapper.readTree(templateService.getById(saved.getId()).getBody()));
    }

    @Test
    void rejectsUnsupportedPlacementsAndMalformedChildren() {
        for (String parent : List.of("heading", "codeBlock", "text", "horizontalRule",
                "mergeToken", "unknown", "bulletList", "orderedList")) {
            String body = "{\"type\":\"doc\",\"content\":[{\"type\":\"" + parent
                + "\",\"content\":[{\"type\":\"lineItems\"}]}]}";
            assertThrows(BadRequestException.class, () -> templateService.create(template(body)), parent);
        }
        for (String body : List.of(
                "null", "{\"type\":\"doc\",\"content\":{}}",
                "{\"type\":\"doc\",\"content\":[null]}",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"listItem\"}]}",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"blockquote\"}]}]}")) {
            assertThrows(BadRequestException.class, () -> templateService.create(template(body)));
        }
        assertTrue(templateService.getAll().isEmpty());
    }

    @Test
    void supportedBlockPositionsFreezeTheSameTotalThatAcceptanceCertifies() throws Exception {
        String body = """
            {"type":"doc","content":[{"type":"blockquote","content":[
              {"type":"bulletList","content":[{"type":"listItem","content":[
                {"type":"paragraph","attrs":{"textAlign":"left"},"content":[
                  {"type":"text","text":"Terms ","marks":[{"type":"bold","attrs":{}}]},
                  {"type":"mergeToken","attrs":{"token":"total"}}]},
                {"type":"lineItems"}]}]}]}]}
            """;
        DocumentFixture fixture = monetaryDocument(body);
        DocumentDeliveryDto delivery = send(fixture, signer("signer@example.test", 1));
        String token = installToken(delivery.recipients().getFirst().id());
        var preview = acceptanceService.preview(link(token), "192.0.2.18");
        assertEquals(0, new BigDecimal("100000").compareTo(preview.content().totals().grandTotal()));
        assertTrue(preview.content().body().toString().contains("USD 100000"));

        assertTrue(acceptanceService.accept(link(token), acceptRequest(token, "Signer"),
            "192.0.2.18", "test-agent").completed());
        var artifacts = deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()).getFirst().artifacts();
        int artifactId = artifacts.stream().filter(artifact -> "signed_document".equals(artifact.kind()))
            .findFirst().orElseThrow().id();
        var stored = deliveryService.downloadArtifact(
            fixture.deal().getId(), fixture.document().id(), delivery.id(), artifactId);
        try (var stream = stored.inputStream()) {
            JsonNode signed = objectMapper.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(preview.content())), signed);
        }
    }

    @Test
    void refusesDeliveryOfPreviouslyStoredInlineLineItems() throws Exception {
        DocumentFixture fixture = monetaryDocument(BLOCK_BODY);
        installLegacyBody(fixture, INLINE_BODY);

        mockMvc.perform(post("/api/deals/{dealId}/documents/{documentId}/delivery",
                    fixture.deal().getId(), fixture.document().id())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "provider", "in_app", "recipients", List.of(signer("signer@example.test", 1))))))
            .andExpect(status().isBadRequest());
        assertTrue(deliveryService.getForDocument(fixture.deal().getId(), fixture.document().id()).isEmpty());
        assertEquals("final", documentService.getOne(fixture.deal().getId(), fixture.document().id()).status());
    }

    @Test
    void refusesDeliveryOfPreviouslyStoredMalformedMarksWithNodePath() throws Exception {
        DocumentFixture fixture = monetaryDocument(BLOCK_BODY);
        installLegacyBody(fixture, MALFORMED_MARKS_BODY);

        mockMvc.perform(post("/api/deals/{dealId}/documents/{documentId}/delivery",
                    fixture.deal().getId(), fixture.document().id())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "provider", "in_app", "recipients", List.of(signer("signer@example.test", 1))))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(MALFORMED_MARKS_MESSAGE));
        assertTrue(deliveryService.getForDocument(fixture.deal().getId(), fixture.document().id()).isEmpty());
        assertEquals("final", documentService.getOne(fixture.deal().getId(), fixture.document().id()).status());
    }

    @ParameterizedTest
    @ValueSource(strings = { INLINE_BODY, MALFORMED_MARKS_BODY })
    void refusesAcceptanceOfPreviouslyDeliveredMalformedBodiesWithoutEvidence(String body) throws Exception {
        DocumentFixture fixture = monetaryDocument(BLOCK_BODY);
        DocumentDeliveryDto delivery = send(fixture, signer("one@example.test", 1), signer("two@example.test", 2));
        String token = installToken(delivery.recipients().getFirst().id());
        installLegacyBody(fixture, body);

        BadRequestException refusal = assertThrows(BadRequestException.class, () -> acceptanceService.accept(
            link(token), acceptRequest(token, "Signer"), "192.0.2.18", "test-agent"));
        if (MALFORMED_MARKS_BODY.equals(body)) {
            assertEquals(MALFORMED_MARKS_MESSAGE, refusal.getMessage());
        }

        DocumentDeliveryDto unchanged = deliveryService.getForDocument(
            fixture.deal().getId(), fixture.document().id()).getFirst();
        assertTrue(unchanged.recipients().stream().allMatch(recipient -> "pending".equals(recipient.status())));
        assertTrue(unchanged.artifacts().isEmpty());
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_delivery_event WHERE workspace_id = ? AND delivery_id = ? AND event_type = 'completed'",
            Integer.class, workspace.getId(), delivery.id()));
        assertEquals(0, jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM document_delivery_recipient
            WHERE workspace_id = ? AND delivery_id = ?
              AND (decided_at IS NOT NULL OR typed_name IS NOT NULL
                   OR evidence_ip_hash IS NOT NULL OR evidence_agent_hash IS NOT NULL)
            """,
            Integer.class, workspace.getId(), delivery.id()));
        assertEquals("sent", documentService.getOne(fixture.deal().getId(), fixture.document().id()).status());
    }

    private DocumentFixture monetaryDocument(String body) throws Exception {
        Pipeline pipeline = newPipeline();
        Deal deal = newDeal(pipeline, newStage(pipeline, 0), newCompany());
        jdbcTemplate.update("UPDATE deal SET currency = 'USD' WHERE workspace_id = ? AND id = ?",
            workspace.getId(), deal.getId());
        DealLineItemRequest line = new DealLineItemRequest();
        line.setName("Services");
        line.setUnitPrice(new BigDecimal("100000"));
        line.setQuantity(BigDecimal.ONE);
        lineItemService.create(deal.getId(), line);
        String saved = mockMvc.perform(post("/api/document-templates")
                .contentType(MediaType.APPLICATION_JSON).content(templateRequest(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int templateId = objectMapper.readTree(saved).path("id").asInt();
        String generated = mockMvc.perform(post("/api/deals/{dealId}/documents", deal.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("templateId", templateId))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int documentId = objectMapper.readTree(generated).path("id").asInt();
        return new DocumentFixture(deal, documentService.updateStatus(deal.getId(), documentId, "final"));
    }

    private void installLegacyBody(DocumentFixture fixture, String body) {
        String stored = Objects.requireNonNull(jdbcTemplate.queryForObject(
            "SELECT content FROM deal_document WHERE workspace_id = ? AND id = ?",
            String.class, workspace.getId(), fixture.document().id()));
        if (!(objectMapper.readTree(stored) instanceof ObjectNode content)) {
            throw new IllegalStateException("Expected frozen document object");
        }
        content.set("body", objectMapper.readTree(body));
        jdbcTemplate.update("UPDATE deal_document SET content = ? WHERE workspace_id = ? AND id = ?",
            objectMapper.writeValueAsString(content), workspace.getId(), fixture.document().id());
    }

    private String templateRequest(String body) {
        return objectMapper.writeValueAsString(Map.of("name", "Monetary template", "type", "quote",
            "locale", "en", "body", body));
    }

    private DocumentTemplate template(String body) {
        DocumentTemplate template = new DocumentTemplate();
        template.setName("Monetary template " + unique());
        template.setType("quote");
        template.setLocale("en");
        template.setBody(body);
        return template;
    }
}
