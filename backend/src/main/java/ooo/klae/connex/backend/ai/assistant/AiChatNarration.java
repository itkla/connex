package ooo.klae.connex.backend.ai.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One narration segment the model wrote alongside a tool call.
 *
 * <p>Narration is what makes a turn read as work rather than a pause: "Let me check this
 * contact…" before the read, "Got it — now their open deals" after it. It streams live and is
 * persisted in the durable answer metadata so a reloaded transcript still shows how the answer was
 * reached. It never re-enters a prompt: replay sends the terminal answer only.
 *
 * @param seq model step the narration accompanied
 * @param text screened, demasked narration prose
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatNarration(int seq, String text) {
}
