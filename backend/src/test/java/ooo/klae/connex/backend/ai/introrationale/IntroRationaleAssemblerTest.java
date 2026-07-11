package ooo.klae.connex.backend.ai.introrationale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.masking.MaskedMessage;
import ooo.klae.connex.backend.ai.masking.MaskedPrompt;
import ooo.klae.connex.backend.dto.IntroSuggestionDto;

class IntroRationaleAssemblerTest {
    private static final int WORKSPACE_ID = 13;

    private IntroRationaleAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new IntroRationaleAssembler();
    }

    @Test
    void assemble_masksAllIdentifiersAndIncludesDeterministicSignals() {
        IntroSuggestionDto suggestion = new IntroSuggestionDto();
        suggestion.setPersonAId(41);
        suggestion.setPersonAName("Alice Ng");
        suggestion.setPersonATitle("VP Partnerships at Atlas Systems, alice.ng@atlas.example");
        suggestion.setPersonACompany("Atlas Systems");
        suggestion.setPersonAImageUrl("https://cdn.example/alice.png");
        suggestion.setPersonAWarmth("warm");
        suggestion.setPersonBId(73);
        suggestion.setPersonBName("Bob Lee");
        suggestion.setPersonBTitle("Head of Data at Beacon Labs");
        suggestion.setPersonBCompany("Beacon Labs");
        suggestion.setPersonBImageUrl("https://cdn.example/bob.png");
        suggestion.setPersonBWarmth("hot");
        suggestion.setScore(82);
        suggestion.setReasons(List.of("mutual_connections", "shared_company"));
        suggestion.setMutualConnections(3);
        suggestion.setSharedCompany("Atlas Systems");

        IntroRationaleAssembly assembly = assembler.assemble(WORKSPACE_ID, suggestion);
        String serialized = serialized(assembly.prompt());

        assertTrue(serialized.contains("CRM_CONTEXT_BEGIN"));
        assertTrue(serialized.contains("Name: {{P1}}"));
        assertTrue(serialized.contains("Name: {{P2}}"));
        assertTrue(serialized.contains("Company: {{C1}}"));
        assertTrue(serialized.contains("Company: {{C2}}"));
        assertTrue(serialized.contains("Title: VP Partnerships at {{C1}}, [redacted]"));
        assertTrue(serialized.contains("Title: Head of Data at {{C2}}"));
        assertTrue(serialized.contains("Reason codes: mutual_connections, shared_company"));
        assertTrue(serialized.contains("Mutual connections: 3"));
        assertTrue(serialized.contains("Shared company: {{C1}}"));
        assertTrue(serialized.contains("Score: 82"));
        assertTrue(serialized.contains("CRM_CONTEXT_END"));
        assertFalse(serialized.contains("Alice Ng"));
        assertFalse(serialized.contains("Bob Lee"));
        assertFalse(serialized.contains("Atlas Systems"));
        assertFalse(serialized.contains("Beacon Labs"));
        assertFalse(serialized.contains("alice.ng@atlas.example"));
        assertFalse(serialized.contains("https://cdn.example/alice.png"));
        assertFalse(serialized.contains("https://cdn.example/bob.png"));
    }

    private static String serialized(MaskedPrompt prompt) {
        return prompt.getSystemPrompt() + "\n" + prompt.getMessages().stream()
                .map(MaskedMessage::getContent)
                .collect(Collectors.joining("\n"));
    }
}
