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
    { name: "password-reset.en.html", element: <PasswordReset locale="en" /> },
    { name: "password-reset.ja.html", element: <PasswordReset locale="ja" /> },
    { name: "email-change.en.html", element: <EmailChange locale="en" /> },
    { name: "email-change.ja.html", element: <EmailChange locale="ja" /> },
    { name: "test.en.html", element: <Test locale="en" /> },
    { name: "test.ja.html", element: <Test locale="ja" /> },
    { name: "notification.en.html", element: <NotificationEmail /> },
    { name: "report-delivery.en.html", element: <ReportDelivery locale="en" /> },
    { name: "report-delivery.ja.html", element: <ReportDelivery locale="ja" /> },
];

const requestedNames = new Set(process.argv.slice(2).filter((name) => name !== "--"));
const unknownNames = [...requestedNames].filter(
    (requestedName) => !templates.some(({ name }) => name === requestedName),
);

if (unknownNames.length > 0) {
    throw new Error(`Unknown email template: ${unknownNames.join(", ")}`);
}

const selectedTemplates = requestedNames.size === 0
    ? templates
    : templates.filter(({ name }) => requestedNames.has(name));

mkdirSync(outDir, { recursive: true });

await Promise.all(selectedTemplates.map(async ({ name, element }) => {
    const html = await render(element, { pretty: true });
    writeFileSync(join(outDir, name), html.endsWith("\n") ? html : html + "\n", "utf8");
    console.log(`rendered ${name}`);
}));
