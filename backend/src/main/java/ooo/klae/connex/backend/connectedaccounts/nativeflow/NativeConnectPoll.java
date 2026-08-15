package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import ooo.klae.connex.backend.beans.NativeConnectSession;

/** Self-scoped pairing status and whether this poll atomically expired it. */
record NativeConnectPoll(
    NativeConnectSession session,
    boolean expiredTransition
) {
}
