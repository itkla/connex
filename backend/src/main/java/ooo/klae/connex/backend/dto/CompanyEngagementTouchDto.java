package ooo.klae.connex.backend.dto;

/** A company-scoped activity, task, or visible-note touch used by the company card summary. */
public record CompanyEngagementTouchDto(String kind, String touchedAt, Integer userId) {}
