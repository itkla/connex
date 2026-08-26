package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.dto.AiChatAnswerBlockDto;
import ooo.klae.connex.backend.dto.AiChatAnswerDocumentDto;
import ooo.klae.connex.backend.dto.AiChatAnswerRowDto;
import ooo.klae.connex.backend.dto.AiChatCitationDto;
import ooo.klae.connex.backend.dto.AiChatCoverageDto;
import ooo.klae.connex.backend.dto.AiChatProgressItemDto;
import ooo.klae.connex.backend.dto.AiChatQueryScopeDto;
import ooo.klae.connex.backend.dto.AiChatScopeReferenceDto;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the durable answer document and its browser-facing projection against the application-wide
 * {@code non_null} Jackson inclusion. Every earlier test built its own default-{@code ALWAYS}
 * mapper, so none of them could observe that a null block title, a null coverage {@code asOf}, or a
 * null progress {@code count} was being dropped on write — invalidating the strict exact-key-count
 * read on the durable side and, on the wire, turning a declared nullable field into an absent one.
 */
class AiChatAnswerDocumentSerializationTest {

    private final ObjectMapper objectMapper = productionObjectMapper();
    private final AiAssistantPromptAssembler promptAssembler = new AiAssistantPromptAssembler(
            objectMapper, new AiAssistantToolCatalog());
    private final PersonMapper personMapper = mock(PersonMapper.class);
    private final CompanyMapper companyMapper = mock(CompanyMapper.class);
    private final DealMapper dealMapper = mock(DealMapper.class);
    private final PipelineMapper pipelineMapper = mock(PipelineMapper.class);
    private final AiChatMapper chatMapper = mock(AiChatMapper.class);
    private final AiChatCitationProjector projector = new AiChatCitationProjector(
            objectMapper, personMapper, companyMapper, dealMapper, pipelineMapper, chatMapper);

    @Test
    void theProductionMapperRetainsEveryNullTheStrictAnswerDocumentReadRequires() {
        String metadata = promptAssembler.finalMetadata(
                7,
                List.of("r1"),
                List.of(),
                Map.of("r1", new AiChatResourceRegistry.ResourceRef("deal", 19)),
                Map.of("r1", new AiChatRecordObservation(null, null)),
                List.of(
                        new AiAssistantStep.AnswerBlock(
                                "fact", null, "Atlas is active.",
                                List.of(), List.of(), List.of("r1")),
                        new AiAssistantStep.AnswerBlock(
                                "metric", "Pipeline", null, List.of(),
                                List.of(new AiAssistantStep.Row(
                                        "Open pipeline", "12", null, null, List.of("r1"))),
                                List.of("r1"))),
                new AiAssistantStep.Coverage(
                        "partial", null, null, null,
                        List.of("records"), List.of("bounded_results"), true),
                List.of(
                        new AiChatProgressItemDto(0, "scope", "complete", null, false),
                        new AiChatProgressItemDto(1, "records", "complete", 1, false)),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE);

        assertTrue(metadata.contains("\"title\":null"), metadata);
        assertTrue(metadata.contains("\"body\":null"), metadata);
        assertTrue(metadata.contains("\"asOf\":null"), metadata);
        assertTrue(metadata.contains("\"periodStart\":null"), metadata);
        assertTrue(metadata.contains("\"periodEnd\":null"), metadata);
        assertTrue(metadata.contains("\"detail\":null"), metadata);
        assertTrue(metadata.contains("\"at\":null"), metadata);
        assertTrue(metadata.contains("\"count\":null"), metadata);
        assertTrue(metadata.contains("\"observed\":{\"asOf\":null,\"detail\":null}"), metadata);

        AiChatMessage message = new AiChatMessage();
        message.setId(23);
        message.setAuthorKind("assistant");
        message.setContent("Atlas is active.");
        message.setStructuredJson(metadata);
        Deal deal = new Deal();
        deal.setId(19);
        deal.setName("Atlas renewal");
        when(dealMapper.getByIds(3, List.of(19))).thenReturn(List.of(deal));

        List<AiChatCitationDto> citations = projector.project(3, List.of(message))
                .citationsByMessage().get(23);
        AiChatAnswerDocumentDto document = projector.answerDocument(message, citations);

        assertNotNull(document, "answer document was dropped by non_null serialization");
        assertEquals(7, document.turnId());
        assertEquals(2, document.blocks().size());
        assertNull(document.blocks().getFirst().title());
        assertEquals(List.of(citations.getFirst()), document.blocks().getFirst().evidence());
        assertNull(document.blocks().get(1).body());
        assertEquals("Open pipeline", document.blocks().get(1).rows().getFirst().label());
        assertNull(document.blocks().get(1).rows().getFirst().at());
        assertEquals(
                List.of(citations.getFirst()),
                document.blocks().get(1).rows().getFirst().evidence());
        assertNotNull(document.coverage());
        assertEquals("partial", document.coverage().status());
        assertNull(document.coverage().asOf());
        assertEquals(2, document.progress().size());
        assertNull(document.progress().getFirst().count());
    }

    @Test
    void theProductionMapperKeepsEveryBrowserFacingAnswerKeyPresent() {
        AiChatAnswerDocumentDto document = new AiChatAnswerDocumentDto(
                7,
                List.of(new AiChatAnswerBlockDto(
                        "metric", null, null, List.of(),
                        List.of(new AiChatAnswerRowDto(
                                "Open pipeline", null, null, null, List.of())),
                        List.of())),
                new AiChatCoverageDto(
                        "partial", null, null, null, List.of("records"), List.of(), false),
                List.of(new AiChatProgressItemDto(0, "scope", "complete", null, false)));

        String json = objectMapper.writeValueAsString(document);

        assertTrue(json.contains("\"title\":null"), json);
        assertTrue(json.contains("\"body\":null"), json);
        assertTrue(json.contains("\"value\":null"), json);
        assertTrue(json.contains("\"detail\":null"), json);
        assertTrue(json.contains("\"at\":null"), json);
        assertTrue(json.contains("\"asOf\":null"), json);
        assertTrue(json.contains("\"periodStart\":null"), json);
        assertTrue(json.contains("\"periodEnd\":null"), json);
        assertTrue(json.contains("\"count\":null"), json);
    }

    /**
     * The declared skill is durable evaluation metadata and a browser-facing attribution. Under the
     * application-wide {@code non_null} inclusion the whole key would simply vanish for the generic
     * loop, which is correct, but it must survive the round trip when a skill did produce the answer.
     */
    @Test
    void theDeclaredSkillSurvivesTheDurableRoundTripAndItsAbsenceStaysDistinct() {
        String routed = promptAssembler.finalMetadata(
                7,
                List.of("r1"),
                List.of(),
                Map.of("r1", new AiChatResourceRegistry.ResourceRef("deal", 19)),
                Map.of("r1", new AiChatRecordObservation(null, null)),
                List.of(new AiAssistantStep.AnswerBlock(
                        "fact", null, "Atlas is active.",
                        List.of(), List.of(), List.of("r1"))),
                new AiAssistantStep.Coverage(
                        "complete", null, null, null, List.of("records"), List.of(), false),
                List.of(new AiChatProgressItemDto(0, "scope", "complete", null, false)),
                AiAssistantPromptAssembler.ToolBudgetAudit.NONE,
                new AiAssistantPromptAssembler.SkillReference("activity_digest_v1", "1.0.0"));

        assertTrue(
                routed.contains("\"skill\":{\"key\":\"activity_digest_v1\",\"version\":\"1.0.0\"}"),
                routed);

        AiChatMessage message = new AiChatMessage();
        message.setId(23);
        message.setAuthorKind("assistant");
        message.setContent("Atlas is active.");
        message.setStructuredJson(routed);
        Deal deal = new Deal();
        deal.setId(19);
        deal.setName("Atlas renewal");
        when(dealMapper.getByIds(3, List.of(19))).thenReturn(List.of(deal));

        List<AiChatCitationDto> citations = projector.project(3, List.of(message))
                .citationsByMessage().get(23);
        AiChatAnswerDocumentDto document = projector.answerDocument(message, citations);

        assertNotNull(document);
        assertNotNull(document.skill(), "the declared skill was dropped by non_null serialization");
        assertEquals("activity_digest_v1", document.skill().key());
        assertEquals("1.0.0", document.skill().version());
        assertTrue(objectMapper.writeValueAsString(document).contains("\"skill\":"),
                "the browser-facing answer document dropped the skill key");
    }

    /**
     * The interpreted scope is stored as JSON on the turn and read back to restate the same breadth
     * the requester was shown, so every field of it must survive a write-read round trip through the
     * production mapper — including the nullable ones the {@code non_null} inclusion would erase.
     */
    @Test
    void theInterpretedQueryScopeSurvivesAWriteThenReadRoundTrip() {
        AiChatQueryScopeDto scope = new AiChatQueryScopeDto(
                true, "2026-05-26", "2026-08-23", 90, "members",
                List.of(new AiChatScopeReferenceDto(4, "")),
                List.of("cool", "cold"), List.of("company"),
                List.of(new AiChatScopeReferenceDto(3, "")), List.of("open"),
                List.of("meeting"), new AiChatScopeReferenceDto(17, ""),
                null, false, 200, 100, 10, List.of("period_capped"));

        String json = objectMapper.writeValueAsString(scope);
        AiChatQueryScopeDto restored = objectMapper.readValue(json, AiChatQueryScopeDto.class);

        assertTrue(json.contains("\"matchedRecordCount\":null"), json);
        assertTrue(json.contains("\"savedView\":{"), json);
        assertEquals(scope, restored);
        assertNull(restored.matchedRecordCount());
        assertEquals(List.of("cool", "cold"), restored.warmthBands());
        assertEquals(17, restored.savedView().id());
        assertEquals(4, restored.owners().getFirst().id());
        assertEquals(List.of("period_capped"), restored.unavailable());
    }

    private static ObjectMapper productionObjectMapper() {
        String[] jacksonProperties = applicationJacksonProperties();
        assertTrue(
                jacksonProperties.length > 0,
                "application.yml declares no spring.jackson properties");
        AtomicReference<ObjectMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues(jacksonProperties)
                .run(context -> mapper.set(context.getBean(ObjectMapper.class)));
        return assertNotNullMapper(mapper.get());
    }

    private static ObjectMapper assertNotNullMapper(ObjectMapper mapper) {
        assertNotNull(mapper, "the auto-configured production ObjectMapper was unavailable");
        return mapper;
    }

    private static String[] applicationJacksonProperties() {
        List<String> properties = new ArrayList<>();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                    "application", new ClassPathResource("application.yml"))) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("spring.jackson.")) {
                        properties.add(name + "=" + enumerable.getProperty(name));
                    }
                }
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("application.yml could not be read", exception);
        }
        return properties.toArray(String[]::new);
    }
}
