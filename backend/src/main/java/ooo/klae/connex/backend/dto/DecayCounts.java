package ooo.klae.connex.backend.dto;

/**
 * Contact counts by predicted time until reaching the cold band.
 */
public record DecayCounts(long soon, long mid, long later) {}
