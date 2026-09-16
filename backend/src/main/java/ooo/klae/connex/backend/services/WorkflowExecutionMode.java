package ooo.klae.connex.backend.services;

import java.util.Locale;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Closed execution identities used to select mandatory workflow authorization paths. */
enum WorkflowExecutionMode {
    USER("user"),
    SYSTEM("system");

    private final String value;

    WorkflowExecutionMode(String value) {
        this.value = value;
    }

    /** Parses the existing normalized wire values before workflow authorization or mutation. */
    static WorkflowExecutionMode parse(String value) {
        if (value == null) {
            throw new BadRequestException("Workflow execution mode must be user or system");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "user" -> USER;
            case "system" -> SYSTEM;
            default -> throw new BadRequestException("Workflow execution mode must be user or system");
        };
    }

    /** Returns the compatible wire and persistence value. */
    String value() {
        return value;
    }
}
