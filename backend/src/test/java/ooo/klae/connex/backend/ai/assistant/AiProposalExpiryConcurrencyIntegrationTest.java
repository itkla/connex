package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.support.MySqlLockWaitProbe;

/** Observes real lock waits before advancing the review clock for every executable branch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiProposalExpiryConcurrencyIntegrationTest extends AbstractAiProposalExpiryIntegrationTest {
    @Autowired SqlSessionTemplate sqlSessions;
    @MockitoSpyBean PersonMapper personSpy;

    static Stream<Arguments> toolsAndKinds() {
        List<Arguments> cases = new ArrayList<>();
        for (String tool : List.of("assign_owner", "add_tag", "remove_tag", "update_record_fields")) {
            for (String kind : List.of("person", "company", "deal")) {
                cases.add(Arguments.of(tool, kind));
            }
        }
        for (String tool : List.of("create_activity", "create_task", "create_note")) {
            for (String kind : List.of("person", "deal")) {
                cases.add(Arguments.of(tool, kind));
            }
        }
        for (String tool : List.of("complete_task", "reschedule_task")) {
            cases.add(Arguments.of(tool, "task"));
        }
        cases.add(Arguments.of("set_response_due", "person"));
        cases.add(Arguments.of("draft_document", "deal"));
        cases.add(Arguments.of("change_deal_stage", "deal"));
        cases.add(Arguments.of("create_person", "company"));
        cases.add(Arguments.of("create_deal", "company"));
        cases.add(Arguments.of("create_company", "workspace"));
        cases.add(Arguments.of("create_report", "workspace"));
        return cases.stream();
    }

    @Test
    void organizationAuthorityWaitCrossingDeadlineRefusesWithoutGenerationChange() throws Exception {
        Proposal proposal = proposal("update_record_fields", "company", Map.of("industry", "Late industry"));
        Long generation = jdbc.queryForObject(
                "SELECT generation FROM ai_assistant_privacy_fence WHERE organization_id=?",
                Long.class, organization.getId());
        assertRefusalAfterWait(proposal, new LockRow("ai_assistant_privacy_fence",
                "SELECT generation FROM ai_assistant_privacy_fence WHERE organization_id=? FOR UPDATE",
                List.of(organization.getId())), false);
        assertEquals(generation, jdbc.queryForObject(
                "SELECT generation FROM ai_assistant_privacy_fence WHERE organization_id=?",
                Long.class, organization.getId()));
    }

    @Test
    void contentionMatrixCoversEveryExecutableToolAndDeclaredTargetBranch() {
        Set<String> actual = toolsAndKinds().map(arguments -> {
            Object[] values = arguments.get();
            return values[0] + ":" + values[1];
        }).collect(Collectors.toSet());
        Set<String> required = AiAssistantToolCatalog.writeToolNames().stream()
                .flatMap(name -> registry.find(name).orElseThrow().acceptedTargetKinds().stream()
                        .map(kind -> name + ":" + kind))
                .collect(Collectors.toSet());
        assertEquals(required, actual);
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("toolsAndKinds")
    void eachDelegateWaitCrossingDeadlineRefuses(String tool, String kind) throws Exception {
        if (tool.startsWith("create_") && List.of("create_person", "create_company", "create_deal").contains(tool)) {
            createGuidedTemplate(tool.substring("create_".length()));
        }
        Proposal proposal = proposal(tool, kind);
        assertRefusalAfterWait(proposal, lastLock(tool, kind), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"date_only", "value_only", "unchanged_value"})
    void eachDealFieldCheckpointRefusesAfterWaitAndAdmitsBeforeDeadline(String fields) throws Exception {
        Map<String, Object> arguments = switch (fields) {
            case "date_only" -> Map.of("expected_close_date", "2030-01-02");
            case "value_only" -> Map.of("value", "2000.00");
            case "unchanged_value" -> Map.of("value", "1000.00");
            default -> throw new IllegalArgumentException(fields);
        };
        Proposal proposal = proposal("update_record_fields", "deal", arguments);
        assertRefusalAfterWait(proposal, byId("deal", deal.getId()), false, false,
                !"unchanged_value".equals(fields));
        assertEquals("value_only".equals(fields) ? "2000.00" : "1000.00",
                jdbc.queryForObject("SELECT value FROM deal WHERE workspace_id = ? AND id = ?",
                        String.class, workspace.getId(), deal.getId()));
        assertEquals("date_only".equals(fields) ? "2030-01-02" : null,
                jdbc.queryForObject("SELECT expected_close_date FROM deal WHERE workspace_id = ? AND id = ?",
                        String.class, workspace.getId(), deal.getId()));
    }

    @Test
    void activityExpiryDefersFailureAuditUntilAuthorityLocksAreReleased() throws Exception {
        assertRefusalAfterWait(proposal("create_activity", "person"), byId("person", person.getId()),
                false, true, true);
        assertEquals(1, activityFailureAudits());
    }

    @Test
    void reportDefinitionWaitCrossingDeadlineRefusesCreate() throws Exception {
        assertRefusalAfterWait(proposal("create_report", "workspace"), lastLock("create_report", "workspace"), false);
    }

    static Stream<Arguments> guidedLocks() {
        List<Arguments> cases = new ArrayList<>();
        for (String kind : List.of("person", "company", "deal")) {
            for (String lock : List.of("template_set", "template_root", "custom_field", "tag")) {
                cases.add(Arguments.of(kind, lock));
            }
        }
        cases.add(Arguments.of("person", "company"));
        cases.add(Arguments.of("person", "person"));
        cases.add(Arguments.of("deal", "company"));
        cases.add(Arguments.of("deal", "stage"));
        cases.add(Arguments.of("deal", "pipeline"));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}/{1}")
    @MethodSource("guidedLocks")
    void guidedCreationWaitCrossingDeadlineRefusesInsert(String kind, String prerequisite) throws Exception {
        createGuidedTemplate(kind);
        Proposal proposal = proposal("create_" + kind, "company".equals(kind) ? "workspace" : "company");
        LockRow row = switch (prerequisite) {
            case "template_set" -> new LockRow("record_creation_template_set",
                    "SELECT workspace_id FROM record_creation_template_set WHERE workspace_id = ? AND record_type = ? FOR UPDATE",
                    List.of(workspace.getId(), kind));
            case "template_root" -> byId("record_creation_template", templateIds.get(kind));
            case "custom_field" -> byId("custom_field_definition", customFieldIds.get(kind));
            case "tag" -> byId("tag", tag.getId());
            case "person" -> byId("person", person.getId());
            case "company" -> byId("company", company.getId());
            case "stage" -> byId("stage", nextStage.getId());
            case "pipeline" -> byId("pipeline", pipeline.getId());
            default -> throw new IllegalArgumentException(prerequisite);
        };
        assertRefusalAfterWait(proposal, row, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ai_chat_session", "ai_chat_tool_call"})
    void discardWaitCrossingDeadlineRefuses(String table) throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        assertRefusalAfterWait(proposal, byId(table,
                "ai_chat_session".equals(table) ? proposal.sessionId() : proposal.id()), true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"person", "company", "deal"})
    void tagDefinitionWaitCrossingDeadlineRefuses(String kind) throws Exception {
        assertRefusalAfterWait(proposal("add_tag", kind), byId("tag", tag.getId()), false);
    }

    @Test
    void admittedActionMayCommitAfterDeadline() throws Exception {
        Proposal proposal = proposal("assign_owner", "person");
        now.set(deadline.minus(1, ChronoUnit.MICROS));
        CountDownLatch mutated = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        PersonMapper real = sqlSessions.getMapper(PersonMapper.class);
        doAnswer(invocation -> {
            int result = real.updateOwner(workspace.getId(), person.getId(), other.getId());
            mutated.countDown();
            assertTrue(finish.await(20, TimeUnit.SECONDS));
            return result;
        }).when(personSpy).updateOwner(workspace.getId(), person.getId(), other.getId());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> decision = executor.submit(() -> {
                authenticate(actor);
                try {
                    execute(proposal);
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(mutated.await(15, TimeUnit.SECONDS));
                now.set(deadline.plusSeconds(1));
                finish.countDown();
                decision.get(20, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        assertEquals(other.getId(), jdbc.queryForObject(
                "SELECT owner_id FROM person WHERE workspace_id = ? AND id = ?",
                Integer.class, workspace.getId(), person.getId()));
        assertEquals("executed", chats.getToolCallBySession(workspace.getId(), proposal.sessionId(), proposal.id(), 1L).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"add_tag", "assign_owner", "set_response_due", "complete_task"})
    void successfulNoOpStillRequiresAdmission(String tool) {
        String kind = "complete_task".equals(tool) ? "task" : "person";
        switch (tool) {
            case "add_tag" -> jdbc.update("INSERT INTO person_tag (person_id, tag_id) VALUES (?, ?)", person.getId(), tag.getId());
            case "assign_owner" -> jdbc.update("UPDATE person SET owner_id = ?, updated_at = ? WHERE id = ?",
                    other.getId(), dbTime(createdAt.minusSeconds(3600)), person.getId());
            case "set_response_due" -> jdbc.update("UPDATE person SET first_response_started_at = ?, first_response_due_at = ?, updated_at = ? WHERE id = ?",
                    dbTime(createdAt.minusSeconds(3600)), dbTime(createdAt.plusSeconds(3600)), dbTime(createdAt.minusSeconds(3600)), person.getId());
            case "complete_task" -> jdbc.update("UPDATE task SET completed = TRUE, status = 'done' WHERE id = ?", task.getId());
            default -> throw new IllegalArgumentException(tool);
        }
        Proposal proposal = proposal(tool, kind);
        var before = domainSnapshot();
        now.set(deadline);
        assertThrows(AiAssistantProposalExpiredException.class, () -> execute(proposal));
        assertEquals(before, domainSnapshot());
        assertPending(proposal);
        now.set(originalNow);
        execute(proposal);
        assertEquals("executed", chats.getToolCallBySession(workspace.getId(), proposal.sessionId(), proposal.id(), 1L).getStatus());
    }

    private void assertRefusalAfterWait(Proposal proposal, LockRow row, boolean discard) throws Exception {
        assertRefusalAfterWait(proposal, row, discard, false, true);
    }

    private void assertRefusalAfterWait(Proposal proposal, LockRow row, boolean discard,
            boolean queueActorWriter, boolean changesDomain) throws Exception {
        var before = domainSnapshot();
        CountDownLatch connected = new CountDownLatch(1);
        AtomicLong waitingConnection = new AtomicLong();
        try (Connection holder = Objects.requireNonNull(jdbc.getDataSource()).getConnection();
                var executor = Executors.newFixedThreadPool(2)) {
            holder.setAutoCommit(false);
            long holdingConnection;
            try (var statement = holder.createStatement(); var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(result.next());
                holdingConnection = result.getLong(1);
            }
            try (PreparedStatement statement = holder.prepareStatement(row.sql())) {
                for (int index = 0; index < row.arguments().size(); index++) {
                    statement.setObject(index + 1, row.arguments().get(index));
                }
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next(), "The prerequisite lock fixture must exist: " + row.table());
                }
            }
            Future<?> decision = executor.submit(() -> {
                authenticate(actor);
                try {
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                    transaction.executeWithoutResult(status -> {
                        waitingConnection.set(Objects.requireNonNull(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
                        connected.countDown();
                        if (discard) {
                            writes.reject(proposal.sessionId(), proposal.id());
                        } else {
                            try {
                                execute(proposal);
                            } catch (AiAssistantProposalExpiredException refusal) {
                                if (queueActorWriter) {
                                    assertEquals(0, activityFailureAudits(),
                                            "The failure audit must wait for the decision transaction to complete");
                                }
                                throw refusal;
                            }
                        }
                    });
                } finally {
                    clearAuthentication();
                }
            });
            try {
                assertTrue(connected.await(15, TimeUnit.SECONDS));
                awaitWait(waitingConnection.get(), holdingConnection, row.table(), decision);
                assertFalse(decision.isDone());
                Future<?> actorWriter = null;
                if (queueActorWriter) {
                    CountDownLatch writerConnected = new CountDownLatch(1);
                    AtomicLong writerConnection = new AtomicLong();
                    actorWriter = executor.submit(() -> {
                        updateActor(writerConnection, writerConnected);
                        return null;
                    });
                    assertTrue(writerConnected.await(15, TimeUnit.SECONDS));
                    MySqlLockWaitProbe.awaitExclusiveRecordLock(jdbc, writerConnection.get(),
                            "app_user", Integer.toString(actor.getId()));
                    awaitWait(writerConnection.get(), waitingConnection.get(), "app_user", actorWriter);
                }
                now.set(deadline);
                holder.commit();
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> decision.get(20, TimeUnit.SECONDS));
                assertInstanceOf(AiAssistantProposalExpiredException.class, failure.getCause());
                if (actorWriter != null) {
                    actorWriter.get(20, TimeUnit.SECONDS);
                }
            } finally {
                holder.rollback();
            }
        }
        assertEquals(before, domainSnapshot(), "No domain data or success audit may commit after refusal");
        assertPending(proposal);
        now.set(originalNow);
        if (discard) {
            assertEquals("rejected", writes.reject(proposal.sessionId(), proposal.id()).status());
            assertEquals(before, domainSnapshot());
        } else {
            execute(proposal);
            assertEquals("executed", chats.getToolCallBySession(workspace.getId(), proposal.sessionId(), proposal.id(), 1L).getStatus());
            if (changesDomain) {
                assertNotEquals(before, domainSnapshot(), "The before-deadline control must perform the intended action");
            } else {
                assertEquals(before, domainSnapshot(), "An admitted no-op must preserve domain state");
            }
        }
    }

    private int activityFailureAudits() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log
                WHERE workspace_id = ? AND actor_id = ? AND action = 'activity.create' AND outcome = 'failure'
                """, Integer.class, workspace.getId(), actor.getId()));
    }

    private void updateActor(AtomicLong connectionId, CountDownLatch connected) throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (var session = connection.createStatement()) {
                try (var result = session.executeQuery("SELECT CONNECTION_ID()")) {
                    assertTrue(result.next());
                    connectionId.set(result.getLong(1));
                }
                session.execute("SET SESSION innodb_lock_wait_timeout = 10");
                connected.countDown();
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE app_user SET timezone = timezone WHERE id = ?")) {
                    statement.setInt(1, actor.getId());
                    assertEquals(1, statement.executeUpdate());
                }
                connection.commit();
            } finally {
                connection.rollback();
                try (var reset = connection.createStatement()) {
                    reset.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                }
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private void awaitWait(long waiting, long holding, String table, Future<?> decision) throws Exception {
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < expires) {
            if (decision.isDone()) {
                decision.get();
                throw new AssertionError("Decision completed without waiting on " + table);
            }
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE = waits.ENGINE AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.threads requester ON requester.THREAD_ID = waits.REQUESTING_THREAD_ID
                    JOIN performance_schema.threads blocker ON blocker.THREAD_ID = waits.BLOCKING_THREAD_ID
                    WHERE requester.PROCESSLIST_ID = ? AND blocker.PROCESSLIST_ID = ?
                      AND requested.OBJECT_SCHEMA = DATABASE() AND requested.OBJECT_NAME = ?
                      AND requested.LOCK_STATUS = 'WAITING' AND requested.LOCK_TYPE = 'RECORD'
                    """, Integer.class, waiting, holding, table);
            if (count != null && count > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Decision did not wait on its held " + table + " prerequisite");
    }

    private LockRow lastLock(String tool, String kind) {
        return switch (tool) {
            case "add_tag", "remove_tag" -> new LockRow(kind + "_tag",
                    "SELECT tag_id FROM " + kind + "_tag WHERE " + kind + "_id = ? AND tag_id = ? FOR UPDATE",
                    List.of(targetId(kind), existingTag.getId()));
            case "assign_owner" -> "deal".equals(kind)
                    ? new LockRow("deal_collaborator", "SELECT user_id FROM deal_collaborator WHERE workspace_id = ? AND deal_id = ? AND user_id = ? FOR UPDATE",
                            List.of(workspace.getId(), deal.getId(), other.getId()))
                    : byId(kind, targetId(kind));
            case "complete_task" -> byId("task", sibling.getId());
            case "set_response_due" -> new LockRow("person_lifecycle_pass",
                    "SELECT id FROM person_lifecycle_pass WHERE workspace_id = ? AND person_id = ? AND ended_at IS NULL FOR UPDATE",
                    List.of(workspace.getId(), person.getId()));
            case "create_company" -> byId("record_creation_template", templateIds.get("company"));
            case "create_person", "create_deal" -> byId("company", company.getId());
            case "create_report" -> new LockRow("report_definition",
                    "SELECT id FROM report_definition WHERE workspace_id = ? FOR UPDATE", List.of(workspace.getId()));
            default -> byId(kind, targetId(kind));
        };
    }

    private LockRow byId(String table, int id) {
        return new LockRow(table, "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE", List.of(id));
    }

    private record LockRow(String table, String sql, List<Object> arguments) {
    }
}
