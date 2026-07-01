import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import {
    getActivities,
    getCompanies,
    getCompanyTemperatures,
    getContacts,
    getContactTemperatures,
    getCurrentUserFromCookie,
    getDeals,
    getNotes,
    getPipelines,
    getStagesByPipelineId,
    getTasks,
    getUsers,
} from "@/app/lib/api";
import type { Activity, Company, Contact, Deal, Note, Pipeline, RelationshipTemperature, Stage, Task, TemperatureBand, User } from "@/app/lib/types";
import MapView from "@/app/components/map/MapView";
import { buildGraph, companyNodeId, contactNodeId } from "@/app/components/map/graph/buildGraph";

type MapSearchParams = { companyId?: string; contactId?: string };

export default async function MapPage({ searchParams }: { searchParams: Promise<MapSearchParams> }) {
    const cookie = (await headers()).get('cookie');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const t = await getTranslations("MapPage");
    const { companyId, contactId } = await searchParams;

    const [user, companies, contacts, deals, users, allActivities, allTasks, allNotes, pipelines, contactTemps, companyTemps] =
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
            getContactTemperatures(init).catch(() => [] as RelationshipTemperature[]),
            getCompanyTemperatures(init).catch(() => [] as RelationshipTemperature[]),
        ]);

    if (!user) {
        redirect('/auth/login');
    }

    const contactWarmth = new Map<number, TemperatureBand>(contactTemps.map((t) => [t.id, t.band]));
    const companyWarmth = new Map<number, TemperatureBand>(companyTemps.map((t) => [t.id, t.band]));

    // stage names across every pipeline, used to label a deal's current stage.
    const stageLists = await Promise.all(
        pipelines.map((p) => getStagesByPipelineId(p.id, init).catch(() => [] as Stage[])),
    );
    const stageNames = new Map<number, string>();
    for (const stages of stageLists) {
        for (const s of stages) {
            stageNames.set(s.id, s.name);
        }
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
        contactWarmth,
        companyWarmth,
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
                <MapView graph={graph} focusId={focusId} />
            </div>
        </div>
    );
}