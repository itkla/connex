package ooo.klae.connex.backend.dto;

/**
 * One contact selected by the first-response SLA sweep, carrying the display name the breach audit
 * entry needs (#559). The name travels with the id so recording a batch of breaches costs one query
 * rather than one query per contact.
 *
 * @param id contact id
 * @param name contact display name at selection time
 */
public record PersonBreachRow(int id, String name) { }
