package ooo.klae.connex.backend.dto;

import java.util.List;

/** A bounded contact restriction page; nextAfterId is absent on the final page. */
public record ContactSuppressionPageDto(List<ContactSuppressionDto> items, Integer nextAfterId) {}
