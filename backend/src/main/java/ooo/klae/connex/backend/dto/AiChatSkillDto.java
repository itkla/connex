package ooo.klae.connex.backend.dto;

/**
 * The declared Ask Connex skill that produced one answer.
 *
 * <p>Both values are stable machine identifiers the client maps to product language. The plan the
 * skill ran, its tool arguments, and its budgets stay on the server: a viewer learns which job was
 * performed, never how the orchestration performed it.
 *
 * @param key stable catalog key
 * @param version semantic version of the declaration that ran
 */
public record AiChatSkillDto(String key, String version) {
}
