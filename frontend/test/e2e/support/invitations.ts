import { expect, type APIRequestContext } from "@playwright/test";

import { csrfBootstrap } from "./api";

/** Extracts the invitation bearer without accepting an account-shaped response. */
export function invitationToken(result: unknown): string {
    if (typeof result !== "object" || result === null || !("invite" in result)) {
        throw new Error("Missing invitation result");
    }
    const invite = result.invite;
    if (typeof invite !== "object" || invite === null || !("token" in invite)
        || typeof invite.token !== "string" || !invite.token) {
        throw new Error("Missing invitation token");
    }
    return invite.token;
}

/** Redeems an email invitation through its purpose-bound browser flow and returns the recipient id. */
export async function acceptEmailInvitation(api: APIRequestContext, result: unknown): Promise<number> {
    const csrf = await csrfBootstrap(api);
    const headers = { [csrf.headerName]: csrf.token };
    const exchange = await api.post("/api/invites/exchange", {
        headers, data: { token: invitationToken(result) }, maxRedirects: 0,
    });
    expect(exchange.status(), await exchange.text()).toBe(303);
    const preview = await api.get("/api/invites");
    expect(preview.status(), await preview.text()).toBe(200);
    const flow: unknown = await preview.json();
    if (typeof flow !== "object" || flow === null || !("flowId" in flow) || typeof flow.flowId !== "string") {
        throw new Error("Missing invitation flow id");
    }
    const acceptance = await api.post("/api/invites/accept", { headers, data: { flowId: flow.flowId } });
    expect(acceptance.status(), await acceptance.text()).toBe(200);
    const account = await api.get("/api/auth/me");
    expect(account.status(), await account.text()).toBe(200);
    const recipient: unknown = await account.json();
    if (typeof recipient !== "object" || recipient === null || !("id" in recipient) || typeof recipient.id !== "number") {
        throw new Error("Missing accepted recipient id");
    }
    return recipient.id;
}
