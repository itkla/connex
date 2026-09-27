"use client";

import { useEffect } from "react";

import { clearTenantBrowserStorage } from "@/app/lib/browserAccountStorage";

/**
 * Removes record data a session left behind when it ended without an explicit sign-out, such as an
 * expired session or a closed browser. Storage that holds no record data is left untouched.
 *
 * Callers must render this only where the server has confirmed there is no session. A signed-in
 * visitor keeps their own data: the sign-in route can render for one, so it resolves the session
 * server-side first and this sweep is the backstop for the signed-out case alone.
 */
export default function SignedOutStorageCleanup() {
    useEffect(() => clearTenantBrowserStorage(), []);
    return null;
}
