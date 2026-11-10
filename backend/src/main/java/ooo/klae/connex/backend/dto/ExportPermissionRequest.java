package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;

/** Explicitly enables or withdraws export access for one built-in role. */
public record ExportPermissionRequest(@NotNull Boolean enabled) { }
