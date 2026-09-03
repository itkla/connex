package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.json.JsonMapper;
import ooo.klae.connex.backend.beans.Sequence;
import ooo.klae.connex.backend.beans.SequenceVersion;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.sequence.SequenceStepDto;
import ooo.klae.connex.backend.dto.sequence.SequenceStepType;
import ooo.klae.connex.backend.dto.sequence.SequenceVersionDto;
import ooo.klae.connex.backend.mappers.SequenceMapper;
import ooo.klae.connex.backend.mappers.SequenceVersionMapper;

@ExtendWith(MockitoExtension.class)
class SequenceVersionServiceTest {
    @Mock private SequenceVersionMapper versionMapper;
    @Mock private SequenceMapper sequenceMapper;
    @Mock private SequenceService sequenceService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;

    private SequenceVersionService service;
    private Sequence sequence;
    private final List<SequenceVersion> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new SequenceVersionService(
            versionMapper,
            sequenceMapper,
            sequenceService,
            workspaceService,
            authService,
            auditService,
            JsonMapper.builder().findAndAddModules().build());
        User actor = new User();
        actor.setId(9);
        sequence = new Sequence();
        sequence.setId(41);
        sequence.setWorkspaceId(7);
        sequence.setName("First name");
        sequence.setStatus("draft");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(sequenceService.requireVisibleForUpdate(7, 41, 9)).thenReturn(sequence);
        when(sequenceService.loadStepsForShare(7, 41)).thenReturn(steps());
        AtomicInteger next = new AtomicInteger(1);
        when(versionMapper.nextVersionNumberForUpdate(7, 41))
            .thenAnswer(invocation -> next.getAndIncrement());
        when(versionMapper.insertVersion(any())).thenAnswer(invocation -> {
            SequenceVersion version = invocation.getArgument(0);
            version.setId(stored.size() + 1L);
            stored.add(version);
            return 1;
        });
        when(versionMapper.getVersion(any(Integer.class), any(Integer.class), any(Integer.class)))
            .thenAnswer(invocation -> stored.stream()
                .filter(version -> version.getVersionNumber() == (int) invocation.getArgument(2))
                .findFirst().orElse(null));
    }

    @Test
    void republishingCreatesNextVersionWithoutRewritingPriorBytes() {
        SequenceVersionDto first = service.publish(41);
        String firstJson = stored.getFirst().getDefinitionJson();
        byte[] firstHash = stored.getFirst().getDefinitionHash().clone();
        sequence.setName("Unrelated metadata edit");

        SequenceVersionDto second = service.publish(41);

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals(firstJson, stored.getFirst().getDefinitionJson());
        assertArrayEquals(firstHash, stored.getFirst().getDefinitionHash());
        assertEquals(first.definitionHash(), second.definitionHash());
        verify(sequenceService, times(2)).loadStepsForShare(7, 41);
        verify(sequenceMapper, times(2)).markPublished(7, 41, 9);
        verify(versionMapper, times(2)).insertVersionPublisher(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.eq(9));
    }

    @Test
    void getRechecksViewPermissionBeforeLoadingVersionPayload() {
        SequenceVersionDto published = service.publish(41);
        clearInvocations(sequenceService, versionMapper);

        SequenceVersionDto result = service.get(41, published.version());

        assertEquals(published, result);
        InOrder order = inOrder(sequenceService, versionMapper);
        order.verify(sequenceService).requireViewPermission(7, 9);
        order.verify(sequenceService).requireVisible(7, 41, 9);
        order.verify(versionMapper).getVersion(7, 41, published.version());
    }

    private static List<SequenceStepDto> steps() {
        return List.of(new SequenceStepDto(
            0,
            SequenceStepType.SEND_EMAIL,
            0,
            "hours",
            "automatic",
            List.of(new SequenceStepDto.ContentDto(
                "en", "Hello {{person.name}}", "Text", null))));
    }
}
