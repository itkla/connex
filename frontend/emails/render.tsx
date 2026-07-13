import * as React from "react";
import { render } from "@react-email/render";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { mkdirSync, writeFileSync } from "node:fs";

import Invite from "./templates/Invite.js";
import PasswordReset from "./templates/PasswordReset.js";
import EmailChange from "./templates/EmailChange.js";
import Test from "./templates/Test.js";
import NotificationEmail from "./templates/NotificationEmail.js";
import ReportDelivery from "./templates/ReportDelivery.js";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, "..", "..", "backend", "src", "main", "resources", "templates", "emails");

const templates: Array<{ name: string; element: React.ReactElement }> = [
    { name: "invite.en.html", element: <Invite /> },
    { name: "password-reset.en.html", element: <PasswordReset /> },
    { name: "email-change.en.html", element: <EmailChange /> },
    { name: "test.en.html", element: <Test /> },
    { name: "notification.en.html", element: <NotificationEmail /> },
    { name: "report-delivery.en.html", element: <ReportDelivery locale="en" /> },
    { name: "report-delivery.ja.html", element: <ReportDelivery locale="ja" /> },
];

mkdirSync(outDir, { recursive: true });

for (const { name, element } of templates) {
    const html = await render(element, { pretty: true });
    writeFileSync(join(outDir, name), html.endsWith("\n") ? html : html + "\n", "utf8");
    console.log(`rendered ${name}`);
}
