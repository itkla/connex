package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

/** Registers real scheduling annotations without allowing a timer to mutate shared fixtures. */
class AiAgentMaintenanceSchedulingTest {
    @ParameterizedTest
    @CsvSource({
            "AiAgentPrivacyWorker,10,1,false", "AiAgentPrivacyWorker,3600,1,true",
            "AiAgentPrivacyInitializer,60,60,false", "AiAgentPrivacyInitializer,3600,60,true",
            "AiAgentScheduler,30,5,false", "AiAgentScheduler,3600,5,true"
    })
    void testConfigurationDefersTimersAndPreservesProductionCadence(
            String className, long initialSeconds, long intervalSeconds, boolean testConfiguration) throws Exception {
        var environment = new StandardEnvironment();
        if (testConfiguration) {
            environment.getPropertySources().addFirst(new ResourcePropertySource(
                    "integration-test", new FileSystemResource("src/test/resources/application.properties")));
        }
        Object worker = mock(Class.forName(AiAgentScheduler.class.getPackageName() + "." + className));
        var processor = new ScheduledAnnotationBeanPostProcessor();
        var scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemUTC());
        processor.setScheduler(scheduler);
        processor.setEmbeddedValueResolver(environment::resolveRequiredPlaceholders);
        try {
            processor.postProcessAfterInitialization(worker, className);
            processor.afterSingletonsInstantiated();
            var tasks = processor.getScheduledTasks();
            assertEquals(1, tasks.size());
            var task = assertInstanceOf(FixedDelayTask.class, tasks.iterator().next().getTask());
            assertEquals(Duration.ofSeconds(initialSeconds), task.getInitialDelayDuration());
            assertEquals(Duration.ofSeconds(intervalSeconds), task.getIntervalDuration());
            verifyNoInteractions(worker);
        } finally {
            processor.destroy();
        }
    }
}
