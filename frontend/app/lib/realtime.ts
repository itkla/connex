import { Client, type IMessage } from "@stomp/stompjs";

import { csrfHeader } from "@/app/lib/api";
import { type Notification } from "@/app/lib/types";

const NOTIFICATIONS_QUEUE = "/user/queue/notifications";
const RECONNECT_DELAY_MS = 5_000;
const HEARTBEAT_MS = 10_000;

/** Connection state of the realtime notification socket. */
export type RealtimeStatus = "connecting" | "connected" | "disconnected";

/**
 * A frame pushed by the backend on the recipient's per-user queue. `created`
 * carries a brand-new notification worth surfacing, `updated` a materially
 * changed one worth a silent refresh, and `counts` a recomputed unread total.
 */
export type RealtimeNotificationFrame = {
    kind: "created" | "updated" | "counts";
    notification?: Notification | null;
    dedupeKey?: string | null;
    unread?: number | null;
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
    if (kind !== "created" && kind !== "updated" && kind !== "counts") {
        return null;
    }
    return parsed as RealtimeNotificationFrame;
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
            client.subscribe(NOTIFICATIONS_QUEUE, (message) => {
                const frame = parseFrame(message);
                if (frame) {
                    handlers.onFrame(frame);
                }
            });
            handlers.onStatusChange?.("connected");
        },
        onWebSocketClose: () => handlers.onStatusChange?.("disconnected"),
        onStompError: () => handlers.onStatusChange?.("disconnected"),
    });

    return {
        activate: () => {
            handlers.onStatusChange?.("connecting");
            client.activate();
        },
        deactivate: () => {
            void client.deactivate();
        },
    };
}
