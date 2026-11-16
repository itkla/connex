package ooo.klae.connex.backend.dto;

/** Reference to one bounded company candidate group and its explicit source-row selection. */
public record ImportCompanyDependency(
        String name,
        String key,
        Integer selectedId,
        boolean truncated) {
}
