package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.DispatchStatus;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;

@TestPropertySource(properties = "connex.delivery.enabled=true")
@Import(CampaignSendServiceTest.FakeDeliveryConfig.class)
class CampaignSendServiceTest extends AbstractServiceTest {

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private ConsentService consentService;
    @Autowired private SuppressionService suppressionService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private CampaignDispatchService campaignDispatchService;
    @Autowired private DeliveryUnsubscribeService deliveryUnsubscribeService;
    @Autowired private CampaignDeliveryMapper campaignDeliveryMapper;
    @Autowired private FakeDispatcher fakeDispatcher;
    @MockitoBean private DeliveryProviderConfigService deliveryProviderConfigService;

    @BeforeEach
    void resetDelivery() {
        fakeDispatcher.reset();
        lenient().when(deliveryProviderConfigService.isReady(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenReturn(true);
        lenient().when(deliveryProviderConfigService.resolveForWorkspace(anyInt(), eq(DeliveryChannel.EMAIL)))
                .thenAnswer(invocation -> new ResolvedDeliveryProvider(
                        FakeDispatcher.ID, DeliveryChannel.EMAIL, invocation.getArgument(0),
                        DeliveryCredentials.none()));
    }

    @Test
    void createSendMaterializesIncludedMembersOnly() {
        String prefix = "send-" + unique();
        Company company = newCompany();
        Person included = person(company, prefix + "-in", prefix + "-in@example.com");
        person(company, prefix + "-out", prefix + "-out@example.com");
        consentService.setForPerson(included.getId(), grantedConsent());

        int sendId = readySend(prefix).id();

        List<Integer> pending = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), sendId);
        assertEquals(1, pending.size());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), pending.getFirst());
        assertEquals(included.getId(), delivery.getPersonId());
        assertEquals(prefix + "-in@example.com", delivery.getAddress());
        assertEquals(64, delivery.getUnsubscribeToken().length());
    }

    @Test
    void queuingRequiresCampaignSendPermission() {
        String prefix = "rbac-" + unique();
        person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignSendDto send = readySend(prefix);
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertThrows(ForbiddenException.class,
                () -> campaignSendService.queueSend(send.campaignId(), send.id()));
    }

    @Test
    void otherTenantCannotReadOrQueueSend() {
        String prefix = "iso-" + unique();
        person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        CampaignSendDto send = readySend(prefix);
        User other = newUser();
        WorkspaceMembershipDto otherWorkspace = workspaceService.createWorkspace("Tenant B", other.getId());
        authenticateAs(other, otherWorkspace.getId());

        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.getSend(send.campaignId(), send.id()));
        assertThrows(ResourceNotFoundException.class,
                () -> campaignSendService.queueSend(send.campaignId(), send.id()));
    }

    @Test
    void dispatchSendsEachRecipientOnceAndIsIdempotent() {
        String prefix = "dispatch-" + unique();
        Person recipient = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        consentService.setForPerson(recipient.getId(), grantedConsent());
        CampaignSendDto send = readySend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());

        campaignDispatchService.processSend(workspace.getId(), send.id());
        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(1, fakeDispatcher.count());
        CampaignSendDto completed = campaignSendService.getSend(send.campaignId(), send.id());
        assertEquals("completed", completed.status());
        assertEquals(1, completed.dispatchedCount());
    }

    @Test
    void dispatchSkipsSuppressedRecipientWithoutSending() {
        String prefix = "skip-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        consentService.setForPerson(person.getId(), grantedConsent());
        CampaignSendDto send = readySend(prefix);
        campaignSendService.queueSend(send.campaignId(), send.id());
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", "email", person.getEmail(), person.getId(), "manual", null));

        campaignDispatchService.processSend(workspace.getId(), send.id());

        assertEquals(0, fakeDispatcher.count());
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);
        assertEquals("skipped", delivery.getStatus());
        assertEquals("suppressed", delivery.getSkipReason());
    }

    @Test
    void unsubscribeSuppressesRevokesConsentAndIsIdempotent() {
        String prefix = "unsub-" + unique();
        Person person = person(newCompany(), prefix + "-in", prefix + "-in@example.com");
        consentService.setForPerson(person.getId(), grantedConsent());
        CampaignSendDto send = readySend(prefix);
        int deliveryId = campaignDeliveryMapper.pendingDeliveryIds(workspace.getId(), send.id()).getFirst();
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspace.getId(), deliveryId);

        deliveryUnsubscribeService.unsubscribe(delivery.getUnsubscribeToken());
        deliveryUnsubscribeService.unsubscribe(delivery.getUnsubscribeToken());

        assertTrue(campaignDeliveryMapper.hasEvent(workspace.getId(), deliveryId, "unsubscribed"));
        assertEquals(1, suppressionService.list().stream()
                .filter(entry -> "unsubscribe".equals(entry.reason())
                        && entry.address().equals(person.getEmail().toLowerCase()))
                .count());
        assertTrue(consentService.getForPerson(person.getId()).stream()
                .anyMatch(state -> "email".equals(state.channel()) && "revoked".equals(state.status())));
    }

    private CampaignSendDto readySend(String prefix) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Campaign " + prefix, null, "email", null, currentUser.getId(), null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        campaignService.snapshotAudience(campaign.id());
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Message " + prefix, "email"));
        campaignSendService.addRevision(campaign.id(), message.id(), new CampaignMessageRevisionRequest(
                "en", "Hello", "<p>Hi</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>", null));
        CampaignSendDto send = campaignSendService.createSend(
                campaign.id(), new CampaignSendRequest(1, message.id(), 1, null, null));
        assertNotNull(send);
        return send;
    }

    private Person person(Company company, String name, String email) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Marketing contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private CampaignAudienceRequest audience(String namePrefix) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(namePrefix + "-in");
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition);
    }

    private ContactChannelConsentRequest grantedConsent() {
        return new ContactChannelConsentRequest("email", "marketing", "granted", "manual", null, null);
    }

    @TestConfiguration
    static class FakeDeliveryConfig {
        @Bean
        FakeDispatcher fakeDispatcher() {
            return new FakeDispatcher();
        }
    }

    static final class FakeDispatcher implements MessageDispatcher {
        static final String ID = "fake-smtp";
        private final AtomicInteger dispatches = new AtomicInteger();
        private volatile DispatchStatus status = DispatchStatus.SENT;

        void reset() {
            dispatches.set(0);
            status = DispatchStatus.SENT;
        }

        int count() {
            return dispatches.get();
        }

        @Override
        public String providerId() {
            return ID;
        }

        @Override
        public Set<DeliveryChannel> channels() {
            return Set.of(DeliveryChannel.EMAIL);
        }

        @Override
        public DeliveryCapabilities capabilities() {
            return new DeliveryCapabilities(true, false, false, 1);
        }

        @Override
        public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
            dispatches.incrementAndGet();
            return status == DispatchStatus.SENT
                    ? DispatchReceipt.sent("fake-" + dispatches.get(), "ok")
                    : DispatchReceipt.rejected("rejected");
        }
    }
}
