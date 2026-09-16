package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Verifies startup on the existing schema after the committed activation fixtures are cleaned. */
@SpringBootTest(properties = {
    "connex.workflows.runtime.enabled=false",
    "connex.workflows.runtime.scheduling-enabled=false",
    "connex.rules.scheduling-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class WorkflowStartupAfterActivationIntegrationTest {

    @Autowired private ConfigurableApplicationContext context;
    @MockitoSpyBean private LegacyWorkflowBackfillRunner backfillRunner;

    @Test
    void startupBackfillSucceedsAfterCommittedActivationFixturesAreRemoved() {
        assertTrue(context.isActive());
        verify(backfillRunner).run(isA(ApplicationArguments.class));
    }
}
