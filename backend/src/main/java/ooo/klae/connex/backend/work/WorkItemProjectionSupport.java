package ooo.klae.connex.backend.work;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;

final class WorkItemProjectionSupport {
    private WorkItemProjectionSupport() {
    }

    static Instant instant(String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new InvalidWorkItemSourceRowsException();
            }
            if (value.contains("T")) {
                return Instant.parse(value);
            }
            return Timestamp.valueOf(value).toLocalDateTime().toInstant(ZoneOffset.UTC);
        } catch (InvalidWorkItemSourceRowsException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidWorkItemSourceRowsException(exception);
        }
    }

    static String etag(String version) {
        return "\"" + version + "\"";
    }
}
