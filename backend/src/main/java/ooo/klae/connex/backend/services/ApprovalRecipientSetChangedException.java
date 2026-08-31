package ooo.klae.connex.backend.services;

final class ApprovalRecipientSetChangedException extends RuntimeException {
    ApprovalRecipientSetChangedException() {
        super("Approval recipients changed during lock acquisition");
    }
}
