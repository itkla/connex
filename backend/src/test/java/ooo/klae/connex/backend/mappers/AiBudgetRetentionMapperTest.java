package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.ai.AiBudgetControlOperations;
import ooo.klae.connex.backend.beans.Organization;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AiBudgetRetentionMapperTest {
    @Autowired private AiOrganizationBudgetMapper mapper;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private AiBudgetControlOperations operations;
    @Autowired private SqlSessionFactory sqlSessionFactory;
    @Autowired private DataSource dataSource;

    @Test
    void retentionPurgesOnlyOldSettledRowsInBatchesAndPreservesTheUsageLedger() {
        int orgId = newOrganization();
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).plusDays(8);
        LocalDate day = cutoff.toLocalDate();
        mapper.upsert(orgId, 1_000);
        mapper.ensureUsage(orgId, day);
        mapper.addConsumedTokens(orgId, day, 105);
        List<String> oldIds = new ArrayList<>();
        for (int index = 0; index < 105; index++) {
            oldIds.add(reservation(orgId, day, cutoff.minusDays(1), "settled"));
        }
        String boundary = reservation(orgId, day, cutoff, "settled");
        String recent = reservation(orgId, day, cutoff.plusDays(1), "settled");
        String reserved = reservation(orgId, day, cutoff.minusDays(1), "reserved");
        String dispatched = reservation(orgId, day, cutoff.minusDays(1), "dispatched");

        assertEquals(100, mapper.deleteSettledReservationsBefore(cutoff));
        assertEquals(5, oldIds.stream().filter(id -> mapper.getReservation(id) != null).count());
        assertEquals(5, mapper.deleteSettledReservationsBefore(cutoff));
        assertEquals(0, mapper.deleteSettledReservationsBefore(cutoff));

        oldIds.forEach(id -> assertNull(mapper.getReservation(id)));
        assertNotNull(mapper.getReservation(boundary));
        assertNotNull(mapper.getReservation(recent));
        assertNotNull(mapper.getReservation(reserved));
        assertNotNull(mapper.getReservation(dispatched));
        assertEquals(105, mapper.getConsumedTokens(orgId, day));
        AiBudgetControlOperations.Snapshot snapshot = operations.snapshot(orgId, day, cutoff);
        assertEquals(105, snapshot.usage().getFirst().inputUsage());
        assertEquals("unattributed", snapshot.usage().getFirst().feature());
        assertEquals(0, operations.snapshot(newOrganization(), day, cutoff).consumedTokens());
    }

    @Test
    void recoveringAnOldDispatchStartsAFullSettlementRetentionHorizon() {
        int orgId = newOrganization();
        LocalDateTime beforeSettlement = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        String id = reservation(orgId, beforeSettlement.toLocalDate(), beforeSettlement.minusDays(30),
                "dispatched");

        assertEquals(1, mapper.markReservationSettled(id, 1));

        assertTrue(mapper.getReservation(id).getExpiresAt().isAfter(beforeSettlement));
        mapper.deleteSettledReservationsBefore(beforeSettlement.minusDays(7));
        assertNotNull(mapper.getReservation(id));
    }

    @Test
    void discoveryAndPurgeUseStateLeadingRangesAcrossSettledHistory() {
        int orgId = newOrganization();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).plusDays(8);
        LocalDate day = now.toLocalDate();
        for (int index = 0; index < 1_024; index++) {
            reservation(orgId, day, now.minusDays(1), "settled");
        }
        List<String> expired = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            expired.add(reservation(orgId, day, now.minusHours(1),
                    index % 2 == 0 ? "reserved" : "dispatched"));
        }
        reservation(orgId, day, now.plusDays(1), "reserved");
        reservation(orgId, day, now.plusDays(1), "dispatched");

        List<String> discovered = mapper.listExpiredReservationIds(now);

        assertEquals(100, discovered.size());
        assertTrue(expired.containsAll(discovered));
        assertStateLeadingRange("listExpiredReservationIds", "now", now);
        assertStateLeadingRange("deleteSettledReservationsBefore", "cutoff", now);
        discovered.forEach(mapper::deleteReservation);
        assertEquals(20, mapper.listExpiredReservationIds(now).size());
        assertStateLeadingRange("listExpiredReservationIds", "now", now);
        expired.forEach(mapper::deleteReservation);
        assertTrue(mapper.listExpiredReservationIds(now).isEmpty());
        assertStateLeadingRange("listExpiredReservationIds", "now", now);
    }

    private void assertStateLeadingRange(String statement, String parameter, LocalDateTime value) {
        String sql = sqlSessionFactory.getConfiguration()
                .getMappedStatement(AiOrganizationBudgetMapper.class.getName() + "." + statement)
                .getBoundSql(Map.of(parameter, value)).getSql();
        List<Map<String, Object>> plan = new JdbcTemplate(dataSource)
                .queryForList("EXPLAIN " + sql, Timestamp.valueOf(value));
        assertEquals(1, plan.size());
        assertEquals("range", plan.getFirst().get("type"), () -> statement + ": " + plan);
        assertEquals("idx_organization_ai_budget_reservation_state_expiry", plan.getFirst().get("key"));
        assertFalse(String.valueOf(plan.getFirst().get("Extra")).contains("filesort"));
        List<String> columns = new JdbcTemplate(dataSource).queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organization_ai_budget_reservation'
                  AND INDEX_NAME = 'idx_organization_ai_budget_reservation_state_expiry'
                ORDER BY SEQ_IN_INDEX
                """, String.class);
        assertEquals(List.of("state", "expires_at", "reservation_id"), columns);
    }

    private String reservation(int orgId, LocalDate day, LocalDateTime expiresAt, String state) {
        String id = UUID.randomUUID().toString();
        mapper.insertReservation(id, orgId, day, 1, expiresAt);
        if ("dispatched".equals(state)) mapper.markReservationDispatched(id);
        if ("settled".equals(state)) mapper.markReservationSettled(id, 1);
        return id;
    }

    private int newOrganization() {
        Organization organization = new Organization();
        organization.setName("Budget retention fixture");
        organization.setSlug("budget-retention-" + UUID.randomUUID());
        organizationMapper.insert(organization);
        return organization.getId();
    }
}
