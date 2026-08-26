package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import ooo.klae.connex.backend.beans.AiWorkspaceGovernance;

class AiWorkspaceGovernanceMapperTest extends AbstractMapperTest {
    @Autowired private AiWorkspaceGovernanceMapper governanceMapper;

    @Test
    void settingsRoundTripAndRemainBounded() {
        assertNull(governanceMapper.get(workspace.getId()));

        assertEquals(1, governanceMapper.upsert(workspace.getId(), true, 5));
        AiWorkspaceGovernance enabled = governanceMapper.get(workspace.getId());
        assertTrue(enabled.isAiEnabled());
        assertEquals(5, enabled.getAssistantMaxSteps());

        assertEquals(2, governanceMapper.upsert(workspace.getId(), false, 2));
        AiWorkspaceGovernance disabled = governanceMapper.get(workspace.getId());
        assertFalse(disabled.isAiEnabled());
        assertEquals(2, disabled.getAssistantMaxSteps());

        assertEquals(2, governanceMapper.upsert(workspace.getId(), true, 48));
        assertEquals(48, governanceMapper.get(workspace.getId()).getAssistantMaxSteps());

        assertThrows(
                DataAccessException.class,
                () -> governanceMapper.upsert(workspace.getId(), true, 49));
    }
}
