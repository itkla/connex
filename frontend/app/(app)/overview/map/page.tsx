import Link from "next/link";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { ShareIcon } from "@heroicons/react/24/outline";
import {
    getContactTemperatures,
    getCurrentUserResultFromCookie,
    getMapCompanyTemperatures,
    getMyWorkspacesFromCookie,
    getPipelines,
    getRelationshipMapData,
    getStagesByPipelineId,
    getUsers,
} from "@/app/lib/api";
import type { Pipeline, RelationshipTemperature, Stage, TemperatureBand, User } from "@/app/lib/types";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import MapView from "@/app/components/map/MapView";
import { buildGraph, companyNodeId, contactNodeId } from "@/app/components/map/graph/buildGraph";

type MapSearchParams = { companyId?: string; contactId?: string };

export default async function MapPage({ searchParams }: { searchParams: Promise<MapSearchParams> }) {
    const cookie = (await headers()).get('cookie');
    const init = { headers: { cookie: cookie ?? '' } } as const;
    const t = await getTranslations("MapPage");
    const { companyId, contactId } = await searchParams;
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect('/auth/login');
    }

    const [workspaces, mapData, users, pipelines] =
        await Promise.all([
            getMyWorkspacesFromCookie(cookie),
            getRelationshipMapData(init).catch(() => null),
            getUsers(init).catch(() => [] as User[]),
            getPipelines(init).catch(() => [] as Pipeline[]),
        ]);

    if (!mapData) {
        return (
            <div className="flex min-h-0 flex-1 items-center justify-center">
                <div role="alert" className="max-w-md rounded-2xl border border-border bg-card px-6 py-12 text-center">
                    <h2 className="text-lg font-semibold text-foreground">{t("limitTitle")}</h2>
                    <p className="mt-2 text-sm text-muted-foreground">{t("limitBody")}</p>
                </div>
            </div>
        );
    }

    const { companies, contacts, deals, activities: allActivities, tasks: allTasks, notes: allNotes } = mapData;

    const activeWorkspace = workspaces.workspaces.find((w) => w.id === workspaces.activeWorkspaceId);
    const ucLabel = activeWorkspace?.name ?? t("yourCompany");

    const [contactTemps, companyTemps] = await Promise.all([
        getContactTemperatures(contacts.map((contact) => contact.id), init).catch(() => [] as RelationshipTemperature[]),
        getMapCompanyTemperatures(init).catch(() => [] as RelationshipTemperature[]),
    ]);

    const contactWarmth = new Map<number, TemperatureBand>(contactTemps.map((t) => [t.id, t.band]));
    const companyWarmth = new Map<number, TemperatureBand>(companyTemps.map((t) => [t.id, t.band]));

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
        ucLabel,
    });

    const focusId = contactId
        ? contactNodeId(Number(contactId))
        : companyId
          ? companyNodeId(Number(companyId))
          : undefined;

    const isEmpty = companies.length === 0 && contacts.length === 0;

    return (
        <div className="flex h-full min-h-0 w-full flex-1 flex-col">
            {isEmpty ? (
                <div className="flex min-h-0 flex-1 items-center justify-center">
                    <div className="flex max-w-md flex-col items-center rounded-2xl border border-border bg-card px-6 py-16 text-center">
                        <span className="flex size-14 items-center justify-center rounded-2xl bg-brand-light text-brand">
                            <ShareIcon className="size-7" />
                        </span>
                        <h2 className="mt-6 text-xl font-semibold text-foreground">{t("emptyTitle")}</h2>
                        <p className="mt-2 text-sm text-muted-foreground">{t("emptyBody")}</p>
                        <Link
                            href="/records/companies"
                            className="mt-6 inline-flex items-center rounded-lg bg-brand px-4 py-2 text-sm font-medium text-brand-foreground transition hover:bg-brand-hover"
                        >
                            {t("emptyCta")}
                        </Link>
                    </div>
                </div>
            ) : (
                <div className="min-h-0 w-full flex-1 rounded-lg">
                    <MapView graph={graph} focusId={focusId} />
                </div>
            )}
        </div>
    );
}
