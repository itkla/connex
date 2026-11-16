package ooo.klae.connex.backend.dto;

/** The matching restriction fields required to inspect and explicitly remove one contact's block. */
public record ContactSuppressionDto(int id, String channel, String address, String reason) {}
