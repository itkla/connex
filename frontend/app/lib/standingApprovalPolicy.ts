import { Client } from "@stomp/stompjs";

import { csrfHeader } from "@/app/lib/api";
import type { AiStandingApprovalPolicy } from "@/app/lib/types";

/** Validates finite per-approval ceilings without silently clamping the administrator's choice. */
export function validStandingApprovalCaps(policy: AiStandingApprovalPolicy): boolean {
    return Number.isSafeInteger(policy.maximumDailyCap) && policy.maximumDailyCap >= 1
        && policy.maximumDailyCap <= 10_000 && Number.isSafeInteger(policy.maximumPerRunCap)
        && policy.maximumPerRunCap >= 1 && policy.maximumPerRunCap <= policy.maximumDailyCap;
}

/** Accepts only a content-free invalidation; clients fetch current authorized settings afterward. */
export function isStandingApprovalPolicyInvalidation(value: unknown): boolean {
    return typeof value === "object" && value !== null
        && Reflect.get(value, "kind") === "policy_changed" && Object.keys(value).length === 1;
}

/** Connects the administrative panel only while mounted; reconnects always refresh current policy. */
export function subscribeStandingApprovalPolicy(refresh: () => void): () => void {
    const configured = process.env.NEXT_PUBLIC_WS_URL;
    const { protocol, hostname, host } = window.location;
    const brokerURL = configured || (hostname === "localhost" || hostname === "127.0.0.1"
        ? `ws://${hostname}:8080/api/ws`
        : `${protocol === "https:" ? "wss" : "ws"}://${host}/api/ws`);
    const client = new Client({ brokerURL, reconnectDelay: 5_000,
        heartbeatIncoming: 10_000, heartbeatOutgoing: 10_000 });
    client.beforeConnect = async () => {
        client.connectHeaders = await csrfHeader();
    };
    client.onConnect = () => {
        client.subscribe("/user/queue/ai-standing-approval-policy", (message) => {
            try {
                const value: unknown = JSON.parse(message.body);
                if (isStandingApprovalPolicyInvalidation(value)) refresh();
            } catch {
                return;
            }
        });
        refresh();
    };
    client.activate();
    return () => { void client.deactivate(); };
}
