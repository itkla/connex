import { useSyncExternalStore } from "react";
import { browserSupportsWebAuthn } from "@simplewebauthn/browser";

const noopSubscribe = () => () => {};

/**
 * Reports whether the current browser supports the WebAuthn ceremonies, resolved on the
 * client only. Returns false during SSR and the first client render, then the real value
 * after hydration — avoiding both a hydration mismatch and a synchronous effect setState.
 */
export function usePasskeySupport(): boolean {
    return useSyncExternalStore(
        noopSubscribe,
        () => browserSupportsWebAuthn(),
        () => false,
    );
}
