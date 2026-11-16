package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.mappers.PersonLifecyclePassMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;

class LeadResponseSlaAdmissionTest {
    @Test
    void lifecyclePassIdsAboveSignedIntegerRangeStayExactAndSortedBeforeAdmission() {
        PersonMapper people = mock(PersonMapper.class);
        PersonLifecyclePassMapper passes = mock(PersonLifecyclePassMapper.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        MutationAdmission admission = mock(MutationAdmission.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);
        LeadResponseSlaService service = new LeadResponseSlaService(
                people, workspaces, mock(AuditService.class), mock(RuleTriggerPublisher.class),
                passes, mock(NotificationChangePublisher.class), clock);
        Person person = new Person();
        person.setId(31);
        person.setWorkspaceId(7);
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(people.getOwnedPersonByIdForUpdate(7, 31)).thenReturn(person);
        long firstPassId = (long) Integer.MAX_VALUE + 1;
        long secondPassId = firstPassId + 10;
        when(passes.listOpenPassIds(7, 31)).thenReturn(List.of(secondPassId, firstPassId));

        assertFalse(service.startFirstResponseClock(31, 4, admission));

        InOrder order = inOrder(people, passes, admission);
        order.verify(people).getOwnedPersonByIdForUpdate(7, 31);
        order.verify(passes).listOpenPassIds(7, 31);
        order.verify(passes).getOpenPassByIdForUpdate(7, 31, firstPassId);
        order.verify(passes).getOpenPassByIdForUpdate(7, 31, secondPassId);
        order.verify(admission).beforeMutation();
        order.verify(people).startFirstResponseClock(
                eq(7), eq(31), any(LocalDateTime.class), any(LocalDateTime.class));
    }
}
