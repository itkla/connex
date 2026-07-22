package ooo.klae.connex.backend.dto;

/**
 * One grouped count returned by the campaign engagement mapper. The key carries the
 * grouped dimension value (a delivery status, a skip reason, an event type, or a channel)
 * and the count is the number of rows in that group.
 * @param keyValue grouped dimension value
 * @param countValue number of rows in the group
 */
public record CampaignEngagementCountRow(String keyValue, int countValue) {
}
