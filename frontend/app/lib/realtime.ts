import { Client, type IMessage } from "@stomp/stompjs";

import { csrfHeader } from "@/app/lib/api";
import { type AiChatRealtimeFrame, type Notification } from "@/app/lib/types";

const NOTIFICATIONS_QUEUE = "/user/queue/notifications";
const AI_CHAT_QUEUE = "/user/queue/ai-chat";
const RECONNECT_DELAY_MS = 5_000;
const HEARTBEAT_MS = 10_000;

/**
 * Close code the backend sends when rejecting a socket that exceeds the per-user
 * connection cap. The client stops reconnecting on it rather than looping every
 * few seconds against a limit it cannot clear (see backend `WebSocketConfig`).
 */
const CONNECTION_LIMIT_CLOSE_CODE = 4029;

/** Connection state of the realtime notification socket. */
export type RealtimeStatus = "connecting" | "connected" | "disconnected";

/**
 * A frame pushed by the backend on the recipient's per-user queue. `created`
 * carries a brand-new notification worth surfacing; `updated` and `invalidated`
 * represent durable inbox changes worth a silent refresh.
 */
export type RealtimeNotificationFrame = {
    kind: "created" | "updated" | "invalidated";
    stateVersion: number;
    notification?: Notification | null;
    dedupeKey?: string | null;
};

/** Callbacks a consumer supplies to react to frames and connection changes. */
export type NotificationSocketHandlers = {
    onFrame: (frame: RealtimeNotificationFrame) => void;
    onStatusChange?: (status: RealtimeStatus) => void;
};

/** Lifecycle controls for a realtime notification socket. */
export type NotificationSocket = {
    activate: () => void;
    deactivate: () => void;
};

/** Callbacks for one authenticated assistant-session realtime stream. */
export type AiChatSocketHandlers = {
    onFrame: (frame: AiChatRealtimeFrame) => void;
    onStatusChange?: (status: RealtimeStatus) => void;
};

/**
 * Resolves the WebSocket endpoint. Next.js rewrites cannot proxy a WebSocket
 * upgrade, so the client connects straight to the backend origin: an explicit
 * `NEXT_PUBLIC_WS_URL` when configured (staging/prod tunnel), the `:8080` dev
 * backend on localhost, or a same-host `/api/ws` path otherwise.
 */
function resolveBrokerUrl(): string {
    const configured = process.env.NEXT_PUBLIC_WS_URL;
    if (configured) {
        return configured;
    }
    const { protocol, hostname, host } = window.location;
    if (hostname === "localhost" || hostname === "127.0.0.1") {
        return `ws://${hostname}:8080/api/ws`;
    }
    return `${protocol === "https:" ? "wss" : "ws"}://${host}/api/ws`;
}

function parseFrame(message: IMessage): RealtimeNotificationFrame | null {
    let parsed: unknown;
    try {
        parsed = JSON.parse(message.body);
    } catch {
        return null;
    }
    if (typeof parsed !== "object" || parsed === null) {
        return null;
    }
    const kind = (parsed as { kind?: unknown }).kind;
    const stateVersion = (parsed as { stateVersion?: unknown }).stateVersion;
    if (kind !== "created" && kind !== "updated" && kind !== "invalidated") {
        return null;
    }
    if (typeof stateVersion !== "number" || !Number.isSafeInteger(stateVersion) || stateVersion < 0) {
        return null;
    }
    return parsed as RealtimeNotificationFrame;
}

function parseAiChatFrame(message: IMessage): AiChatRealtimeFrame | null {
    let parsed: unknown;
    try {
        parsed = JSON.parse(message.body);
    } catch {
        return null;
    }
    if (typeof parsed !== "object" || parsed === null) return null;
    const workspaceId = Reflect.get(parsed, "workspaceId");
    const sessionId = Reflect.get(parsed, "sessionId");
    const turnId = Reflect.get(parsed, "turnId");
    const seq = Reflect.get(parsed, "seq");
    const kind = Reflect.get(parsed, "kind");
    const status = Reflect.get(parsed, "status");
    const tool = Reflect.get(parsed, "tool");
    const reason = Reflect.get(parsed, "reason");
    const numericFields = [workspaceId, sessionId, turnId, seq];
    if (numericFields.some((value) => typeof value !== "number" || !Number.isSafeInteger(value) || value < 0)) {
        return null;
    }
    if (
        kind !== "session"
        && kind !== "message"
        && kind !== "state"
        && kind !== "step"
        && kind !== "terminal"
    ) return null;
    if (typeof status !== "string") return null;
    if (tool !== null && tool !== undefined && typeof tool !== "string") return null;
    if (reason !== null && reason !== undefined && typeof reason !== "string") return null;
    return {
        workspaceId,
        sessionId,
        turnId,
        seq,
        kind,
        tool: typeof tool === "string" ? tool : null,
        status,
        reason: typeof reason === "string" ? reason : null,
    };
}

function createUserQueueSocket(
    queue: string,
    onMessage: (message: IMessage) => void,
    onStatusChange?: (status: RealtimeStatus) => void,
): NotificationSocket {
    if (typeof window === "undefined") {
        return { activate: () => {}, deactivate: () => {} };
    }

    const client = new Client({
        brokerURL: resolveBrokerUrl(),
        reconnectDelay: RECONNECT_DELAY_MS,
        heartbeatIncoming: HEARTBEAT_MS,
        heartbeatOutgoing: HEARTBEAT_MS,
        beforeConnect: async () => {
            client.connectHeaders = await csrfHeader();
        },
        onConnect: () => {
            client.subscribe(queue, onMessage);
            onStatusChange?.("connected");
        },
        onWebSocketClose: (event: CloseEvent) => {
            onStatusChange?.("disconnected");
            if (event.code === CONNECTION_LIMIT_CLOSE_CODE) void client.deactivate();
        },
        onStompError: () => onStatusChange?.("disconnected"),
    });

    return {
        activate: () => {
            onStatusChange?.("connecting");
            client.activate();
        },
        deactivate: () => {
            void client.deactivate();
        },
    };
}

/**
 * Creates a STOMP-over-WebSocket client for realtime notification frames. The
 * socket connects directly to the backend origin, authenticates via the session
 * cookie carried on the handshake, echoes the CSRF token on CONNECT, and
 * auto-subscribes to the recipient's per-user queue. Reconnection and heartbeats
 * are handled by the underlying client; it is a no-op during server rendering.
 * @param handlers frame and connection-state callbacks
 * @returns lifecycle controls for the socket
 */
export function createNotificationSocket(handlers: NotificationSocketHandlers): NotificationSocket {
    return createUserQueueSocket(NOTIFICATIONS_QUEUE, (message) => {
        const frame = parseFrame(message);
        if (frame) handlers.onFrame(frame);
    }, handlers.onStatusChange);
}

/** Creates a metadata-only assistant-session socket on the authenticated user's queue. */
export function createAiChatSocket(handlers: AiChatSocketHandlers): NotificationSocket {
    return createUserQueueSocket(AI_CHAT_QUEUE, (message) => {
        const frame = parseAiChatFrame(message);
        if (frame) handlers.onFrame(frame);
    }, handlers.onStatusChange);
}
