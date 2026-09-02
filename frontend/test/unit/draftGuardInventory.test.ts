import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { act, createElement, type ReactElement, type ReactNode } from "react";
import ts from "typescript";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { installInteractiveDocument } from "@/test/unit/helpers/interactiveDocument";
import type { MobileDealDiscardControls } from "@/app/components/actions/MobileDealDiscardGuard";

type ConfirmCapture = {
    open: boolean;
    onKeepEditing: () => void;
    onDiscard: () => void;
};

type RenderedCapture = {
    dismiss: ((open: boolean) => void) | null;
    confirm: ConfirmCapture | null;
    inputs: Map<string, (value: string) => void>;
    mentions: Map<string, (value: string) => void>;
    richNoteChange: ((value: string) => void) | null;
    switchChange: ((value: boolean) => void) | null;
};

const rendered = vi.hoisted<RenderedCapture>(() => ({
    dismiss: null,
    confirm: null,
    inputs: new Map<string, (value: string) => void>(),
    mentions: new Map<string, (value: string) => void>(),
    richNoteChange: null,
    switchChange: null,
}));

vi.mock("next/navigation", () => ({
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

vi.mock("next-intl", () => ({
    useLocale: () => "en",
    useTranslations: () => (key: string) => key,
}));

vi.mock("next/image", async () => {
    const React = await import("react");
    return { default: () => React.createElement("span") };
});

vi.mock("next/link", async () => {
    const React = await import("react");
    return {
        default: ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children),
    };
});

vi.mock("motion/react", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        AnimatePresence: Passthrough,
        motion: { div: Passthrough },
        useReducedMotion: () => true,
    };
});

vi.mock("@/app/hooks/useWorkspace", () => ({
    useWorkspace: () => ({ activeWorkspaceId: 17 }),
}));

vi.mock("@/app/hooks/useFormDraft", () => ({
    useFormDraft: () => ({ persist: vi.fn(), clear: vi.fn() }),
}));

vi.mock("@/app/hooks/useApiErrorToast", () => ({
    useApiErrorToast: () => vi.fn(),
}));

vi.mock("@/app/hooks/useCompanySearch", () => ({
    useCompanySearch: () => ({
        companies: [],
        error: null,
        loading: false,
        onInputValueChange: vi.fn(),
    }),
}));

vi.mock("@/app/hooks/useRecordTargetSearch", () => ({
    useDealTargetSearch: () => ({
        deals: [],
        options: [],
        query: "",
        loading: false,
        error: null,
        onInputValueChange: vi.fn(),
    }),
}));

vi.mock("@/app/hooks/useDuplicatePreflight", () => ({
    duplicatePreflightResponseSignature: () => "none",
    useDuplicatePreflight: () => ({
        acknowledged: false,
        blocked: false,
        response: null,
        retry: vi.fn(),
        reviewNow: vi.fn(async () => ({ allowed: true, duplicateReviewToken: null })),
        setAcknowledged: vi.fn(),
        status: "idle",
    }),
}));

vi.mock("@/app/components/records/contacts/useBusinessCardCapture", () => ({
    useBusinessCardCapture: () => ({
        acknowledgeRecoveredImport: vi.fn(),
        available: false,
        canCreateCompany: false,
        cancelScan: vi.fn(),
        captureImportError: vi.fn(),
        companyMode: "none",
        companyName: "",
        companyValidationError: null,
        continueManually: vi.fn(),
        deferImportRetry: vi.fn(),
        discardCardImage: vi.fn(),
        file: null,
        importError: null,
        isScanning: false,
        markImportAvatarCompleted: vi.fn(),
        prepareImportDraft: vi.fn(),
        previewUrl: null,
        recoveredImport: null,
        recoveryStatus: "idle",
        requestError: null,
        requiresExactImportRetry: false,
        resolveImportRetry: vi.fn(),
        result: null,
        retryRecovery: vi.fn(),
        retryScan: vi.fn(),
        scanAvailable: false,
        selectCompanyMode: vi.fn(),
        selectExistingCompany: vi.fn(),
        selectFile: vi.fn(),
        status: "idle",
        updateCompanyName: vi.fn(),
        updateContactField: vi.fn(),
    }),
}));

vi.mock("@/app/lib/api", async () => {
    const actual = await vi.importActual<typeof import("@/app/lib/api")>("@/app/lib/api");
    return {
        ...actual,
        getCompanyPeople: vi.fn(async () => []),
        getUsers: vi.fn(async () => []),
    };
});

vi.mock("@/app/lib/toast", () => ({
    toastError: vi.fn(),
    toastSuccess: vi.fn(),
}));

vi.mock("@/app/components/ConfirmDiscardDialog", () => ({
    default: (props: ConfirmCapture) => {
        rendered.confirm = props;
        return null;
    },
}));

vi.mock("@/components/ui/responsive-dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        ResponsiveDialog: ({
            children,
            onOpenChange,
        }: {
            children?: ReactNode;
            onOpenChange?: (open: boolean) => void;
        }) => {
            if (onOpenChange) rendered.dismiss = onOpenChange;
            return React.createElement(React.Fragment, null, children);
        },
        ResponsiveDialogClose: Passthrough,
        ResponsiveDialogContent: Passthrough,
        ResponsiveDialogDescription: Passthrough,
        ResponsiveDialogFooter: Passthrough,
        ResponsiveDialogHeader: Passthrough,
        ResponsiveDialogTitle: Passthrough,
    };
});

vi.mock("@/components/ui/dialog", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Dialog: ({ children, onOpenChange }: { children?: ReactNode; onOpenChange?: (open: boolean) => void }) => {
            if (onOpenChange) rendered.dismiss = onOpenChange;
            return React.createElement(React.Fragment, null, children);
        },
        DialogClose: Passthrough,
        DialogContent: Passthrough,
        DialogDescription: Passthrough,
        DialogFooter: Passthrough,
        DialogHeader: Passthrough,
        DialogTitle: Passthrough,
    };
});

vi.mock("@/components/ui/drawer", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Drawer: ({ children, onOpenChange }: { children?: ReactNode; onOpenChange?: (open: boolean) => void }) => {
            if (onOpenChange) rendered.dismiss = onOpenChange;
            return React.createElement(React.Fragment, null, children);
        },
        DrawerClose: Passthrough,
        DrawerContent: Passthrough,
        DrawerDescription: Passthrough,
        DrawerFooter: Passthrough,
        DrawerHeader: Passthrough,
        DrawerTitle: Passthrough,
    };
});

vi.mock("@/components/ui/input", () => ({
    Input: ({
        id,
        onChange,
    }: {
        id?: string;
        onChange?: (event: { target: { value: string } }) => void;
    }) => {
        if (id && onChange) rendered.inputs.set(id, (value) => onChange({ target: { value } }));
        return null;
    },
}));

vi.mock("@/components/ui/switch", () => ({
    Switch: ({ onCheckedChange }: { onCheckedChange?: (value: boolean) => void }) => {
        if (onCheckedChange) rendered.switchChange = onCheckedChange;
        return null;
    },
}));

vi.mock("@/app/components/activity/notes/MentionEditor", () => ({
    default: ({ id, onChange }: { id: string; onChange: (value: string) => void }) => {
        rendered.mentions.set(id, onChange);
        return null;
    },
}));

vi.mock("@/app/components/activity/notes/RichNoteEditor", () => ({
    default: ({ onChange }: { onChange: (value: string) => void }) => {
        rendered.richNoteChange = onChange;
        return null;
    },
}));

vi.mock("@/app/components/activity/notes/commands/slashCommandRegistry", () => ({
    ACTIVITY_COMMANDS: [],
    ENTITY_COMMANDS: [],
}));

vi.mock("@/components/ui/button", async () => {
    const React = await import("react");
    return { Button: ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children) };
});

vi.mock("@/components/ui/label", async () => {
    const React = await import("react");
    return { Label: ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children) };
});

vi.mock("@/components/ui/combobox", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Combobox: Passthrough,
        ComboboxChip: Passthrough,
        ComboboxChips: Passthrough,
        ComboboxChipsInput: () => null,
        ComboboxContent: Passthrough,
        ComboboxEmpty: Passthrough,
        ComboboxInput: () => null,
        ComboboxItem: Passthrough,
        ComboboxList: Passthrough,
        ComboboxValue: () => null,
        useComboboxAnchor: () => null,
    };
});

vi.mock("@/components/ui/autocomplete", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Autocomplete: Passthrough,
        AutocompleteContent: Passthrough,
        AutocompleteEmpty: Passthrough,
        AutocompleteInput: () => null,
        AutocompleteItem: Passthrough,
        AutocompleteList: Passthrough,
    };
});

vi.mock("@/components/ui/select", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return {
        Select: Passthrough,
        SelectContent: Passthrough,
        SelectItem: Passthrough,
        SelectTrigger: Passthrough,
        SelectValue: () => null,
    };
});

vi.mock("@/components/ui/segmented-control", () => ({ SegmentedControl: () => null }));
vi.mock("@/components/ui/checkbox", () => ({ Checkbox: () => null }));
vi.mock("@/components/ui/input-group", async () => {
    const React = await import("react");
    return {
        InputGroupAddon: ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children),
    };
});
vi.mock("@/components/ui/dialog-status-cover", () => ({
    DialogStatusCover: () => null,
    fieldErrorClass: "",
    fieldInputClass: "",
    fieldLeadIconClass: "",
    resolveDialogStatus: () => "idle",
}));
vi.mock("@/components/ui/alert", async () => {
    const React = await import("react");
    const Passthrough = ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children);
    return { Alert: Passthrough, AlertDescription: Passthrough };
});
vi.mock("@/components/ui/badge", async () => {
    const React = await import("react");
    return { Badge: ({ children }: { children?: ReactNode }) => React.createElement(React.Fragment, null, children) };
});
vi.mock("@/components/ui/skeleton", () => ({ Skeleton: () => null }));
vi.mock("@/app/components/records/RecordSelect", () => ({ default: () => null }));
vi.mock("@/app/components/records/DuplicatePreflightWarning", () => ({ default: () => null }));
vi.mock("@/app/components/records/companies/CompanyContactsField", () => ({ default: () => null }));
vi.mock("@/app/components/records/companies/ContactSubView", () => ({ default: () => null }));
vi.mock("@/app/components/records/contacts/BusinessCardCapture", () => ({
    BusinessCardCapture: () => null,
    BusinessCardCompanyChoice: () => null,
    BusinessCardScanTrigger: () => null,
}));
vi.mock("@/app/components/ProtectedMediaImage", () => ({ default: () => null }));

/**
 * Gate over the committed draft-guard denominator. It proves that every surface **named in**
 * `lint/draft-guard-inventory.json` wires `useUnsavedChangesGuard` + `ConfirmDiscardDialog`, itself or
 * through a component it renders.
 *
 * The executable table also mounts every real guard owner, changes the owner's real draft input,
 * and proves outside dismissal, keep-editing, and confirmed discard behavior for every inventory
 * entry. Delegating entries run against the shared owner named by their inventory contract.
 *
 * It is list-driven, and that is its one blind spot: a new dialog or drawer that accumulates input and
 * is never added to the inventory is invisible here and this suite still passes. The inventory is the
 * denominator only because adding the surface to it is part of adding the surface — nothing in this
 * file can discover an unlisted one.
 */
const INVENTORY_PATH = path.join(process.cwd(), "lint", "draft-guard-inventory.json");

const GUARD_HOOK = "useUnsavedChangesGuard";
const CONFIRM_DIALOG = "ConfirmDiscardDialog";
const OWN_GUARD = "own";
const MAX_DELEGATION_DEPTH = 8;

type DraftGuardSurface = {
    file: string;
    surface: string;
    guard: string;
};

type DraftGuardInventory = {
    minimumCount: number;
    surfaces: DraftGuardSurface[];
};

function isJsonObject(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readInventory(): DraftGuardInventory {
    const parsed: unknown = JSON.parse(readFileSync(INVENTORY_PATH, "utf8"));
    if (!isJsonObject(parsed)) throw new Error("draft-guard-inventory.json is not an object");
    const { minimumCount, surfaces } = parsed;
    if (typeof minimumCount !== "number") throw new Error("minimumCount must be a number");
    if (!Array.isArray(surfaces)) throw new Error("surfaces must be an array");
    const entries = surfaces.map((entry) => {
        if (!isJsonObject(entry)) throw new Error("every surface must be an object");
        const { file, surface, guard } = entry;
        if (typeof file !== "string") throw new Error("every surface needs a file");
        if (typeof surface !== "string") throw new Error(`every surface needs a name: ${file}`);
        if (typeof guard !== "string") throw new Error(`every surface needs a guard: ${file}`);
        return { file, surface, guard } satisfies DraftGuardSurface;
    });
    return { minimumCount, surfaces: entries };
}

function readSource(file: string): string {
    return readFileSync(path.join(process.cwd(), file), "utf8");
}

function guardDirtyExpression(entry: DraftGuardSurface): ts.Expression | null {
    const source = readSource(entry.file);
    const sourceFile = ts.createSourceFile(
        entry.file,
        source,
        ts.ScriptTarget.Latest,
        true,
        ts.ScriptKind.TSX,
    );
    let dirtyExpression: ts.Expression | null = null;

    function visit(node: ts.Node) {
        if (
            ts.isCallExpression(node)
            && ts.isIdentifier(node.expression)
            && node.expression.text === GUARD_HOOK
            && node.arguments.length === 1
            && ts.isObjectLiteralExpression(node.arguments[0])
        ) {
            const dirty = node.arguments[0].properties.find((property) =>
                (ts.isPropertyAssignment(property) || ts.isShorthandPropertyAssignment(property))
                && property.name.getText(sourceFile) === "isDirty"
            );
            if (dirty && ts.isPropertyAssignment(dirty)) dirtyExpression = dirty.initializer;
            if (dirty && ts.isShorthandPropertyAssignment(dirty)) dirtyExpression = dirty.name;
        }
        ts.forEachChild(node, visit);
    }

    visit(sourceFile);
    return dirtyExpression;
}

/** The `@/`-aliased specifier a file is imported by, extension dropped as the imports write it. */
function importSpecifier(file: string): string {
    return `@/${file.replace(/\.tsx?$/, "")}`;
}

const inventory = readInventory();
const byFile = new Map(inventory.surfaces.map((entry) => [entry.file, entry]));

beforeEach(() => {
    rendered.dismiss = null;
    rendered.confirm = null;
    rendered.inputs.clear();
    rendered.mentions.clear();
    rendered.richNoteChange = null;
    rendered.switchChange = null;
    vi.stubGlobal("self", globalThis);
    vi.stubGlobal("getComputedStyle", vi.fn(() => ({ getPropertyValue: () => "" })));
    vi.stubGlobal("ResizeObserver", class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
});

/** Follows `guard` references to the file that actually wires the guard, or null if it does not resolve. */
function resolveOwner(entry: DraftGuardSurface): DraftGuardSurface | null {
    let current = entry;
    for (let depth = 0; depth < MAX_DELEGATION_DEPTH; depth += 1) {
        if (current.guard === OWN_GUARD) return current;
        const next = byFile.get(current.guard);
        if (!next) return null;
        current = next;
    }
    return null;
}

type MountedOwner = {
    onClose: ReturnType<typeof vi.fn>;
    makeDirty: () => Promise<void>;
    unmount: () => Promise<void>;
};

type OwnerScenario = () => Promise<MountedOwner>;

async function mountOwner(
    build: (onClose: (open: boolean) => void) => ReactElement,
    dirty: (rerender: () => void) => void,
): Promise<MountedOwner> {
    const installed = installInteractiveDocument();
    const { createRoot } = await import("react-dom/client");
    const root = createRoot(installed.container, { onCaughtError: vi.fn() });
    const onClose = vi.fn();
    const rerender = () => root.render(build(onClose));

    await act(async () => {
        rerender();
    });

    return {
        onClose,
        makeDirty: async () => {
            await act(async () => {
                dirty(rerender);
                await Promise.resolve();
            });
        },
        unmount: async () => {
            await act(async () => root.unmount());
        },
    };
}

function requiredInput(id: string): (value: string) => void {
    const input = rendered.inputs.get(id);
    if (!input) throw new Error(`${id} did not render a controllable input`);
    return input;
}

function requiredMention(id: string): (value: string) => void {
    const mention = rendered.mentions.get(id);
    if (!mention) throw new Error(`${id} did not render a controllable mention editor`);
    return mention;
}

function requiredRichNoteChange(): (value: string) => void {
    if (!rendered.richNoteChange) throw new Error("the note owner did not render its editor");
    return rendered.richNoteChange;
}

function requiredSwitchChange(): (value: boolean) => void {
    if (!rendered.switchChange) throw new Error("the schedule owner did not render its enabled switch");
    return rendered.switchChange;
}

function requiredDismiss(): (open: boolean) => void {
    if (!rendered.dismiss) throw new Error("the owner did not expose its outside-dismiss boundary");
    return rendered.dismiss;
}

function requiredConfirm(): ConfirmCapture {
    if (!rendered.confirm) throw new Error("the owner did not render its discard confirmation");
    return rendered.confirm;
}

const ownerScenarios: Record<string, OwnerScenario> = {
    "app/components/actions/MobileDealDiscardGuard.tsx": async () => {
        const { default: MobileDealDiscardGuard } = await import("@/app/components/actions/MobileDealDiscardGuard");
        const { Drawer } = await import("@/components/ui/drawer");
        let isDirty = false;
        return mountOwner(
            (onClose) => {
                const props: Parameters<typeof MobileDealDiscardGuard>[0] = {
                    active: true,
                    hasUnsavedChanges: () => isDirty,
                    disabled: false,
                    onBack: vi.fn(),
                    onClose: () => onClose(false),
                    children: ({ handleOpenChange }: MobileDealDiscardControls) => createElement(
                        Drawer,
                        { open: true, onOpenChange: handleOpenChange },
                    ),
                };
                return createElement(MobileDealDiscardGuard, props);
            },
            (rerender) => {
                isDirty = true;
                rerender();
            },
        );
    },
    "app/components/activity/activities/ActivityDialog.tsx": async () => {
        const { default: ActivityDialog } = await import("@/app/components/activity/activities/ActivityDialog");
        return mountOwner(
            (onClose) => createElement(ActivityDialog, {
                open: true,
                onOpenChange: onClose,
                persons: [],
                deals: [],
                currentUserId: 7,
            }),
            () => requiredMention("activity-notes")("Met at the conference"),
        );
    },
    "app/components/activity/notes/NoteDialog.tsx": async () => {
        const { default: NoteDialog } = await import("@/app/components/activity/notes/NoteDialog");
        return mountOwner(
            (onClose) => createElement(NoteDialog, {
                open: true,
                onOpenChange: onClose,
                note: null,
                persons: [],
                deals: [],
                currentUserId: 7,
            }),
            () => requiredRichNoteChange()("A real note draft"),
        );
    },
    "app/components/activity/tasks/EditTaskSheet.tsx": async () => {
        const { default: EditTaskSheet } = await import("@/app/components/activity/tasks/EditTaskSheet");
        const task = {
            id: 31,
            description: "Original follow-up",
            completed: false,
            status: "todo" as const,
            position: 0,
            assignedToId: 7,
            createdAt: "2026-08-01T00:00:00Z",
            updatedAt: "2026-08-01T00:00:00Z",
        };
        return mountOwner(
            (onClose) => createElement(EditTaskSheet, {
                task,
                open: true,
                onOpenChange: onClose,
                deals: [],
            }),
            () => requiredMention("task-description")("Changed follow-up"),
        );
    },
    "app/components/activity/tasks/TaskDialog.tsx": async () => {
        const { default: TaskDialog } = await import("@/app/components/activity/tasks/TaskDialog");
        return mountOwner(
            (onClose) => createElement(TaskDialog, {
                open: true,
                onOpenChange: onClose,
                persons: [],
                deals: [],
                users: [],
                currentUserId: 7,
            }),
            () => requiredMention("task-description")("Send the renewal brief"),
        );
    },
    "app/components/marketing/campaigns/NewCampaignDialog.tsx": async () => {
        const { default: NewCampaignDialog } = await import("@/app/components/marketing/campaigns/NewCampaignDialog");
        return mountOwner(
            (onClose) => createElement(NewCampaignDialog, { open: true, onOpenChange: onClose }),
            () => requiredInput("campaign-name")("Fall renewal"),
        );
    },
    "app/components/records/companies/NewCompanyDialog.tsx": async () => {
        const { default: NewCompanyDialog } = await import("@/app/components/records/companies/NewCompanyDialog");
        let name = "";
        return mountOwner(
            (onClose) => createElement(NewCompanyDialog, {
                open: true,
                onOpenChange: onClose,
                payload: { name },
                setPayload: vi.fn(),
                logoFile: null,
                setLogoFile: vi.fn(),
                isCreating: false,
                createNewCompany: vi.fn(),
                pendingContacts: [],
                addPendingContact: vi.fn(),
                updatePendingContact: vi.fn(),
                removePendingContact: vi.fn(),
            }),
            (rerender) => {
                name = "Northstar Labs";
                rerender();
            },
        );
    },
    "app/components/records/contacts/NewContactDialog.tsx": async () => {
        const { default: NewContactDialog } = await import("@/app/components/records/contacts/NewContactDialog");
        let name = "";
        return mountOwner(
            (onClose) => createElement(NewContactDialog, {
                newContactDialogOpen: true,
                setNewContactDialogOpen: onClose,
                newContactPayload: { name, email: "", phone: "", title: "" },
                setNewContactPayload: vi.fn(),
                imageFile: null,
                setImageFile: vi.fn(),
                isCreating: false,
                createNewContact: vi.fn(),
            }),
            (rerender) => {
                name = "Ada Lovelace";
                rerender();
            },
        );
    },
    "app/components/records/deals/NewDealDialog.tsx": async () => {
        const { default: NewDealDialog } = await import("@/app/components/records/deals/NewDealDialog");
        let isDirty = false;
        return mountOwner(
            (onClose) => createElement(NewDealDialog, {
                open: true,
                onOpenChange: onClose,
                payload: {
                    name: "",
                    value: 0,
                    actualValue: 0,
                    currency: "USD",
                    pipeline: null,
                    stage: null,
                },
                setPayload: vi.fn(),
                pipelines: [],
                stagesByPipeline: {},
                isCreating: false,
                isDirty,
                createNewDeal: vi.fn(),
            }),
            (rerender) => {
                isDirty = true;
                rerender();
            },
        );
    },
    "app/components/records/deals/ApprovalStepApproversDialog.tsx": async () => {
        const { default: ApprovalStepApproversDialog } = await import(
            "@/app/components/records/deals/ApprovalStepApproversDialog"
        );
        const approvalStep = {
            id: 41,
            stepOrder: 1,
            name: "Finance",
            requiredCount: 1,
            approvedCount: 0,
            status: "active" as const,
            onExpiry: "expire" as const,
            satisfiable: true,
            effectiveAnyApprover: false,
            effectiveApproverIds: [7],
            approvers: [],
            assignments: [],
            decisions: [],
        };
        const selectedMember = {
            id: 7,
            username: "member-7",
            displayName: "Member Seven",
            email: "member-7@example.test",
            role: "Member",
            builtInRole: "member" as const,
            status: "active",
        };
        let selectedMembers: typeof selectedMember[] = [];
        return mountOwner(
            (onClose) => createElement(ApprovalStepApproversDialog, {
                open: true,
                action: "reassign",
                documentTitle: "Renewal quote",
                steps: [approvalStep],
                selectedStepId: approvalStep.id,
                memberDirectoryStatus: "ready",
                members: [selectedMember],
                verifiedApproverIds: [selectedMember.id],
                memberLabelStatus: "ready",
                memberLabels: [selectedMember],
                mode: "members",
                selectedMembers,
                comment: "",
                busy: false,
                onOpenChange: onClose,
                onStepChange: vi.fn(),
                onRetryMembers: vi.fn(),
                onModeChange: vi.fn(),
                onSelectedMembersChange: vi.fn(),
                onCommentChange: vi.fn(),
                onSubmit: vi.fn(),
            }),
            (rerender) => {
                selectedMembers = [selectedMember];
                rerender();
            },
        );
    },
    "app/components/records/quick-edit/QuickEditSheetShell.tsx": async () => {
        const { QuickEditSheetShell } = await import("@/app/components/records/quick-edit/QuickEditSheetShell");
        let value = "original";
        return mountOwner(
            (onClose) => {
                const props = {
                    open: true,
                    onOpenChange: onClose,
                    icon: createElement("span"),
                    title: "Edit record",
                    count: 1,
                    isSaving: false,
                    onSave: vi.fn(),
                    saveLabel: "Save",
                    cancelLabel: "Cancel",
                    dirtySnapshot: { value },
                    children: createElement("span", null, value),
                };
                return createElement(QuickEditSheetShell, props);
            },
            (rerender) => {
                value = "changed";
                rerender();
            },
        );
    },
    "app/components/reports/GoalDialog.tsx": async () => {
        const { default: GoalDialog } = await import("@/app/components/reports/GoalDialog");
        return mountOwner(
            (onClose) => createElement(GoalDialog, {
                open: true,
                editing: null,
                owners: [],
                onOpenChange: onClose,
                onSubmit: vi.fn(async () => undefined),
            }),
            () => requiredInput("goal-target")("250000"),
        );
    },
    "app/components/reports/ScheduleDialog.tsx": async () => {
        const { default: ScheduleDialog } = await import("@/app/components/reports/ScheduleDialog");
        return mountOwner(
            (onClose) => createElement(ScheduleDialog, {
                open: true,
                schedule: null,
                members: [],
                canManage: true,
                loading: false,
                loadFailed: false,
                membersFailed: false,
                defaultTimezone: "UTC",
                onOpenChange: onClose,
                onRetry: vi.fn(),
                onSubmit: vi.fn(async () => undefined),
                onRequestDelete: vi.fn(),
            }),
            () => requiredSwitchChange()(false),
        );
    },
};

describe("draft-guard inventory", () => {
    it("names files that exist, uniquely and in sorted order", () => {
        const files = inventory.surfaces.map((entry) => entry.file);

        expect(files).toEqual([...new Set(files)].sort());
        expect(files.filter((file) => !existsSync(path.join(process.cwd(), file)))).toEqual([]);
    });

    it("only ever grows", () => {
        expect(
            inventory.surfaces.length,
            "a surface leaves the inventory only when it stops accumulating input; lower minimumCount in that same commit",
        ).toBeGreaterThanOrEqual(inventory.minimumCount);
    });

    it("wires the guard and the confirm on every surface that owns one", () => {
        const missing = inventory.surfaces
            .filter((entry) => entry.guard === OWN_GUARD)
            .flatMap((entry) => {
                const source = readSource(entry.file);
                const gaps: string[] = [];
                if (!source.includes(GUARD_HOOK)) gaps.push(`${entry.file} does not use ${GUARD_HOOK}`);
                if (!source.includes(`<${CONFIRM_DIALOG}`)) gaps.push(`${entry.file} does not render ${CONFIRM_DIALOG}`);
                return gaps;
            });

        expect(missing).toEqual([]);
    });

    it("routes every owning surface's outside dismissal and confirm actions through the guard", () => {
        const missing = inventory.surfaces
            .filter((entry) => entry.guard === OWN_GUARD)
            .flatMap((entry) => {
                const source = readSource(entry.file);
                const gaps: string[] = [];
                const overlayRoutesDismissal = source.includes("onOpenChange={guard.onOpenChange}")
                    || (
                        (
                            source.includes("onOpenChange={handleOpenChange}")
                            || source.includes("children?.({ handleOpenChange")
                        )
                        && source.includes("guard.onOpenChange(")
                    );
                if (!overlayRoutesDismissal) gaps.push(`${entry.file} does not route outside dismissal through the guard`);
                if (!source.includes("open={guard.confirm.open}")) gaps.push(`${entry.file} does not expose guard confirm state`);
                if (!source.includes("onKeepEditing={guard.confirm.onKeepEditing}")) {
                    gaps.push(`${entry.file} does not wire the keep-editing action`);
                }
                if (!source.includes("guard.confirm.onDiscard")) gaps.push(`${entry.file} does not wire the discard action`);
                return gaps;
            });

        expect(missing).toEqual([]);
    });

    it("passes live draft state to every owning guard", () => {
        const invalid = inventory.surfaces
            .filter((entry) => entry.guard === OWN_GUARD)
            .flatMap((entry) => {
                const expression = guardDirtyExpression(entry);
                if (expression === null) return [`${entry.file} has no structured isDirty input`];
                if (expression.kind === ts.SyntaxKind.FalseKeyword) {
                    return [`${entry.file} permanently disables its draft guard`];
                }
                return [];
            });

        expect(invalid).toEqual([]);
    });

    it("has an executable owner scenario for every owning surface", () => {
        const owners = inventory.surfaces
            .filter((entry) => entry.guard === OWN_GUARD)
            .map((entry) => entry.file)
            .sort();

        expect(Object.keys(ownerScenarios).sort()).toEqual(owners);
    });

    it("resolves every delegating surface to a file that renders the guarded shell it names", () => {
        const broken = inventory.surfaces
            .filter((entry) => entry.guard !== OWN_GUARD)
            .flatMap((entry) => {
                const gaps: string[] = [];
                if (resolveOwner(entry) === null) {
                    gaps.push(`${entry.file} delegates to ${entry.guard}, which is not an inventory surface that owns a guard`);
                    return gaps;
                }
                if (!readSource(entry.file).includes(importSpecifier(entry.guard))) {
                    gaps.push(`${entry.file} does not import ${entry.guard}`);
                }
                return gaps;
            });

        expect(broken).toEqual([]);
    });

    it("covers the surfaces #1344 committed as the acceptance denominator", () => {
        const required = [
            "app/components/activity/activities/ActivityDialog.tsx",
            "app/components/activity/tasks/TaskDialog.tsx",
            "app/components/marketing/campaigns/EditCampaignSheet.tsx",
            "app/components/marketing/campaigns/NewCampaignDialog.tsx",
            "app/components/records/quick-edit/QuickEditSheetShell.tsx",
            "app/components/reports/GoalDialog.tsx",
            "app/components/reports/ScheduleDialog.tsx",
        ];

        expect(required.filter((file) => !byFile.has(file))).toEqual([]);
    });
});

describe.each(inventory.surfaces)("$surface draft guard", (entry) => {
    it("executes its real owner through dirty input, outside dismissal, keep editing, and discard", async () => {
        const owner = resolveOwner(entry);
        if (owner === null) throw new Error(`${entry.surface} has no guard owner`);
        const scenario = ownerScenarios[owner.file];
        if (!scenario) throw new Error(`${entry.surface} has no executable owner scenario`);
        const mounted = await scenario();

        try {
            await mounted.makeDirty();

            await act(async () => requiredDismiss()(false));
            expect(mounted.onClose, `${entry.surface} closed on outside dismissal`).not.toHaveBeenCalled();
            expect(requiredConfirm().open, `${entry.surface} did not ask before discarding`).toBe(true);

            await act(async () => requiredConfirm().onKeepEditing());
            expect(mounted.onClose, `${entry.surface} closed after keeping edits`).not.toHaveBeenCalled();
            expect(requiredConfirm().open).toBe(false);

            await act(async () => requiredDismiss()(false));
            await act(async () => requiredConfirm().onDiscard());
            expect(mounted.onClose, `${entry.surface} did not close after confirmed discard`).toHaveBeenCalledOnce();
        } finally {
            await mounted.unmount();
        }
    });
});
