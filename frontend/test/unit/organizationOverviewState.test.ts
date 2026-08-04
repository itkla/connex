import { describe, expect, it } from "vitest";

import {
    INITIAL_ORGANIZATION_OVERVIEW_STATE,
    organizationOverviewReducer,
} from "@/app/lib/organizationOverviewState";
import type { OrganizationLayout } from "@/app/lib/types";

function page(orgId: number, workspaceId: number, hasMore = false): OrganizationLayout {
    return {
        organization: {
            id: orgId,
            name: `Organization ${orgId}`,
            slug: `organization-${orgId}`,
            identityVersion: 0,
            updatedAt: "2026-08-04 00:00:00",
        },
        authorityMemberships: [{
            userId: orgId * 10,
            displayName: `Admin ${orgId}`,
            orgRole: "admin",
        }],
        nextAuthorityMemberId: hasMore ? orgId * 10 : null,
        workspaces: [{
            id: workspaceId,
            name: `Workspace ${workspaceId}`,
            slug: `workspace-${workspaceId}`,
            timezone: null,
            rosterVisible: false,
            memberships: [],
            membershipsTruncated: false,
        }],
        nextWorkspaceId: hasMore ? workspaceId : null,
    };
}

describe("organization overview request generations", () => {
    it("discards pagination outcomes after the active organization changes", () => {
        const organizationA = organizationOverviewReducer(
            INITIAL_ORGANIZATION_OVERVIEW_STATE,
            { type: "loadSucceeded", orgId: 1, generation: 0, page: page(1, 11, true) },
        );
        const loadingA = organizationOverviewReducer(
            organizationA,
            { type: "loadMoreStarted", orgId: 1, generation: 0 },
        );
        const organizationB = organizationOverviewReducer(
            loadingA,
            { type: "loadSucceeded", orgId: 2, generation: 0, page: page(2, 21) },
        );

        expect(organizationOverviewReducer(
            organizationB,
            { type: "loadMoreSucceeded", orgId: 1, generation: 0, page: page(1, 12) },
        )).toBe(organizationB);
        expect(organizationOverviewReducer(
            organizationB,
            { type: "loadMoreDenied", orgId: 1, generation: 0 },
        )).toBe(organizationB);
        expect(organizationOverviewReducer(
            organizationB,
            { type: "loadMoreFailed", orgId: 1, generation: 0 },
        )).toBe(organizationB);
        expect(organizationB.loadingMore).toBe(false);
        expect(organizationB.workspaces.map(({ id }) => id)).toEqual([21]);
    });

    it("merges pagination only into the organization that requested it", () => {
        const initial = organizationOverviewReducer(
            INITIAL_ORGANIZATION_OVERVIEW_STATE,
            { type: "loadSucceeded", orgId: 1, generation: 0, page: page(1, 11, true) },
        );
        const loading = organizationOverviewReducer(
            initial,
            { type: "loadMoreStarted", orgId: 1, generation: 0 },
        );
        const merged = organizationOverviewReducer(
            loading,
            { type: "loadMoreSucceeded", orgId: 1, generation: 0, page: page(1, 12) },
        );

        expect(merged.loadingMore).toBe(false);
        expect(merged.workspaces.map(({ id }) => id)).toEqual([11, 12]);
    });

    it("discards a late identity response from the previous organization", () => {
        const organizationB = organizationOverviewReducer(
            INITIAL_ORGANIZATION_OVERVIEW_STATE,
            { type: "loadSucceeded", orgId: 2, generation: 0, page: page(2, 21) },
        );

        expect(organizationOverviewReducer(organizationB, {
            type: "organizationUpdated",
            orgId: 1,
            organization: page(1, 11).organization,
        })).toBe(organizationB);
    });

    it("discards same-organization pagination from before a retry", () => {
        const initial = organizationOverviewReducer(
            INITIAL_ORGANIZATION_OVERVIEW_STATE,
            { type: "loadSucceeded", orgId: 1, generation: 0, page: page(1, 11, true) },
        );
        const loading = organizationOverviewReducer(
            initial,
            { type: "loadMoreStarted", orgId: 1, generation: 0 },
        );
        const retried = organizationOverviewReducer(loading, { type: "retry" });
        const refreshed = organizationOverviewReducer(
            retried,
            { type: "loadSucceeded", orgId: 1, generation: 1, page: page(1, 21) },
        );

        expect(retried.loadingMore).toBe(false);
        expect(organizationOverviewReducer(
            refreshed,
            { type: "loadMoreSucceeded", orgId: 1, generation: 0, page: page(1, 12) },
        )).toBe(refreshed);
        expect(organizationOverviewReducer(
            refreshed,
            { type: "loadFailed", orgId: 1, generation: 0 },
        )).toBe(refreshed);
        expect(refreshed.workspaces.map(({ id }) => id)).toEqual([21]);
    });
});
