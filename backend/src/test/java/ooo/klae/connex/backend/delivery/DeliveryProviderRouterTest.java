package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DeliveryProviderRouterTest {

    private static final DeliveryCapabilities DISPATCH_CAPS =
            new DeliveryCapabilities(true, false, false, true, 1);
    private static final DeliveryCapabilities SYNC_CAPS =
            new DeliveryCapabilities(false, true, false, false, 0);

    private static final class FakeDispatcher implements MessageDispatcher {
        private final String id;

        private FakeDispatcher(String id) {
            this.id = id;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public Set<DeliveryChannel> channels() {
            return Set.of(DeliveryChannel.EMAIL);
        }

        @Override
        public DeliveryCapabilities capabilities() {
            return DISPATCH_CAPS;
        }

        @Override
        public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
            return DispatchReceipt.sent(null, "ok");
        }
    }

    private static final class FakeSyncOnly implements AudienceSyncConnector {
        @Override
        public String providerId() {
            return "sync";
        }

        @Override
        public Set<DeliveryChannel> channels() {
            return Set.of(DeliveryChannel.EMAIL);
        }

        @Override
        public DeliveryCapabilities capabilities() {
            return SYNC_CAPS;
        }

        @Override
        public AudiencePushResult pushAudience(ResolvedDeliveryProvider target, AudiencePush push) {
            return new AudiencePushResult(push.members().size(), 0, "ok");
        }
    }

    @Test
    void indexesAdaptersByIdAndResolvesThem() {
        FakeDispatcher smtp = new FakeDispatcher("smtp");
        DeliveryProviderRouter router = new DeliveryProviderRouter(List.of(smtp, new FakeSyncOnly()));

        assertSame(smtp, router.adapterFor("smtp"));
        assertSame(smtp, router.dispatcherFor("smtp"));
    }

    @Test
    void rejectsDuplicateProviderIds() {
        assertThrows(IllegalStateException.class, () ->
                new DeliveryProviderRouter(List.of(new FakeDispatcher("smtp"), new FakeDispatcher("smtp"))));
    }

    @Test
    void rejectsBlankProviderId() {
        assertThrows(IllegalStateException.class, () ->
                new DeliveryProviderRouter(List.of(new FakeDispatcher("  "))));
    }

    @Test
    void unknownProviderFailsClosed() {
        DeliveryProviderRouter router = new DeliveryProviderRouter(List.of(new FakeDispatcher("smtp")));
        assertThrows(DeliveryProviderException.class, () -> router.adapterFor("nope"));
        assertThrows(DeliveryProviderException.class, () -> router.dispatcherFor("nope"));
    }

    @Test
    void nonDispatcherProviderCannotBeFetchedAsDispatcher() {
        DeliveryProviderRouter router = new DeliveryProviderRouter(List.of(new FakeSyncOnly()));
        assertEquals("sync", router.adapterFor("sync").providerId());
        assertThrows(DeliveryProviderException.class, () -> router.dispatcherFor("sync"));
    }

    @Test
    void connectorForResolvesAConnectorAndFailsClosedForANonConnector() {
        DeliveryProviderRouter router =
                new DeliveryProviderRouter(List.of(new FakeDispatcher("smtp"), new FakeSyncOnly()));
        assertEquals("sync", router.connectorFor("sync").providerId());
        assertThrows(DeliveryProviderException.class, () -> router.connectorFor("smtp"));
        assertThrows(DeliveryProviderException.class, () -> router.connectorFor("nope"));
    }
}
