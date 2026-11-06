package ooo.klae.connex.backend.dto;

/** Stable presentation codes. Diagnostic text and imported values are never presentation copy. */
public record ImportProblem(String code, String field) {

    /** Converts legacy import diagnostics at the response boundary without exposing their values. */
    public static ImportProblem fromDiagnostic(String diagnostic) {
        String code;
        String field = null;
        if (diagnostic.startsWith("'") || diagnostic.startsWith("A value is required for '")
                || diagnostic.startsWith("Invalid option for '")) {
            int start = diagnostic.indexOf('\'');
            int end = diagnostic.lastIndexOf('\'');
            field = end > start ? diagnostic.substring(start + 1, end) : "";
            String validation = end > start ? diagnostic.substring(end + 1) : "";
            if (diagnostic.startsWith("A value is required")) code = "CUSTOM_REQUIRED";
            else if (diagnostic.startsWith("Invalid option for")) code = "CUSTOM_OPTION";
            else if (validation.contains("must be a number")) code = "CUSTOM_NUMBER";
            else if (validation.contains("out of range")) code = "CUSTOM_RANGE";
            else if (validation.contains("must be a date")) code = "CUSTOM_DATE";
            else if (validation.contains("must be true or false")) code = "CUSTOM_BOOLEAN";
            else if (validation.contains("must be a URL")) code = "CUSTOM_URL";
            else code = "CUSTOM_INVALID";
            return new ImportProblem(code, field);
        }
        if (diagnostic.equals("Name is required")) code = "NAME_REQUIRED";
        else if (diagnostic.startsWith("Name exceeds")) code = "NAME_TOO_LONG";
        else if (diagnostic.startsWith("Invalid email:")) code = "EMAIL_INVALID";
        else if (diagnostic.startsWith("Invalid date")) code = "DATE_INVALID";
        else if (diagnostic.startsWith("Invalid Closed at")) code = "CLOSED_AT_INVALID";
        else if (diagnostic.startsWith("Invalid Created at")) code = "CREATED_AT_INVALID";
        else if (diagnostic.contains("line items")) code = "LINE_ITEMS_CONFLICT";
        else if (diagnostic.contains("deal value") || diagnostic.contains("Deal value")) code = "VALUE_INVALID";
        else if (diagnostic.contains("pipeline or stage")) code = "STAGE_UNKNOWN";
        else if (diagnostic.contains("Company dependency candidates were truncated")) code = "COMPANY_TRUNCATED";
        else if (diagnostic.contains("company selection") || diagnostic.contains("companies match the company name")) code = "COMPANY_CHOICE_REQUIRED";
        else if (diagnostic.contains("Company selection is no longer available")) code = "COMPANY_CHOICE_UNAVAILABLE";
        else if (diagnostic.contains("Conflicting company choices")) code = "COMPANY_CHOICES_CONFLICT";
        else if (diagnostic.contains("Company name is invalid")) code = "COMPANY_NAME_INVALID";
        else if (diagnostic.contains("truncated")) code = "CANDIDATES_TRUNCATED";
        else if (diagnostic.startsWith("Linked ") || diagnostic.startsWith("Matched ") && diagnostic.contains("not found")) code = "TARGET_UNAVAILABLE";
        else if (diagnostic.contains("after duplicate review") || diagnostic.contains("no longer uniquely")) code = "IDENTITY_CHANGED";
        else if (diagnostic.contains("shared ")) code = "SHARED_IDENTITY";
        else if (diagnostic.contains("identit") || diagnostic.contains("Canonical ") || diagnostic.contains("multiple matched records")) code = "IDENTITY_CONFLICT";
        else if (diagnostic.contains("Multiple owned deals")) code = "DEAL_CHOICE_REQUIRED";
        else code = "ROW_INVALID";
        return new ImportProblem(code, field);
    }
}
