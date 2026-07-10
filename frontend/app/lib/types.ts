// type definitions becaue the api.ts library was getting too bloated

export type Page<T> = {
    items: T[];
    total: number;
};

export type PageParams = {
    page?: number;
    size?: number;
    q?: string;
    sort?: string;
    dir?: 'asc' | 'desc';
};

export type ContactsPageParams = PageParams & {
    companies?: string[];
    titles?: string[];
    noCompany?: boolean;
};

export type CompaniesPageParams = PageParams & {
    industry?: string[];
    noIndustry?: boolean;
    ids?: number[];
};

export type ActivitiesPageParams = PageParams & {
    personId?: number;
    dealId?: number;
    createdById?: number;
};

/** One record that a bulk operation could not apply, with its index in the request and the reason. */
export type BulkOperationError = {
    rowIndex: number;
    reason: string;
};

/** Outcome of a bulk record mutation: per-record success/failure counts plus per-failure reasons. */
export type BulkOperationResult = {
    succeeded: number;
    failed: number;
    errors: BulkOperationError[];
};

export type ImportEntity = 'persons' | 'companies' | 'deals';

export type ImportDuplicateAction = 'fill_empty' | 'skip' | 'overwrite';

export type ImportColumnMapping = {
    column: string;
    field?: string | null;
    createCustomField?: boolean;
    customFieldType?: string | null;
    customFieldLabel?: string | null;
};

export type ImportRequest = {
    rows: Record<string, string>[];
    mapping: ImportColumnMapping[];
    onDuplicate?: ImportDuplicateAction;
    links?: Record<number, number>;
};

export type ImportRowStatus = 'create' | 'match' | 'skip' | 'invalid';

export type ImportRowAnalysis = {
    rowIndex: number;
    status: ImportRowStatus;
    matchedId?: number | null;
    matchedLabel?: string | null;
    errors?: string[] | null;
};

export type ImportPreviewResult = {
    total: number;
    toCreate: number;
    toUpdate: number;
    toSkip: number;
    invalid: number;
    rows: ImportRowAnalysis[];
};

export type ImportRowError = {
    rowIndex: number;
    reason: string;
};

export type ImportResult = {
    created: number;
    updated: number;
    skipped: number;
    failed: ImportRowError[];
};

export type PersonFacets = {
    companies: string[];
    titles: string[];
    hasNoCompany: boolean;
};

export type CompanyFacets = {
    industries: string[];
    hasNoIndustry: boolean;
};

export type TemperatureBand = 'hot' | 'warm' | 'cool' | 'cold';
export type TemperatureTrend = 'rising' | 'steady' | 'cooling';

/**
 * Computed relationship "temperature" for a contact or company. Derived on read by the backend
 * from interaction recency/frequency; consumed by the map, dashboard, and records tables.
 */
export type RelationshipTemperature = {
    id: number;
    score: number;
    band: TemperatureBand;
    trend: TemperatureTrend;
    lastTouchAt?: string | null;
    daysSinceTouch?: number | null;
    touchCount: number;
    /** Predicted date the relationship decays into "cold" if untouched; null if already cold. */
    goesColdAt?: string | null;
    /** Whole days until {@link goesColdAt}; null if already cold or no activity. */
    daysUntilCold?: number | null;
};

export type ReplayGranularity = 'weekly' | 'monthly';

/** A contact's state within a single time-travel replay (#48) frame. */
export type ReplayContactState = {
    id: number;
    band: TemperatureBand;
    /** Company the contact worked at as of this frame, or null/undefined if none. */
    employerId?: number | null;
};

/** A company's state within a single replay frame. */
export type ReplayCompanyState = {
    id: number;
    band: TemperatureBand;
};

export type ReplayDealResolution = 'open' | 'won' | 'lost';

/** A deal's state within a single replay frame. */
export type ReplayDealState = {
    id: number;
    resolution: ReplayDealResolution;
};

/**
 * One frame of the time-travel replay: the contacts, companies, and deals that existed as of
 * {@link asOf}, each with its as-of warmth band, employer, or outcome. Entities absent from these
 * lists did not exist yet (or no longer exist) at this instant.
 */
export type ReplayFrame = {
    /** The frame's calendar date as a UTC {@code yyyy-MM-dd} string. */
    asOf: string;
    contacts: ReplayContactState[];
    companies: ReplayCompanyState[];
    deals: ReplayDealState[];
};

/** The full time-travel replay payload: an ordered series of frames. */
export type MapReplay = {
    frames: ReplayFrame[];
};

/** Query parameters for the replay endpoint. Dates are ISO {@code yyyy-MM-dd}. */
export type ReplayParams = {
    from: string;
    to: string;
    granularity?: ReplayGranularity;
};

export type DealRiskSeverity = 'high' | 'medium' | 'low';
/** Overall risk band for a deal; {@code none} when the deal is not at risk. */
export type DealRiskLevel = DealRiskSeverity | 'none';
export type DealRiskFactorCode =
    | 'close_overdue'
    | 'closing_soon_quiet'
    | 'stalled'
    | 'stakeholder_cold'
    | 'no_stakeholders';

/** One deterministic risk signal on a deal; {@link params} feed the localized sentence. */
export type DealRiskFactor = {
    code: DealRiskFactorCode;
    severity: DealRiskSeverity;
    params: Record<string, unknown>;
};

/**
 * Computed risk assessment for a single open deal. Derived on read by the backend from the deal's
 * timeline, expected close date, and stakeholder warmth; consumed by the deal card and detail page.
 */
export type DealRisk = {
    dealId: number;
    level: DealRiskLevel;
    score: number;
    factors: DealRiskFactor[];
    assessedAt: string;
};

/** Why an AI deal brief is unavailable: AI is not configured for the org, or the provider call failed. */
export type DealBriefUnavailableReason = 'not_configured' | 'provider_error';

/**
 * AI-generated "before you call" brief for a deal, or a graceful unavailability result. Presentation-only:
 * the deterministic risk/warmth signals remain the source of truth. {@code warnings} counts demasking
 * integrity warnings; nonzero means parts of the brief may reference unknown placeholders.
 */
export type DealBrief = {
    dealId: number;
    available: boolean;
    brief?: string | null;
    generatedAt?: string | null;
    warnings: number;
    reason?: DealBriefUnavailableReason | null;
};

/** One stint in a contact's employment history. The row with {@code current} is the present company. */
export type PersonEmployment = {
    id: number;
    personId: number;
    companyId?: number | null;
    companyName?: string | null;
    title?: string | null;
    startedAt?: string | null;
    endedAt?: string | null;
    current: boolean;
};

/** A contact who recently changed companies — the "recently moved" feed row. */
export type JobMove = {
    personId: number;
    personName: string;
    personImageUrl?: string | null;
    fromCompanyId?: number | null;
    fromCompanyName?: string | null;
    toCompanyId?: number | null;
    toCompanyName?: string | null;
    movedAt?: string | null;
};

export type ConnectionType = 'knows' | 'colleague' | 'former_colleague' | 'friend';

/** A contact's connection to another contact in the warm-intro graph (from that contact's view). */
export type PersonConnection = {
    id: number;
    personId: number;
    personName: string;
    companyId?: number | null;
    companyName?: string | null;
    type: string;
    strength: number;
    note?: string | null;
};

export type ConnectionPayload = {
    targetPersonId: number;
    type?: string;
    strength?: number;
    note?: string;
};

/** One contact along a warm-introduction path. */
export type IntroPathStep = {
    personId: number;
    personName?: string | null;
    companyName?: string | null;
    connectionType?: string | null;
    engaged: boolean;
};

/** The warmest introduction path to a contact: ordered from an engaged contact to the target. */
export type IntroPath = {
    reachable: boolean;
    directlyKnown: boolean;
    steps: IntroPathStep[];
};

/** Why a reverse-introduction is suggested. */
export type IntroReason = 'mutual_connections' | 'shared_company';

/**
 * A suggested reverse introduction: a pair of contacts the team is positioned to introduce because
 * it knows both but they are not connected to each other. The "give side" of the graph (issue #43).
 */
export type IntroSuggestion = {
    personAId: number;
    personAName: string;
    personATitle?: string | null;
    personACompany?: string | null;
    personAImageUrl?: string | null;
    personAWarmth?: TemperatureBand | null;
    personBId: number;
    personBName: string;
    personBTitle?: string | null;
    personBCompany?: string | null;
    personBImageUrl?: string | null;
    personBWarmth?: TemperatureBand | null;
    score: number;
    reasons: string[];
    mutualConnections: number;
    sharedCompany?: string | null;
};

/** A recorded introduction in the lineage feed ("intros you've made"). */
export type IntroductionRecord = {
    id: number;
    personAId: number;
    personAName: string;
    personACompany?: string | null;
    personAImageUrl?: string | null;
    personBId: number;
    personBName: string;
    personBCompany?: string | null;
    personBImageUrl?: string | null;
    introducerId?: number | null;
    introducerName?: string | null;
    note?: string | null;
    /** Server-resolved @/# references parsed from `note`; read-only, drives chip rendering. */
    references?: NoteReference[];
    introducedAt: string;
};

/** Request body to record an introduction, or dismiss a suggested pair. */
export type IntroductionPayload = {
    personAId: number;
    personBId: number;
    note?: string;
};

export type User = {
    id: number;
    username: string;
    displayName: string;
    email: string;
    createdAt: string;
    updatedAt: string;
    lastLoginAt?: string;
    profilePictureUrl?: string;
    timezone: string;
};

export type LoginPayload = {
    username: string;
    password: string;
};

export type RegisterPayload = {
    username: string;
    password: string;
    displayName: string;
    email: string;
    timezone?: string;
};

export type AuthResponse = {
    message: string;
};

export type Passkey = {
    credentialId: string;
    label: string;
    transports: string[];
    createdAt: string;
    lastUsedAt: string | null;
};

export type ForgotPasswordPayload = {
    email: string;
};

export type ResetPasswordPayload = {
    token: string;
    newPassword: string;
};

export type ResetTokenValidation = {
    valid: boolean;
};

export type UpdateUserPayload = {
    username: string;
    displayName: string;
    email: string;
    profilePictureUrl?: string;
};

export type CreateContactPayload = {
    name: string;
    email: string;
    phone: string;
    title: string;
    companyId?: number;
};

export type CreateTaskPayload = {
    description: string;
    completed?: boolean;
    dueDate?: string;
    assignedToId: number;
    personId?: number;
    dealId?: number;
};

export type ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'];


export type CreateActivityPayload = {
    type: string;
    subject: string;
    notes?: string;
    personId?: number;
    dealId?: number;
    createdById: number;
    timestamp?: string;
};

export type UpdateActivityPayload = {
    type: string;
    subject: string;
    createdById: number;
    notes?: string;
    personId?: number | null;
    dealId?: number | null;
    timestamp?: string;
};

/** Workflow status of a task, used as the Kanban board columns. `done` mirrors `completed`. */
export type TaskStatus = 'todo' | 'in_progress' | 'done';

export type Task = {
    id: number;
    description: string;
    completed: boolean;
    /** Kanban workflow column; kept in lockstep with `completed` (done ⇔ completed) by the server. */
    status: TaskStatus;
    /** Manual sort order within the status column (0-based, contiguous). */
    position: number;
    dueDate?: string;
    assignedToId: number;
    personId?: number | null;
    dealId?: number | null;
    workspaceId?: number;
    createdAt: string;
    updatedAt: string;
    /** Server-resolved @/# references parsed from `description`; read-only, drives chip rendering. */
    references?: NoteReference[];
};

export type Activity = {
    id: number;
    type: string;
    subject: string;
    notes?: string;
    personId?: number | null;
    dealId?: number | null;
    createdById: number;
    timestamp?: string;
    /** Server-resolved @/# references parsed from `notes`; read-only, drives chip rendering. */
    references?: NoteReference[];
};

export type DealSummary = {
    id: number;
    name: string;
    value: number;
    currency: string;
    status: string;
    expectedCloseDate?: string | null;
    stageName?: string | null;
    pipelineName?: string | null;
    companyName?: string | null;
    ownerName?: string | null;
};

export type NoteReferenceType =
    | "user"
    | "person"
    | "deal"
    | "company"
    | "note"
    | "file"
    | "task"
    | "activity";

export type NoteReference = {
    type: NoteReferenceType;
    id: number;
    label: string;
};

export type NoteVisibility = "private" | "workspace";

export type Note = {
    id: number;
    content: string;
    title?: string | null;
    visibility?: NoteVisibility;
    author: number;
    person?: number | null;
    deal?: number | null;
    createdAt: string;
    updatedAt: string;
    references?: NoteReference[];
};

export type NoteDraft = {
    content: string;
    title?: string | null;
    visibility?: NoteVisibility;
    author: number;
    person?: number | null;
    deal?: number | null;
};

export type CreateNotePayload = {
    content: string;
    title?: string | null;
    visibility?: NoteVisibility;
    author: number;
    person?: number | null;
    deal?: number | null;
};

export type UpdateNotePayload = {
    content?: string;
    title?: string | null;
    visibility?: NoteVisibility;
    author?: number;
    person?: number | null;
    deal?: number | null;
};

export type Company = {
    id: number;
    workspaceId?: number;
    name: string;
    website: string;
    industry: string;
    phone: string;
    address: string;
    logoUrl: string;
    personIds?: number[];
    dealIds?: number[];
    tagIds?: number[];
    people?: Contact[];
    deals?: Deal[];
    tags?: Tag[];
    createdAt: string;
    updatedAt: string;
};

// metrics for a company, filled via relationship traversal
export type CompanyMetrics = {
    persons: Contact[];
    relatedUsers: User[];
    pastRevenue: number;
    projectedRevenue: number;
    currency: string;
    numDeals: number;
    numTasks: number;
    numActivities: number;
    numNotes: number;
    weeklyEngagement: {
        weekStart: number;
        count: number;
        activities: number;
        tasks: number;
        notes: number;
    }[];
};

// loading state for data fetching
export type LoadStatus = 'idle' | 'loading' | 'ready' | 'error';

// payload to create a new company
export type CreateCompanyPayload = {
    name: string;
    website?: string;
    industry?: string;
    phone?: string;
    address?: string;
    logoUrl?: string;
};

// payload to update a company
export type UpdateCompanyPayload = {
    name?: string;
    website?: string;
    industry?: string;
    phone?: string;
    address?: string;
    logoUrl?: string;
};

export type Contact = {
    id: number;
    workspaceId?: number;
    name: string;
    email: string;
    phone: string;
    company?: Company;
    companyId?: number;
    tags?: Tag[];
    tagIds?: number[];
    deals?: Deal[];
    tasks?: Task[];
    activities?: Activity[];
    notes?: Note[];
    title: string;
    imageUrl: string;
    createdAt: string;
    updatedAt: string;
    /** Engine-evaluation opt-outs (issue #358); read-only, set via the evaluation endpoint. */
    riskExcluded?: boolean;
    introExcluded?: boolean;
};

export type Deal = {
    id: number;
    name: string;
    value: number;
    actualValue: number;
    currency: string;
    pipeline: number | null;
    stage: number | null;
    /** Manual sort order within the stage column (0-based, contiguous). */
    position: number;
    company: number | null;
    workspaceId?: number;
    ownerId?: number | null;
    expectedCloseDate?: string;
    closedAt?: string;
    closedReason?: string;
    /** Server-resolved @/# references parsed from `closedReason`; read-only, drives chip rendering. */
    references?: NoteReference[];
    /** Outcome when closed: true = won, false = lost, null/undefined = open. closedAt follows this. */
    won?: boolean | null;
    /** Deal-risk evaluation opt-out (issue #358); read-only, set via the evaluation endpoint. */
    riskExcluded?: boolean;
    createdAt: string;
    updatedAt: string;
};

export type UpdateContactEvaluationPayload = {
    riskExcluded?: boolean;
    introExcluded?: boolean;
};

export type UpdateDealEvaluationPayload = {
    riskExcluded: boolean;
};

export type CreateDealPayload = {
    name: string;
    value: number;
    actualValue: number;
    currency: string;
    pipeline: number | null;
    stage: number | null;
    company?: number | null;
    ownerId?: number | null;
    expectedCloseDate?: string;
    closedAt?: string;
    closedReason?: string;
    won?: boolean | null;
};

export type UpdateDealPayload = {
    name: string;
    value: number;
    actualValue: number;
    currency: string;
    pipeline: number;
    stage: number;
    company?: number | null;
    ownerId?: number | null;
    expectedCloseDate?: string | null;
    closedAt?: string | null;
    closedReason?: string | null;
    won?: boolean | null;
};

export type Pipeline = {
    id: number;
    workspaceId?: number;
    name: string;
    createdAt: string;
    updatedAt: string;
};

/** One record of a deal reaching a stage. Append-only, so a re-entered stage has multiple entries. */
export type DealStageHistory = {
    id: number;
    stageId: number;
    achievedAt: string;
};

export type CreatePipelinePayload = {
    name?: string;
    stages?: { name: string; success?: boolean; failure?: boolean }[];
};

export type CreateStagePayload = {
    name: string;
    position: number;
    success?: boolean;
    failure?: boolean;
};

export type UpdateStagePayload = {
    name: string;
    position: number;
    success?: boolean;
    failure?: boolean;
};

export type UpdatePipelinePayload = {
    name?: string;
};

export type PipelineMetrics = {
    numStages: number;
    numDeals: number;
    relatedUsers: User[];
    stages: StageMetrics[];
};

export type StageMetrics = {
    stage: Stage;
    numDeals: number;
    numTasks: number;
    numActivities: number;
    numNotes: number;
    weeklyEngagement: {
        weekStart: number;
        count: number;
        activities: number;
        tasks: number;
        notes: number;
    }[];
};

export type Stage = {
    id: number;
    name: string;
    pipeline: number;
    position: number;
    success: boolean;
    failure: boolean;
};

export type Tag = {
    id: number;
    name: string;
    color: string;
    createdAt: string;
    updatedAt: string;
};

export type CreateTagPayload = {
    name: string;
    color: string;
};

export type UpdateTagPayload = {
    name?: string;
    color?: string;
};

export type CustomFieldEntityType = 'company' | 'person' | 'deal';

export type CustomFieldType =
    | 'text'
    | 'textarea'
    | 'number'
    | 'date'
    | 'boolean'
    | 'select'
    | 'url';

export type CustomFieldOption = {
    key: string;
    label: string;
};

export type CustomFieldDataClassification = 'standard' | 'sensitive' | 'special_care';

export type CustomFieldDefinition = {
    id: number;
    workspaceId: number;
    entityType: CustomFieldEntityType;
    fieldKey: string;
    label: string;
    fieldType: CustomFieldType;
    dataClassification: CustomFieldDataClassification;
    options: CustomFieldOption[] | null;
    required: boolean;
    position: number;
    archived: boolean;
    createdAt: string;
    updatedAt: string;
};

export type CustomFieldInput = {
    entityType: CustomFieldEntityType;
    fieldKey: string;
    label: string;
    fieldType: CustomFieldType;
    dataClassification?: CustomFieldDataClassification;
    options?: CustomFieldOption[] | null;
    required?: boolean;
    position?: number;
    archived?: boolean;
};

export type CustomFieldEntry = {
    definitionId: number;
    fieldKey: string;
    label: string;
    fieldType: CustomFieldType;
    options: CustomFieldOption[] | null;
    required: boolean;
    value: CustomFieldCellValue;
};

export type CustomFieldCellValue = string | number | boolean | null;

export type EntityCustomFieldValues = Record<string, Record<string, CustomFieldCellValue>>;

export type SavedViewRecordType = "company" | "person" | "deal";

export type SegmentMatch = "all" | "any";

export type SegmentCondition = {
    type: "predicate" | "field";
    key?: string;
    days?: number;
    field?: string;
    op?: string;
    value?: string;
    values?: string[];
    negate?: boolean;
};

export type SegmentDefinition = {
    match: SegmentMatch;
    conditions: SegmentCondition[];
    groups?: SegmentDefinition[];
    negate?: boolean;
};

export type SavedViewConfig = {
    filters?: Record<string, string[]>;
    query?: string;
    sortKey?: string | null;
    sortDirection?: "asc" | "desc";
    segments?: SegmentDefinition;
};

export type SegmentResult = {
    ids: number[];
};

export type SegmentTag = {
    id: number;
    name: string;
    color?: string;
};

export type SegmentFields = {
    industries: string[];
    tags: SegmentTag[];
};

export type SavedView = {
    id: number;
    recordType: SavedViewRecordType;
    name: string;
    config: SavedViewConfig;
    position: number;
};

export type SavedViewInput = {
    recordType: SavedViewRecordType;
    name: string;
    config: SavedViewConfig;
    position?: number;
};

export type DashboardWidgetType =
    | "overview"
    | "pipeline"
    | "tasks"
    | "atRiskDeals"
    | "coolingRelationships"
    | "recentMoves"
    | "introOpportunities"
    | "recentFiles"
    | "recentActivity"
    | "companyWarmth"
    | "warmthDistribution"
    | "closingSoon"
    | "recentNotes"
    | "notifications"
    | "quickActions"
    | "analyticsKpis"
    | "revenueTrend"
    | "winRate"
    | "pipelineValue"
    | "stageFunnel"
    | "activityVolume"
    | "teamLeaderboard";

export type DashboardWidgetSpan = 1 | 2;

export type DashboardWidgetInstance = {
    id: string;
    type: DashboardWidgetType;
    span: DashboardWidgetSpan;
};

export type DashboardLayout = {
    version: 1;
    widgets: DashboardWidgetInstance[];
};

/**
 * Response from `GET/PUT /api/dashboard-layout`. `layout` is absent when the current user has
 * never customized their dashboard (the client then falls back to the default layout). The raw
 * `layout` is untrusted opaque JSON and must be normalized before use.
 */
export type DashboardLayoutResponse = {
    layout?: DashboardLayout | null;
    updatedAt?: string | null;
};

export type UpdateContactPayload = {
    name?: string;
    email?: string;
    phone?: string;
    title?: string;
    companyId?: number | null;
    imageUrl?: string;
};

export type ContactFilters = {
    companyId?: number;
    tagId?: number;
    dealId?: number;
};

export type ContactTag = {
    id: number;
    contactId: number;
    tagId: number;
    tag?: Tag;
    createdAt: string;
    updatedAt: string;
};

export type UpdateTaskPayload = {
    description?: string;
    completed?: boolean;
    dueDate?: string;
    assignedToId?: number;
    personId?: number;
    dealId?: number;
};

export type NotificationState = 'active' | 'unread' | 'history' | 'all';

export type Notification = {
    id: number;
    workspaceId?: number;
    workspaceName?: string | null;
    type: string;
    category: string;
    severity: 'info' | 'warning' | 'critical' | string;
    templateVersion: number;
    title: string;
    body?: string | null;
    actorId?: number | null;
    actorLabel?: string | null;
    sourceType?: string | null;
    sourceId?: number | null;
    sourceLabel?: string | null;
    contextType?: string | null;
    contextId?: number | null;
    contextLabel?: string | null;
    actionUrl?: string | null;
    data?: Record<string, unknown> | null;
    triggeredAt: string;
    readAt?: string | null;
    dismissedAt?: string | null;
    resolvedAt?: string | null;
    snoozedUntil?: string | null;
    createdAt: string;
    updatedAt: string;
};

export type NotificationCounts = {
    unread: number;
};

export type NotificationParams = {
    state?: NotificationState;
    category?: string;
    contextType?: string;
    contextId?: number;
    page?: number;
    size?: number;
};

export type NotificationPreference = {
    type: string;
    channel: string;
    enabled: boolean;
};

export type AttachmentEntityType = 'company' | 'person' | 'deal' | 'user';

export type Attachment = {
    id: number;
    entityType: string;
    entityId: number;
    entityLabel?: string;
    fileName: string;
    url: string;
    contentType?: string;
    size?: number;
    uploadedBy?: number;
    uploadedByName?: string;
    tags?: Tag[];
    createdAt: string;
    updatedAt: string;
};

export type CreateAttachmentPayload = {
    entityType: string;
    entityId: number;
    fileName: string;
    url: string;
    contentType?: string;
    size?: number;
};

export type FacetCount = {
    key: string;
    count: number;
};

export type AttachmentsPageParams = PageParams & {
    types?: string[];    // filter by owning entity type
    kinds?: string[];    // filter by derived file kind
    tagIds?: number[];   // filter by attached tag
    orphaned?: boolean;  // only files whose owning record is gone
};

export type AttachmentFacets = {
    sources: FacetCount[];
    kinds: FacetCount[];
    tags: FacetCount[];
    orphaned: number;
    total: number;
    totalSize: number;
};

export type DealFilterParams = {
    status?: 'open' | 'closed' | 'won' | 'lost';
    stageId?: number;
    pipelineId?: number;
    companyId?: number;
    currency?: string;
};

export type DealsPageParams = PageParams & DealFilterParams;

export type DealCurrencyMetrics = {
    currency: string;
    openCount: number;
    openValue: number;
    closedCount: number;
    closedForecast: number;
    closedRevenue: number;
    wonCount: number;
    lostCount: number;
};

export type DealMetrics = {
    byCurrency: DealCurrencyMetrics[];
    totalCount: number;
};

export type DealFacets = {
    status: FacetCount[];
    stages: FacetCount[];
    pipelines: FacetCount[];
    companies: FacetCount[];
    currencies: FacetCount[];
};

/** One month's aggregated total; {@code month} is 1-12 (MySQL MONTH()). */
export type DealMonthTotal = {
    year: number;
    month: number;
    total: number;
};

/** Server-computed monthly revenue series for the deals page trend chart. */
export type DealRevenueSeries = {
    closed: DealMonthTotal[];
    projected: DealMonthTotal[];
};

/** Per-stage open/closed rollup for the deals page stage-distribution chart. */
export type DealStageDistribution = {
    stageId: number | null;
    pipelineId: number | null;
    openCount: number;
    openValue: number;
    closedCount: number;
    closedValue: number;
};

export type UploadedFile = {
    url: string;
    fileName: string;
    contentType: string;
    size: number;
};

export type SearchResults = {
    companies: Company[];
    people: Contact[];
    deals: Deal[];
    pipelines: Pipeline[];
    tags: Tag[];
    activities: Activity[];
    notes: Note[];
    tasks: Task[];
    users: User[];
    attachments: Attachment[];
};

export type AuditChange = {
    old: unknown;
    new: unknown;
};

export type WorkspaceRole = "owner" | "admin" | "member";

export type OrgRole = "owner" | "admin";

export type Workspace = {
    id: number;
    name: string;
    slug: string;
    role: WorkspaceRole;
    orgId: number;
    orgName: string;
    orgRole: OrgRole | null;
};

export type OrgMember = {
    id: number;
    username: string;
    displayName: string;
    email: string;
    profilePictureUrl: string | null;
    orgRole: OrgRole;
};

export type MyWorkspaces = {
    workspaces: Workspace[];
    activeWorkspaceId: number | null;
};

export type WorkspaceMember = {
    id: number;
    username: string;
    displayName: string;
    email: string;
    profilePictureUrl?: string;
    role: string;
    roleId?: number | null;
    status?: string;
};

export type CustomRole = {
    id: number;
    name: string;
    permissions: string[];
};

export type RuleTrigger = {
    type: string;
    events?: string[];
    targetStageId?: number;
    throttleMinutes?: number;
    cadence?: string;
};

export type RuleAction = {
    type: string;
    title?: string;
    body?: string;
    activityType?: string;
    tagId?: number;
    dueInDays?: number;
    severity?: string;
    targetUserId?: number;
    targetStageId?: number;
};

export type RuleNamedOption = {
    id: number;
    name: string;
};

export type RuleStageOption = {
    id: number;
    name: string;
    pipeline: string;
};

/** Option data for the builder's id-typed value pickers (deal stage/owner, person company). */
export type RuleBuilderOptions = {
    stages: RuleStageOption[];
    owners: RuleNamedOption[];
    companies: RuleNamedOption[];
};

export type RuleRecordLabel = {
    id: number;
    label: string;
};

export type RulePreview = {
    matchCount: number;
    sample: RuleRecordLabel[];
};

export type Rule = {
    id: number;
    name: string;
    description?: string;
    enabled: boolean;
    recordType: string;
    trigger: RuleTrigger;
    condition?: SegmentDefinition | null;
    actions: RuleAction[];
    executionMode: "user" | "system";
    runAsUserId?: number | null;
    createdById?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type RuleRequest = {
    name: string;
    description?: string;
    enabled?: boolean;
    recordType: string;
    trigger: RuleTrigger;
    condition?: SegmentDefinition | null;
    actions: RuleAction[];
    executionMode: "user" | "system";
};

export type Share = {
    workspaceId: number;
    workspaceName: string;
    canEdit: boolean;
    createdAt: string;
};

export type WorkspaceInvite = {
    id: number;
    email: string;
    role: WorkspaceRole;
    status: string;
    token: string;
    invitedByLabel: string | null;
    expiresAt: string;
    createdAt: string;
};

/**
 * The outcome of inviting someone by email. Exactly one field is set: `invite`
 * for an emailed token invite (a new address), or `member` when the address
 * belongs to an existing Connex user, who is added as a pending member and
 * notified in-app instead.
 */
export type InviteResult = {
    invite: WorkspaceInvite | null;
    member: WorkspaceMember | null;
};

export type InvitePreview = {
    workspaceId: number;
    workspaceName: string;
    email: string;
    role: WorkspaceRole;
    invitedByLabel: string | null;
    status: string;
    valid: boolean;
};

export type WorkspaceInviteLink = {
    id: number;
    token: string;
    role: WorkspaceRole;
    expiresAt: string;
    maxUses: number | null;
    usedCount: number;
    revoked: boolean;
    createdByLabel: string | null;
    createdAt: string;
};

export type InviteLinkPreview = {
    workspaceId: number;
    workspaceName: string;
    role: WorkspaceRole;
    valid: boolean;
};

export type AuditLogEntry = {
    id: number;
    action: string;
    entityType: string | null;
    entityId: number | null;
    actorId: number | null;
    actorLabel: string | null;
    targetLabel: string | null;
    outcome: string | null;
    summary: string | null;
    changes?: Record<string, AuditChange> | null;
    context?: Record<string, unknown> | null;
    ipAddress?: string | null;
    userAgent?: string | null;
    sessionId?: string | null;
    requestId?: string | null;
    createdAt: string;
    currentActorLabel?: string | null;
};

export type AuditLogParams = PageParams & {
    entityType?: string;
    entityId?: number;
    limit?: number;
    offset?: number;
};

export type MailConfig = {
    enabled: boolean;
    host: string | null;
    port: number | null;
    username: string | null;
    fromAddress: string | null;
    fromName: string | null;
    starttls: boolean;
    ssl: boolean;
    auth: boolean;
    hasPassword: boolean;
    configured: boolean;
    updatedAt: string | null;
};

export type MailConfigRequest = {
    enabled: boolean;
    host?: string | null;
    port?: number | null;
    username?: string | null;
    password?: string | null;
    fromAddress?: string | null;
    fromName?: string | null;
    starttls: boolean;
    ssl: boolean;
    auth: boolean;
};

export type MailTestResult = {
    success: boolean;
    error: string | null;
};

export type SsoProtocol = "oidc" | "saml";

export type SsoDiscovery = {
    available: boolean;
    registrationId: string | null;
    protocol: SsoProtocol | null;
    enforced: boolean;
};

export type SsoConnectionDto = {
    configured: boolean;
    protocol: SsoProtocol | null;
    enabled: boolean;
    enforceSso: boolean;
    jitWorkspaceId: number | null;
    defaultRole: string;
    oidcIssuer: string | null;
    oidcClientId: string | null;
    hasClientSecret: boolean;
    oidcScopes: string | null;
    samlIdpEntityId: string | null;
    samlSsoUrl: string | null;
    samlIdpMetadataXml: string | null;
    samlIdpX509: string | null;
    samlSpCertificate: string | null;
    domains: string[];
    updatedAt: string | null;
};

export type SsoConnectionRequest = {
    protocol: SsoProtocol;
    enabled: boolean;
    enforceSso: boolean;
    jitWorkspaceId: number;
    defaultRole: string;
    oidcIssuer?: string | null;
    oidcClientId?: string | null;
    oidcClientSecret?: string | null;
    oidcScopes?: string | null;
    samlIdpEntityId?: string | null;
    samlSsoUrl?: string | null;
    samlIdpMetadataXml?: string | null;
    samlIdpX509?: string | null;
    domains: string[];
};
