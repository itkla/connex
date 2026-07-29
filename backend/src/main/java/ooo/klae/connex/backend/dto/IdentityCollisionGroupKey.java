package ooo.klae.connex.backend.dto;

/**
 * Identity of one collision group, without any of its aggregate or provenance metadata.
 * @param recordType person or company
 * @param kind canonical identity kind
 * @param normalizedValue canonical colliding value
 */
public record IdentityCollisionGroupKey(String recordType, String kind, String normalizedValue) {
}
