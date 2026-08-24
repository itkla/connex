import {
    AcademicCapIcon,
    AdjustmentsHorizontalIcon,
    ArrowDownTrayIcon,
    ArrowsRightLeftIcon,
    ArrowTrendingDownIcon,
    BellAlertIcon,
    BellIcon,
    BoltIcon,
    BookOpenIcon,
    BriefcaseIcon,
    BuildingOffice2Icon,
    CalendarDaysIcon,
    CalendarIcon,
    ChartBarIcon,
    ChatBubbleLeftRightIcon,
    CheckBadgeIcon,
    CheckCircleIcon,
    ClipboardDocumentListIcon,
    CommandLineIcon,
    Cog6ToothIcon,
    CubeIcon,
    DocumentChartBarIcon,
    DocumentCheckIcon,
    DocumentTextIcon,
    EnvelopeIcon,
    FingerPrintIcon,
    FireIcon,
    FolderIcon,
    FunnelIcon,
    GlobeAltIcon,
    HomeIcon,
    InformationCircleIcon,
    LifebuoyIcon,
    LightBulbIcon,
    LinkIcon,
    LockClosedIcon,
    MagnifyingGlassIcon,
    MapIcon,
    MegaphoneIcon,
    PaperAirplaneIcon,
    PresentationChartLineIcon,
    RectangleGroupIcon,
    RectangleStackIcon,
    RocketLaunchIcon,
    ScaleIcon,
    ServerStackIcon,
    ShareIcon,
    ShieldCheckIcon,
    SparklesIcon,
    Square3Stack3DIcon,
    SwatchIcon,
    TableCellsIcon,
    TagIcon,
    UserGroupIcon,
    UsersIcon,
} from "@heroicons/react/24/outline";

/** Icon component shape shared by categories and articles. */
export type DocIcon = React.ComponentType<{ className?: string }>;

/**
 * A single documentation article. Its title, description, and content blocks
 * live in the `docs` i18n namespace under the owning category's namespace, keyed
 * by `articles.<slug>.{title,description,blocks}`.
 */
export type DocArticle = {
    slug: string;
    icon: DocIcon;
};

/**
 * A documentation category. `namespace` is the top-level `docs` i18n message key
 * that holds this category's `title`, `description`, `lead`, and per-article
 * content.
 */
export type DocCategory = {
    slug: string;
    namespace: string;
    icon: DocIcon;
    articles: DocArticle[];
};

/**
 * The full documentation tree. Ordering here drives sidebar order, breadcrumbs,
 * and prev/next navigation. Every entry maps to real product surface area
 * catalogued in the feature audit.
 */
export const docsCategories: DocCategory[] = [
    {
        slug: "getting-started",
        namespace: "DocsGettingStarted",
        icon: BookOpenIcon,
        articles: [
            { slug: "quickstart", icon: RocketLaunchIcon },
            { slug: "what-is-connex", icon: SparklesIcon },
            { slug: "workspaces-and-tenancy", icon: BuildingOffice2Icon },
            { slug: "navigating-connex", icon: MapIcon },
            { slug: "core-concepts", icon: LightBulbIcon },
        ],
    },
    {
        slug: "tutorials",
        namespace: "DocsTutorials",
        icon: AcademicCapIcon,
        articles: [
            { slug: "add-your-first-company", icon: BuildingOffice2Icon },
            { slug: "log-activity-and-warmth", icon: FireIcon },
            { slug: "build-your-pipeline", icon: FunnelIcon },
            { slug: "request-a-warm-intro", icon: ArrowsRightLeftIcon },
            { slug: "import-your-contacts", icon: ArrowDownTrayIcon },
        ],
    },
    {
        slug: "dashboard",
        namespace: "DocsDashboard",
        icon: HomeIcon,
        articles: [{ slug: "home-dashboard", icon: HomeIcon }],
    },
    {
        slug: "records",
        namespace: "DocsRecords",
        icon: RectangleStackIcon,
        articles: [
            { slug: "records-overview", icon: RectangleStackIcon },
            { slug: "companies", icon: BuildingOffice2Icon },
            { slug: "contacts", icon: UsersIcon },
            { slug: "deals-and-pipelines", icon: BriefcaseIcon },
            { slug: "products-and-line-items", icon: CubeIcon },
            { slug: "table-and-grid", icon: TableCellsIcon },
        ],
    },
    {
        slug: "relationship-intelligence",
        namespace: "DocsRelationshipIntelligence",
        icon: FireIcon,
        articles: [
            { slug: "warmth", icon: FireIcon },
            { slug: "decay-and-signals", icon: ArrowTrendingDownIcon },
            { slug: "connections-and-employment", icon: ArrowsRightLeftIcon },
            { slug: "ai-insights", icon: SparklesIcon },
        ],
    },
    {
        slug: "overview-suite",
        namespace: "DocsOverviewSuite",
        icon: PresentationChartLineIcon,
        articles: [
            { slug: "analytics", icon: ChartBarIcon },
            { slug: "reports-and-goals", icon: DocumentChartBarIcon },
            { slug: "relationship-map", icon: MapIcon },
            { slug: "calendar", icon: CalendarIcon },
            { slug: "introductions", icon: ArrowsRightLeftIcon },
            { slug: "warm-intro-paths", icon: ShareIcon },
        ],
    },
    {
        slug: "marketing",
        namespace: "DocsMarketing",
        icon: MegaphoneIcon,
        articles: [{ slug: "campaigns", icon: MegaphoneIcon }],
    },
    {
        slug: "activity",
        namespace: "DocsActivity",
        icon: ChatBubbleLeftRightIcon,
        articles: [
            { slug: "notes", icon: DocumentTextIcon },
            { slug: "tasks", icon: CheckCircleIcon },
            { slug: "activities", icon: ChatBubbleLeftRightIcon },
            { slug: "calendar-events", icon: CalendarDaysIcon },
        ],
    },
    {
        slug: "library",
        namespace: "DocsLibrary",
        icon: FolderIcon,
        articles: [
            { slug: "tags", icon: TagIcon },
            { slug: "files", icon: FolderIcon },
            { slug: "saved-views-and-segments", icon: RectangleGroupIcon },
            { slug: "documents-and-approvals", icon: DocumentCheckIcon },
        ],
    },
    {
        slug: "data",
        namespace: "DocsData",
        icon: MagnifyingGlassIcon,
        articles: [
            { slug: "search", icon: MagnifyingGlassIcon },
            { slug: "filters-and-bulk", icon: FunnelIcon },
            { slug: "import-and-export", icon: ArrowDownTrayIcon },
            { slug: "custom-fields", icon: AdjustmentsHorizontalIcon },
        ],
    },
    {
        slug: "collaboration",
        namespace: "DocsCollaboration",
        icon: UserGroupIcon,
        articles: [
            { slug: "invites-and-links", icon: EnvelopeIcon },
            { slug: "sharing-and-permissions", icon: LockClosedIcon },
            { slug: "notifications-and-mentions", icon: BellAlertIcon },
        ],
    },
    {
        slug: "settings",
        namespace: "DocsSettings",
        icon: Cog6ToothIcon,
        articles: [
            { slug: "members-and-roles", icon: UserGroupIcon },
            { slug: "sign-in-and-security", icon: FingerPrintIcon },
            { slug: "workflows-and-automation", icon: BoltIcon },
            { slug: "notification-settings", icon: BellIcon },
            { slug: "connected-accounts", icon: LinkIcon },
            { slug: "connected-capture", icon: EnvelopeIcon },
            { slug: "privacy-and-data-requests", icon: ScaleIcon },
            { slug: "audit-logs", icon: ClipboardDocumentListIcon },
        ],
    },
    {
        slug: "operations",
        namespace: "DocsOperations",
        icon: ServerStackIcon,
        articles: [
            { slug: "what-connex-is-today", icon: InformationCircleIcon },
            { slug: "whats-shipped-and-whats-preview", icon: CheckBadgeIcon },
            { slug: "deployment-profiles", icon: Square3Stack3DIcon },
            { slug: "operational-boundaries", icon: ShieldCheckIcon },
            { slug: "diagnostics-and-support", icon: LifebuoyIcon },
            { slug: "deliverability-basics", icon: PaperAirplaneIcon },
        ],
    },
    {
        slug: "preferences",
        namespace: "DocsPreferences",
        icon: SwatchIcon,
        articles: [
            { slug: "theming-and-appearance", icon: SwatchIcon },
            { slug: "language-and-i18n", icon: GlobeAltIcon },
            { slug: "keyboard-and-shortcuts", icon: CommandLineIcon },
            { slug: "tips-and-quirks", icon: SparklesIcon },
        ],
    },
];

/** A category paired with one of its articles, resolved from a URL slug pair. */
export type ResolvedArticle = {
    category: DocCategory;
    article: DocArticle;
};

/** Look up a category by its URL slug. */
export function getCategory(slug: string): DocCategory | undefined {
    return docsCategories.find((category) => category.slug === slug);
}

/** Resolve a `category/article` slug pair to its registry entries. */
export function getArticle(categorySlug: string, articleSlug: string): ResolvedArticle | undefined {
    const category = getCategory(categorySlug);
    if (!category) return undefined;
    const article = category.articles.find((entry) => entry.slug === articleSlug);
    if (!article) return undefined;
    return { category, article };
}

/** Every article across all categories, in registry order. */
export function allArticles(): ResolvedArticle[] {
    return docsCategories.flatMap((category) =>
        category.articles.map((article) => ({ category, article })),
    );
}

/** The article immediately before/after the given one, for prev/next links. */
export function articleNeighbors(
    categorySlug: string,
    articleSlug: string,
): { previous: ResolvedArticle | null; next: ResolvedArticle | null } {
    const flat = allArticles();
    const index = flat.findIndex(
        (entry) => entry.category.slug === categorySlug && entry.article.slug === articleSlug,
    );
    if (index === -1) return { previous: null, next: null };
    return {
        previous: index > 0 ? flat[index - 1] : null,
        next: index < flat.length - 1 ? flat[index + 1] : null,
    };
}

/** The i18n key (within a category namespace) for an article's content blocks. */
export function articleBlocksKey(articleSlug: string): string {
    return `articles.${articleSlug}.blocks`;
}

/** The i18n key (within a category namespace) for an article field. */
export function articleFieldKey(articleSlug: string, field: "title" | "description"): string {
    return `articles.${articleSlug}.${field}`;
}
