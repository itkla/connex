package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DocumentTemplate;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.DealDocumentDto;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRecipientRequest;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.signature.SignatureProperties;

abstract class AbstractDocumentDeliveryServiceTest extends AbstractServiceTest {
    @Autowired protected DealDocumentService documentService;
    @Autowired protected DocumentTemplateService templateService;
    @Autowired protected DocumentDeliveryService deliveryService;
    @Autowired protected DocumentAcceptanceService acceptanceService;
    @Autowired protected SignatureProperties signatureProperties;
    @Autowired protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void enableDocumentSignature() {
        signatureProperties.setEnabled(true);
    }

    @AfterEach
    void restoreDocumentSignatureDefault() {
        signatureProperties.setEnabled(false);
    }

    protected DocumentFixture finalDocument() {
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Company company = newCompany();
        Deal deal = newDeal(pipeline, stage, company);
        DocumentTemplate template = new DocumentTemplate();
        template.setName("Delivery template " + unique());
        template.setType("quote");
        template.setLocale("en");
        template.setTitle("Frozen quote " + unique());
        template.setIntro("Immutable introduction");
        template.setTerms("Immutable terms");
        template.setFooter("Immutable footer");
        DocumentTemplate saved = templateService.create(template);
        DealDocumentDto draft = documentService.generate(deal.getId(), saved.getId());
        DealDocumentDto document = documentService.updateStatus(deal.getId(), draft.id(), "final");
        return new DocumentFixture(deal, document);
    }

    protected DocumentDeliveryDto send(
            DocumentFixture fixture, SendDeliveryRecipientRequest... recipients) {
        SendDeliveryRequest request = new SendDeliveryRequest();
        request.setExpiresAt(LocalDateTime.now().plusDays(7));
        request.setMessage("Please review this document");
        request.setRecipients(List.of(recipients));
        return deliveryService.send(
            fixture.deal().getId(), fixture.document().id(), request, UUID.randomUUID().toString());
    }

    protected SendDeliveryRecipientRequest signer(String email, int order) {
        return new SendDeliveryRecipientRequest(
            null, "Signer " + order, email, "signer", order);
    }

    protected SendDeliveryRecipientRequest viewer(String email, int order) {
        return new SendDeliveryRecipientRequest(
            null, "Viewer " + order, email, "viewer", order);
    }

    protected String installToken(int recipientId) {
        String token = "w" + workspace.getId() + "-"
            + String.format("%064x", recipientId * 7919L + 17L);
        jdbcTemplate.update(
            "UPDATE document_delivery_recipient SET token_hash = ?, token_expires_at = ? "
                + "WHERE workspace_id = ? AND id = ?",
            sha256(token),
            LocalDateTime.now().plusDays(7),
            workspace.getId(),
            recipientId);
        return token;
    }

    protected static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    protected record DocumentFixture(Deal deal, DealDocumentDto document) {
    }
}
