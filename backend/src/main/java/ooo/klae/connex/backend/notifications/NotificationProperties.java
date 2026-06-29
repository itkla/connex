package ooo.klae.connex.backend.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Runtime controls for notification reconciliation and retention.
 */
@Data
@Component
@ConfigurationProperties(prefix = "connex.notifications")
public class NotificationProperties {
    private boolean schedulingEnabled = true;
    private long reconciliationDelayMs = 300_000;
    private long initialDelayMs = 300_000;
    private int overdueBackfillDays = 30;
    private int retentionDays = 90;
    private int maxPageSize = 100;
    private int coolingMinDaysSinceTouch = 14;
    private int coolingBackfillDays = 90;
    private int coolingCloseSoonDays = 14;
}