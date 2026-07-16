package ooo.klae.connex.backend.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Canonical member ownership scope shared by workspace-scoped record reads.
 *
 * @param mode ownership mode
 * @param userId server-resolved current user for {@link Mode#ME}
 * @param memberIds selected active workspace member ids for {@link Mode#MEMBERS}
 */
public record MemberScope(Mode mode, Integer userId, List<Integer> memberIds) {
    private static final int MAX_MEMBER_IDS = 50;

    /** Supported ownership modes for member-scoped record reads. */
    public enum Mode {
        ALL_TEAM,
        ME,
        MEMBERS,
        UNASSIGNED
    }

    /**
     * Creates a validated immutable scope.
     *
     * @param mode ownership mode
     * @param userId current user id for the {@code ME} mode
     * @param memberIds selected member ids for the {@code MEMBERS} mode
     */
    public MemberScope {
        Objects.requireNonNull(mode, "mode");
        memberIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds"));
        switch (mode) {
            case ALL_TEAM, UNASSIGNED -> {
                if (userId != null || !memberIds.isEmpty()) {
                    throw new IllegalArgumentException("This member scope mode cannot contain user ids");
                }
            }
            case ME -> {
                if (userId == null || userId < 1 || !memberIds.isEmpty()) {
                    throw new IllegalArgumentException("The me scope requires one server-resolved user id");
                }
            }
            case MEMBERS -> {
                if (userId != null || memberIds.isEmpty() || memberIds.size() > MAX_MEMBER_IDS
                        || memberIds.stream().anyMatch(id -> id == null || id < 1)) {
                    throw new IllegalArgumentException("The members scope requires between 1 and 50 positive member ids");
                }
            }
        }
    }

    /**
     * Resolves and validates the request-level scope syntax without trusting a client id for {@code ME}.
     *
     * @param scope raw request scope
     * @param memberIds raw selected member ids
     * @param currentUserId authenticated current user id
     * @return canonical member scope
     */
    public static MemberScope fromRequest(String scope, List<Integer> memberIds, int currentUserId) {
        String normalizedScope = scope == null ? "" : scope.trim();
        return switch (normalizedScope) {
            case "" -> new MemberScope(Mode.ALL_TEAM, null, List.of());
            case "me" -> new MemberScope(Mode.ME, currentUserId, List.of());
            case "members" -> members(memberIds);
            case "unassigned" -> new MemberScope(Mode.UNASSIGNED, null, List.of());
            default -> throw new BadRequestException(
                "scope must be blank or one of: me, members, unassigned");
        };
    }

    private static MemberScope members(List<Integer> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            throw new BadRequestException("memberIds are required when scope=members");
        }
        if (memberIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new BadRequestException("memberIds values must be positive integers");
        }
        List<Integer> distinctIds = List.copyOf(new LinkedHashSet<>(memberIds));
        if (distinctIds.size() > MAX_MEMBER_IDS) {
            throw new BadRequestException("memberIds accepts at most 50 values");
        }
        return new MemberScope(Mode.MEMBERS, null, distinctIds);
    }
}
