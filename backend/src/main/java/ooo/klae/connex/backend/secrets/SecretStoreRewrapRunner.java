package ooo.klae.connex.backend.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Optional startup batch rewrap for rows encrypted under non-active key ids.
 */
@Component
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class SecretStoreRewrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SecretStoreRewrapRunner.class);

    private final SecretStore secretStore;
    private final SecretStoreProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isBatchRewrapOnStartup()) {
            return;
        }
        int count = secretStore.rewrapBatchToActiveKey(properties.getBatchRewrapLimit());
        log.info("Secret store startup batch rewrapped {} rows to active key {}", count, secretStore.activeKeyId());
    }
}
