/** The owner and collaborators a deal-team dialog opened with, or the draft the user is about to save. */
export type DealTeamDraft = {
    ownerId: number | null;
    collaboratorIds: number[];
};

/**
 * The writes a deal-team save actually needs. The owner is never also a collaborator, so the draft's
 * collaborators are normalised against the drafted owner before being compared. Either half is
 * omitted when it already matches what the dialog opened with, so an untouched save costs no request.
 */
export type DealTeamWrites = {
    owner: boolean;
    collaboratorIds: number[] | null;
};

function sameMembership(left: readonly number[], right: readonly number[]): boolean {
    if (left.length !== right.length) return false;
    const present = new Set(left);
    return right.every((id) => present.has(id));
}

/**
 * Compares a deal-team draft against the state it opened with and reports which writes remain.
 */
export function pendingDealTeamWrites(initial: DealTeamDraft, draft: DealTeamDraft): DealTeamWrites {
    const nextCollaboratorIds = [...new Set(draft.collaboratorIds)].filter((id) => id !== draft.ownerId);
    const initialCollaboratorIds = [...new Set(initial.collaboratorIds)].filter((id) => id !== initial.ownerId);
    return {
        owner: draft.ownerId !== initial.ownerId,
        collaboratorIds: sameMembership(initialCollaboratorIds, nextCollaboratorIds) ? null : nextCollaboratorIds,
    };
}
