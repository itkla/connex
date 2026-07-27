package ooo.klae.connex.backend.seeder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Closes a successful non-web seeder context after all startup runners complete.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
@Profile("seeder")
@ConditionalOnProperty(prefix = "connex.seeder", name = "enabled", havingValue = "true")
public class SeederExitListener implements ApplicationListener<ApplicationReadyEvent> {

    private final ApplicationContext applicationContext;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Seeder application is ready; closing the one-shot context");
        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        if (exitCode != 0) {
            throw new IllegalStateException("Seeder context returned non-zero exit code " + exitCode);
        }
    }
}
