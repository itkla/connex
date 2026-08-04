"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
    ArrowPathIcon,
    BoltIcon,
    CheckCircleIcon,
    ExclamationTriangleIcon,
} from "@heroicons/react/24/outline";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";

import AccessDenied from "@/app/components/AccessDenied";
import { EmptyState } from "@/app/components/EmptyState";
import { PageHeader } from "@/app/components/PageHeader";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import { CrumbLabel } from "@/app/hooks/useNavTrail";
import { WorkflowSimulationEvidence } from "@/app/components/settings/workflows/WorkflowSimulationDialog";
import {
    ApiError,
    getUsers,
    getWorkflowRecipe,
    getWorkflowRecipes,
    installWorkflowRecipe,
    previewWorkflowRecipe,
} from "@/app/lib/api";
import type {
    User,
    WorkflowRecipe,
    WorkflowRecipeParameters,
    WorkflowRecipePreview,
} from "@/app/lib/types";
import { isWorkflowRecipeKey, WORKFLOW_RECIPE_KEYS } from "@/app/lib/workflowOperations";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";

function recipeMessageKey(recipeKey: string): (typeof WORKFLOW_RECIPE_KEYS)[number] {
    return isWorkflowRecipeKey(recipeKey) ? recipeKey : "person-job-change-follow-up";
}

/** Curated deterministic recipe register with source, actor, side-effect, and permission disclosure. */
export function WorkflowRecipeGallery() {
    const t = useTranslations("WorkflowOperations");
    const { activeWorkspaceId, switching } = useWorkspace();
    const [recipes, setRecipes] = useState<WorkflowRecipe[] | null>(null);
    const [error, setError] = useState<"forbidden" | "load" | null>(null);
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        if (!activeWorkspaceId || switching) return;
        const controller = new AbortController();
        void getWorkflowRecipes({
            signal: controller.signal,
            headers: { "X-Workspace-Id": String(activeWorkspaceId) },
        }).then((items) => {
            if (!controller.signal.aborted) {
                setError(null);
                setRecipes(items);
            }
        }).catch((loadError: unknown) => {
            if (controller.signal.aborted) return;
            setError(loadError instanceof ApiError && loadError.status === 403 ? "forbidden" : "load");
        });
        return () => controller.abort();
    }, [activeWorkspaceId, attempt, switching]);

    if (error === "forbidden") return <AccessDenied variant="page" title={t("access.title")} body={t("access.body")} />;

    return (
        <>
            <PageHeader
                title={t("recipes.title")}
                description={t("recipes.description")}
            />
            {error === "load" ? (
                <RecipeError onRetry={() => setAttempt((value) => value + 1)} />
            ) : recipes === null || switching ? (
                <RecipeListSkeleton />
            ) : recipes.length === 0 ? (
                <EmptyState icon={BoltIcon} title={t("recipes.emptyTitle")} body={t("recipes.emptyBody")} tone="muted" />
            ) : (
                <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                    {recipes.map((recipe) => {
                        const key = recipeMessageKey(recipe.recipeKey);
                        return (
                            <li key={recipe.recipeKey}>
                                <Link
                                    href={`/workflows/recipes/${encodeURIComponent(recipe.recipeKey)}`}
                                    className="grid gap-4 px-5 py-5 transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring motion-reduce:transition-none sm:grid-cols-[minmax(0,1fr)_auto]"
                                >
                                    <div className="min-w-0 space-y-2">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <h2 className="text-base font-semibold text-foreground">{t(`recipes.items.${key}.title`)}</h2>
                                            <Badge variant="outline">{t("recipes.version", { version: recipe.recipeVersion })}</Badge>
                                        </div>
                                        <p className="max-w-2xl text-sm text-muted-foreground">{t(`recipes.items.${key}.description`)}</p>
                                        <p className="text-xs text-muted-foreground">
                                            {t("recipes.source", { source: t(`sourceEvent.${recipe.sourceEvent}`) })}
                                        </p>
                                    </div>
                                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                                        <span>{t("recipes.configure")}</span>
                                        <span aria-hidden>→</span>
                                    </div>
                                </Link>
                            </li>
                        );
                    })}
                </ul>
            )}
        </>
    );
}

/** Recipe disclosure, read-only preview, and disabled-workflow installation flow. */
export function WorkflowRecipeDetail({ recipeKey }: { recipeKey: string }) {
    const t = useTranslations("WorkflowOperations");
    const tw = useTranslations("WorkspaceWorkflows");
    const router = useRouter();
    const { activeWorkspaceId, switching } = useWorkspace();
    const [recipe, setRecipe] = useState<WorkflowRecipe | null>(null);
    const [users, setUsers] = useState<User[]>([]);
    const [parameters, setParameters] = useState<WorkflowRecipeParameters>({});
    const [name, setName] = useState("");
    const [exampleRecordId, setExampleRecordId] = useState("");
    const [preview, setPreview] = useState<WorkflowRecipePreview | null>(null);
    const [pending, setPending] = useState<"preview" | "install" | null>(null);
    const [error, setError] = useState<"forbidden" | "missing" | "load" | "action" | null>(null);
    const [attempt, setAttempt] = useState(0);

    const headers = useMemo(
        () => activeWorkspaceId == null ? undefined : { "X-Workspace-Id": String(activeWorkspaceId) },
        [activeWorkspaceId],
    );

    useEffect(() => {
        if (!activeWorkspaceId || !headers || switching) return;
        const controller = new AbortController();
        void Promise.all([
            getWorkflowRecipe(recipeKey, { signal: controller.signal, headers }),
            getUsers({ signal: controller.signal, headers }).catch(() => []),
        ]).then(([loadedRecipe, loadedUsers]) => {
            if (controller.signal.aborted) return;
            setError(null);
            setRecipe(loadedRecipe);
            setUsers(loadedUsers);
            setParameters(Object.fromEntries(loadedRecipe.requiredParameters.map((parameter) => [parameter, ""])));
        }).catch((loadError: unknown) => {
            if (controller.signal.aborted) return;
            if (loadError instanceof ApiError && loadError.status === 403) setError("forbidden");
            else if (loadError instanceof ApiError && loadError.status === 404) setError("missing");
            else setError("load");
        });
        return () => controller.abort();
    }, [activeWorkspaceId, attempt, headers, recipeKey, switching]);

    const updateParameter = (key: string, value: string) => {
        const numeric = key.endsWith("Id") || key.endsWith("Days");
        setParameters((current) => ({ ...current, [key]: numeric && value !== "" ? Number(value) : value }));
        setPreview(null);
    };

    const runPreview = async () => {
        if (!recipe || !headers) return;
        setPending("preview");
        setError(null);
        try {
            const loaded = await previewWorkflowRecipe(recipe.recipeKey, {
                name: name.trim() || undefined,
                parameters,
                exampleRecordId: /^\d+$/.test(exampleRecordId) ? Number(exampleRecordId) : undefined,
            }, { headers });
            setPreview(loaded);
        } catch {
            setError("action");
        } finally {
            setPending(null);
        }
    };

    const install = async () => {
        if (!recipe || !preview || !headers) return;
        setPending("install");
        setError(null);
        try {
            const installed = await installWorkflowRecipe(recipe.recipeKey, {
                previewHash: preview.previewHash,
                name: name.trim() || undefined,
                parameters,
            }, { headers });
            router.push(`/workflows/${installed.workflow.id}`);
        } catch {
            setError("action");
            setPending(null);
        }
    };

    if (error === "forbidden") return <AccessDenied variant="page" title={t("access.title")} body={t("access.body")} />;
    if (error === "missing") return <RecipeUnavailable title={t("recipes.missingTitle")} body={t("recipes.missingBody")} />;
    if ((error === "load" && !recipe) || (!recipe && !switching && error)) {
        return <RecipeError onRetry={() => setAttempt((value) => value + 1)} />;
    }
    if (!recipe || switching) return <RecipeDetailSkeleton />;

    const messageKey = recipeMessageKey(recipe.recipeKey);
    const canInstall = preview?.writesCreated === false
        && preview.unresolvedParameters.length === 0
        && preview.validation.canPublish;

    return (
        <div className="space-y-8">
            <CrumbLabel value={t(`recipes.items.${messageKey}.title`)} />
            <header className="space-y-4">
                <div className="space-y-2">
                    <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="outline">{t("recipes.version", { version: recipe.recipeVersion })}</Badge>
                        <Badge variant="outline">{t("recipes.deterministic")}</Badge>
                    </div>
                    <h1 className="text-3xl font-bold tracking-tight text-foreground">{t(`recipes.items.${messageKey}.title`)}</h1>
                    <p className="max-w-2xl text-sm text-muted-foreground">{t(`recipes.items.${messageKey}.description`)}</p>
                </div>
            </header>

            <div className="grid gap-8 xl:grid-cols-[minmax(0,1.25fr)_minmax(20rem,0.75fr)]">
                <main className="space-y-6">
                    <Disclosure recipe={recipe} />
                    <section className="space-y-4 rounded-2xl border border-border bg-card p-5">
                        <div>
                            <h2 className="text-base font-semibold text-foreground">{t("recipes.configureTitle")}</h2>
                            <p className="text-sm text-muted-foreground">{t("recipes.configureBody")}</p>
                        </div>
                        <div className="space-y-2">
                            <Label htmlFor="recipe-name">{t("recipes.nameLabel")}</Label>
                            <Input id="recipe-name" value={name} onChange={(event) => { setName(event.target.value); setPreview(null); }} />
                        </div>
                        {recipe.requiredParameters.map((parameter) => (
                            <ParameterField
                                key={parameter}
                                parameter={parameter}
                                value={parameters[parameter]}
                                users={users}
                                onChange={(value) => updateParameter(parameter, value)}
                            />
                        ))}
                        <div className="space-y-2">
                            <Label htmlFor="recipe-example-record">{t("recipes.exampleRecordLabel")}</Label>
                            <Input
                                id="recipe-example-record"
                                inputMode="numeric"
                                value={exampleRecordId}
                                onChange={(event) => setExampleRecordId(event.target.value)}
                            />
                            <p className="text-xs text-muted-foreground">{t("recipes.exampleRecordHelp")}</p>
                        </div>
                    </section>
                </main>

                <aside className="space-y-4">
                    <Alert>
                        <BoltIcon />
                        <AlertTitle>{t("recipes.previewOnlyTitle")}</AlertTitle>
                        <AlertDescription>{t("recipes.previewOnlyBody")}</AlertDescription>
                    </Alert>
                    <Button className="w-full" variant="outline" disabled={pending !== null} onClick={() => void runPreview()}>
                        {pending === "preview" ? t("recipes.previewing") : t("recipes.preview")}
                    </Button>
                    {error === "action" ? (
                        <Alert variant="destructive">
                            <ExclamationTriangleIcon />
                            <AlertTitle>{t("recipes.actionErrorTitle")}</AlertTitle>
                            <AlertDescription>{t("recipes.actionErrorBody")}</AlertDescription>
                        </Alert>
                    ) : null}
                    {preview ? (
                        <div className="space-y-4 rounded-2xl border border-border bg-card p-4">
                            <div className="flex items-center gap-2">
                                <CheckCircleIcon className="size-5 text-secondary-foreground" />
                                <h2 className="text-sm font-semibold text-foreground">{t("recipes.previewReady")}</h2>
                            </div>
                            <p className="text-xs text-muted-foreground">{t("recipes.zeroWrites")}</p>
                            <dl className="grid grid-cols-2 gap-3 text-sm">
                                <div>
                                    <dt className="text-xs text-muted-foreground">{t("recipes.nodes")}</dt>
                                    <dd className="font-semibold tabular-nums text-foreground">{preview.definition.nodes.length}</dd>
                                </div>
                                <div>
                                    <dt className="text-xs text-muted-foreground">{t("recipes.actions")}</dt>
                                    <dd className="font-semibold tabular-nums text-foreground">{preview.plannedActions.length}</dd>
                                </div>
                            </dl>
                            {preview.exampleResult ? (
                                <WorkflowSimulationEvidence
                                    result={preview.exampleResult}
                                    diagnosticMessage={(diagnostic) => tw(`diagnostics.${diagnostic.code}`, diagnostic.params)}
                                />
                            ) : null}
                            {preview.unresolvedParameters.length > 0 ? (
                                <p className="text-sm text-destructive">{t("recipes.unresolved", { count: preview.unresolvedParameters.length })}</p>
                            ) : null}
                            {!preview.validation.canPublish ? (
                                <p className="text-sm text-destructive">{t("recipes.validationBlocked")}</p>
                            ) : null}
                        </div>
                    ) : null}
                    <Button className="w-full" variant="brand" disabled={!canInstall || pending !== null} onClick={() => void install()}>
                        {pending === "install" ? t("recipes.installing") : t("recipes.install")}
                    </Button>
                    <p className="text-xs text-muted-foreground">{t("recipes.installHelp")}</p>
                </aside>
            </div>
        </div>
    );
}

function Disclosure({ recipe }: { recipe: WorkflowRecipe }) {
    const t = useTranslations("WorkflowOperations");
    return (
        <section className="space-y-4" aria-labelledby="recipe-disclosure-heading">
            <div>
                <h2 id="recipe-disclosure-heading" className="text-lg font-semibold text-foreground">{t("recipes.disclosureTitle")}</h2>
                <p className="text-sm text-muted-foreground">{t("recipes.disclosureBody")}</p>
            </div>
            <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                <DisclosureRow label={t("recipes.sourceLabel")} values={[t(`sourceEvent.${recipe.sourceEvent}`)]} />
                <DisclosureRow label={t("recipes.actorLabel")} values={[t(`actorModel.${recipe.actorModel}`)]} />
                <DisclosureRow label={t("recipes.dataRead")} values={recipe.dataRead.map((value) => t(`data.${value}`))} />
                <DisclosureRow label={t("recipes.dataWritten")} values={recipe.dataWritten.map((value) => t(`data.${value}`))} />
                <DisclosureRow label={t("recipes.permissions")} values={recipe.requiredPermissions.map((value) => t(`permission.${value}`))} />
                <DisclosureRow label={t("recipes.lockedFields")} values={recipe.lockedFields.map((value) => t(`recipeField.${value}`))} />
                <DisclosureRow label={t("recipes.editableFields")} values={recipe.editableFields.map((value) => t(`recipeField.${value}`))} />
                <DisclosureRow label={t("recipes.sideEffects")} values={recipe.sideEffects.map((value) => t(`sideEffect.${value}`))} />
                <DisclosureRow
                    label={t("recipes.retryBehaviour")}
                    values={recipe.actions.map((action) => `${t(`sideEffect.${action.actionType}`)}: ${t(`retrySafety.${action.retrySafety}`)}`)}
                />
                <DisclosureRow label={t("recipes.disableRemove")} values={[t("recipes.disableBehavior"), t("recipes.removeBehavior")]} />
            </dl>
        </section>
    );
}

function DisclosureRow({ label, values }: { label: string; values: string[] }) {
    return (
        <div className="grid gap-2 px-4 py-3 text-sm sm:grid-cols-[10rem_minmax(0,1fr)]">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="flex flex-wrap gap-1.5">
                {values.map((value) => <Badge key={value} variant="outline">{value}</Badge>)}
            </dd>
        </div>
    );
}

function ParameterField({
    parameter,
    value,
    users,
    onChange,
}: {
    parameter: string;
    value: string | number | boolean | null | undefined;
    users: User[];
    onChange: (value: string) => void;
}) {
    const t = useTranslations("WorkflowOperations");
    const stringValue = value == null ? "" : String(value);
    const userParameter = parameter === "actorUserId" || parameter === "targetUserId";
    return (
        <div className="space-y-2">
            <Label htmlFor={`recipe-${parameter}`}>{t(`parameter.${parameter}`)}</Label>
            {userParameter ? (
                <Select value={stringValue} onValueChange={onChange}>
                    <SelectTrigger id={`recipe-${parameter}`} className="w-full">
                        <SelectValue placeholder={t("parameter.userPlaceholder")} />
                    </SelectTrigger>
                    <SelectContent>
                        {users.map((user) => <SelectItem key={user.id} value={String(user.id)}>{user.displayName}</SelectItem>)}
                    </SelectContent>
                </Select>
            ) : (
                <Input
                    id={`recipe-${parameter}`}
                    type={parameter.endsWith("Days") ? "number" : "text"}
                    min={parameter === "coolingDays" ? 30 : parameter.endsWith("Days") ? 0 : undefined}
                    max={parameter.endsWith("Days") ? 365 : undefined}
                    value={stringValue}
                    onChange={(event) => onChange(event.target.value)}
                />
            )}
        </div>
    );
}

function RecipeError({ onRetry }: { onRetry: () => void }) {
    const t = useTranslations("WorkflowOperations");
    return (
        <RecipeUnavailable
            title={t("recipes.errorTitle")}
            body={t("recipes.errorBody")}
            action={(
                <Button variant="outline" onClick={onRetry}>
                    <ArrowPathIcon className="size-4" />
                    {t("retry")}
                </Button>
            )}
        />
    );
}

function RecipeUnavailable({ title, body, action }: { title: string; body: string; action?: React.ReactNode }) {
    return (
        <div className="rounded-2xl border border-border bg-card px-6 py-16 text-center">
            <ExclamationTriangleIcon className="mx-auto size-7 text-muted-foreground" />
            <h1 className="mt-4 text-lg font-semibold text-foreground">{title}</h1>
            <p className="mx-auto mt-1 max-w-md text-sm text-muted-foreground">{body}</p>
            {action ? <div className="mt-5 flex justify-center">{action}</div> : null}
        </div>
    );
}

function RecipeListSkeleton() {
    return (
        <div className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card" aria-busy="true">
            {Array.from({ length: 3 }, (_, index) => (
                <div key={index} className="space-y-3 p-5">
                    <Skeleton className="h-5 w-56 max-w-full" />
                    <Skeleton className="h-4 w-96 max-w-full" />
                    <Skeleton className="h-3 w-40" />
                </div>
            ))}
        </div>
    );
}

function RecipeDetailSkeleton() {
    return (
        <div className="space-y-8" aria-busy="true">
            <div className="space-y-3">
                <Skeleton className="h-8 w-24" />
                <Skeleton className="h-9 w-80 max-w-full" />
                <Skeleton className="h-4 w-96 max-w-full" />
            </div>
            <div className="grid gap-8 xl:grid-cols-[minmax(0,1.25fr)_minmax(20rem,0.75fr)]">
                <Skeleton className="h-[32rem] rounded-2xl" />
                <Skeleton className="h-72 rounded-2xl" />
            </div>
        </div>
    );
}
