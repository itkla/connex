package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.OutboundLeakScan;
import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;
import ooo.klae.connex.backend.ai.provider.AiCompletionRequest;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiAssistantMaskingPipelineTest {
    private final AiAssistantMaskingTestSupport support = new AiAssistantMaskingTestSupport(7, 70);

    @AfterEach
    void releaseFixtureMocks() {
        support.releaseFixtureMocks();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Johnathan [Smith](person:1)", "Johnathan [Smith](record:r9)"})
    void bracketBearingNamesAreScreenedBeforeTheProductionCapAndFirstProviderRequest(String spelling)
            throws Exception {
        Person person = person("Johnathan [Smith]");
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
        ObjectMapper mapper = JsonMapper.builder().build();
        AiAssistantToolResult result = support.scopeResult(person, "x".repeat(498) + spelling, resources, mapper);
        String scope = mapper.writeValueAsString(result.data().get("activities"));
        assertTrue(scope.contains("\"notes\":\"[redacted]\""));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", result)),
                        context, resources,
                        AiAssistantToolCatalog.ALL), 256, 0.1);

        AiCompletionRequest request = support.firstProviderRequest(invocation, mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("[redacted]"));
        assertFalse(input.contains("John"));
        assertFalse(input.contains("Smit"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Ipek Smith|\u0130pek Smith",
            "IRMA Smith|\u0131rma smith",
            "\u0130pek Smith|i\u0307pek Smith",
            "i\u0307pek Smith|\u0130pek Smith"
    })
    void simpleUnicodeCaseVariantsAreScreenedBeforeTheProductionCapAndFirstProviderRequest(
            String name, String spelling) throws Exception {
        Person person = person(name);
        MaskingContext context = new MaskingContext();
        AiChatResourceRegistry resources = new AiChatResourceRegistry(context);
        ObjectMapper mapper = JsonMapper.builder().build();
        String note = "x".repeat(513 - spelling.length()) + spelling;
        AiAssistantToolResult result = support.scopeResult(person, note, resources, mapper);
        String scope = mapper.writeValueAsString(result.data().get("activities"));
        assertTrue(scope.contains("\"notes\":\"[redacted]\""));
        AiInvocation invocation = new AiInvocation(AiFeature.ASSISTANT_CHAT, context,
                new AiAssistantPromptAssembler(mapper, new AiAssistantToolCatalog()).assemble(
                        List.of(), new AiAssistantToolResult(Map.of(), List.of()),
                        List.of(AiAssistantPromptAssembler.ToolTurn.soleCall(1, "scope_activities", result)),
                        context, resources,
                        AiAssistantToolCatalog.ALL), 256, 0.1);

        AiCompletionRequest request = support.firstProviderRequest(invocation, mapper);
        String input = mapper.writeValueAsString(request.messages());

        assertTrue(input.contains("[redacted]"));
        assertFalse(input.contains(spelling.substring(0, 4)));
        assertFalse(input.contains("Smith"));
        assertFalse(input.contains("smith"));
        OutboundLeakScan.assertNoLeakStrict(input, context, mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8085551", "80855512"})
    void emailPhoneSuffixesCannotReachTheFirstProviderRequest(String suffix) throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        AiCompletionRequest request = support.firstProviderRequest(
                support.invocation("Contact alice@example.com" + suffix, new MaskingContext(), mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("alice@example.com"));
        assertFalse(providerInput.contains(suffix));
        assertTrue(providerInput.contains("Contact [redacted]"));
    }

    @Test
    void explicitlySeededFullwidthDelimiterIdentifierCannotReachTheFirstProviderRequest() throws Exception {
        String name = "John\uFF5B\uFF5B\uFF5D\uFF5Dathan Smith";
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, name, context);
        ObjectMapper mapper = JsonMapper.builder().build();

        AiCompletionRequest request = support.firstProviderRequest(support.invocation("Ask " + name + " today.", context, mapper), mapper);
        String providerInput = mapper.writeValueAsString(request.messages());

        assertFalse(providerInput.contains("Johnathan"));
        assertTrue(providerInput.contains("Ask {{P1}} today."));
    }

    private static Person person(String name) {
        Company company = new Company();
        company.setId(5);
        company.setWorkspaceId(7);
        company.setName("Fixture Parent");
        Person person = new Person();
        person.setId(17);
        person.setWorkspaceId(7);
        person.setCompany(company);
        person.setName(name);
        return person;
    }
}
