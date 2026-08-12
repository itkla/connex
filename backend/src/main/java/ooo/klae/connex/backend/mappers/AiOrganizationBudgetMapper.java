package ooo.klae.connex.backend.mappers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.AiOrganizationBudget;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetReservation;
import ooo.klae.connex.backend.beans.AiOrganizationBudgetUsage;
import ooo.klae.connex.backend.dto.AiUsageBreakdownDto;

/** Control-plane persistence and row locks for organization AI budgets. */
public interface AiOrganizationBudgetMapper {
    AiOrganizationBudget get(@Param("orgId") int orgId);
    AiOrganizationBudget getForUpdate(@Param("orgId") int orgId);
    int upsert(@Param("orgId") int orgId, @Param("dailyTokenLimit") long dailyTokenLimit);
    int ensureUsage(@Param("orgId") int orgId, @Param("usageDay") LocalDate usageDay);
    AiOrganizationBudgetUsage getUsageForUpdate(
            @Param("orgId") int orgId,
            @Param("usageDay") LocalDate usageDay);
    long getConsumedTokens(@Param("orgId") int orgId, @Param("usageDay") LocalDate usageDay);
    int deleteExpiredReservations(@Param("now") LocalDateTime now);
    long sumReservedTokens(@Param("orgId") int orgId, @Param("usageDay") LocalDate usageDay);
    int insertReservation(
            @Param("reservationId") String reservationId,
            @Param("orgId") int orgId,
            @Param("usageDay") LocalDate usageDay,
            @Param("reservedTokens") long reservedTokens,
            @Param("expiresAt") LocalDateTime expiresAt);
    AiOrganizationBudgetReservation getReservationForUpdate(
            @Param("reservationId") String reservationId);
    int deleteReservation(@Param("reservationId") String reservationId);
    int addConsumedTokens(
            @Param("orgId") int orgId,
            @Param("usageDay") LocalDate usageDay,
            @Param("consumedTokens") long consumedTokens);
    List<AiUsageBreakdownDto> listDailyUsage(
            @Param("orgId") int orgId,
            @Param("usageDay") LocalDate usageDay);
}
