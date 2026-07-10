package ooo.klae.connex.backend.ai.masking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lombok.Getter;

/**
 * Provider prompt assembled after CRM content has crossed the masking boundary. The constructor and
 * builder are package-private so code outside {@code ai.masking} cannot fabricate a prompt from raw
 * CRM text and hand it to a provider path.
 */
@Getter
public final class MaskedPrompt {
    private final String systemPrompt;
    private final List<MaskedMessage> messages;

    MaskedPrompt(String systemPrompt, List<MaskedMessage> messages) {
        this.systemPrompt = systemPrompt;
        this.messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private String systemPrompt;
        private final List<MaskedMessage> messages = new ArrayList<>();

        Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        Builder addMessage(String role, String content) {
            messages.add(new MaskedMessage(role, content));
            return this;
        }

        MaskedPrompt build() {
            return new MaskedPrompt(systemPrompt, messages);
        }
    }
}
