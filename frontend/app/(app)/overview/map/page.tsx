import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import {
    getActivities,
    getCompanies,
    getContacts,
    getCurrentUserFromCookie,
    getDeals,
    getNotes,
    getPipelines,
    getStagesByPipelineId,
    getTasks,
    getUsers,
} from "@/app/lib/api";
import type { Activity, Company, Contact, Deal, Note, Pipeline, Stage, Task, User } from "@/app/lib/types";
import RelationMap from "@/app/components/map/RelationMap";
import { buildGraph, companyNodeId, contactNodeId } from "@/app/components/map/graph/buildGraph";

type MapSearchParams = { companyId?: string; contactId?: string };

export default async function MapPage({ searchParams }: { searchParams: Promise<MapSearchParams> }) {
    const cookie = (await headers()).get('cookie');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const t = await getTranslations("MapPage");
    const { companyId, contactId } = await searchParams;

    const [user, companies, contacts, deals, users, allActivities, allTasks, allNotes, pipelines] =
        await Promise.all([
            getCurrentUserFromCookie(cookie),
            getCompanies(init).catch(() => [] as Company[]),
            getContacts({}, init).catch(() => [] as Contact[]),
            getDeals(init).catch(() => [] as Deal[]),
            getUsers(init).catch(() => [] as User[]),
            getActivities(init).catch(() => [] as Activity[]),
            getTasks(init).catch(() => [] as Task[]),
            getNotes(init).catch(() => [] as Note[]),
            getPipelines(init).catch(() => [] as Pipeline[]),
        ]);

    if (!user) {
        redirect('/auth/login');
    }

    // stage names across every pipeline. needed to classify deal outcome.
    // TODO: add a win/lose attribute to stages to classify deal outcome instead of hacking at the string
    const stageLists = await Promise.all(
        pipelines.map((p) => getStagesByPipelineId(p.id, init).catch(() => [] as Stage[])),
    );
    const stageNames = new Map<number, string>();
    for (const stages of stageLists) {
        for (const s of stages) stageNames.set(s.id, s.name);
    }

    const graph = buildGraph({
        companies,
        contacts,
        deals,
        users,
        activities: allActivities,
        tasks: allTasks,
        notes: allNotes,
        stageNames,
        // TODO: replace with real branding later (maybe read ORG_NAME from .env?)
        ucLabel: "Your Company",
    });

    const focusId = contactId
        ? contactNodeId(Number(contactId))
        : companyId
          ? companyNodeId(Number(companyId))
          : undefined;

    return (
        <div className="flex min-h-0 flex-1 flex-col w-full h-full">
            {/* <h1 className="px-1 py-3 text-4xl font-extrabold tracking-tight">{t("title")}</h1> */}
            <div className="min-h-0 flex-1 rounded-lg w-full h-full">
                <RelationMap graph={graph} focusId={focusId} />
            </div>
        </div>
    );
}