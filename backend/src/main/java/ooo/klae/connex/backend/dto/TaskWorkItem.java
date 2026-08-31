package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.Task;

/** Authoritative task row and the canonical state version captured with it. */
public record TaskWorkItem(Task task, String currentVersion) {
}
