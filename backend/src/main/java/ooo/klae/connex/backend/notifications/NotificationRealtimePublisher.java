package ooo.klae.connex.backend.notifications;

/**
 * Delivery boundary for realtime notification frames. The in-process
 * implementation targets the local STOMP broker; a cross-instance
 * implementation (e.g. Redis pub/sub) replaces this bean without touching
 * the publishing call sites.
 */
public interface NotificationRealtimePublisher {

    /**
     * Pushes a frame to every live realtime session of the recipient.
     * @param recipientId the recipient user id
     * @param payload the frame to push
     */
    void send(int recipientId, RealtimeNotificationPayload payload);
}
