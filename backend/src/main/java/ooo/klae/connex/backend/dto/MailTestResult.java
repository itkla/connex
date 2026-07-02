package ooo.klae.connex.backend.dto;

/**
 * Outcome of a "send test email" attempt: whether it succeeded and, if not, the
 * transport error to show the admin.
 */
public record MailTestResult(boolean success, String error) {

    /**
     * A successful result.
     * @return a result with success=true and no error
     */
    public static MailTestResult ok() {
        return new MailTestResult(true, null);
    }

    /**
     * A failed result carrying the error message.
     * @param error the transport error
     * @return a result with success=false
     */
    public static MailTestResult failure(String error) {
        return new MailTestResult(false, error);
    }
}
