package ooo.klae.connex.backend.ai.assistant;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proxied transaction seams surrounding assistant attachment malware scanning.
 */
@Component
public class AiChatAttachmentTransactions {
    /**
     * Runs the preliminary authorization and capacity snapshot in a read-only transaction.
     *
     * @param work preliminary database work
     * @param <T> snapshot type
     * @return non-null snapshot
     */
    @Transactional(readOnly = true)
    public <T> T readOnly(Supplier<T> work) {
        Supplier<T> required = Objects.requireNonNull(work, "work");
        return Objects.requireNonNull(required.get(), "assistant attachment pre-check result");
    }

    /**
     * Runs the locked final re-check and persistence at the assistant isolation level.
     *
     * @param work final database work
     * @param <T> result type
     * @return non-null persisted result
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T readCommitted(Supplier<T> work) {
        Supplier<T> required = Objects.requireNonNull(work, "work");
        return Objects.requireNonNull(required.get(), "assistant attachment persistence result");
    }
}
