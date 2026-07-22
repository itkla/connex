package ooo.klae.connex.backend.delivery;

/**
 * A delivery provider that synchronizes an audience to an external marketing destination. The
 * connector receives members that have already passed the export choke point's eligibility re-check
 * and pushes them to the resolved external list, confining every vendor-specific detail to itself.
 */
public interface AudienceSyncConnector extends DeliveryProvider {

    /**
     * Pushes an eligible audience to the connector's external list.
     * @param target the resolved workspace-scoped connector configuration and decrypted credential
     * @param push the external list and eligible members to synchronize
     * @return the connector-reported push outcome
     */
    AudiencePushResult pushAudience(ResolvedDeliveryProvider target, AudiencePush push);
}
