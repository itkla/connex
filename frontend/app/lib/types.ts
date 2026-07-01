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

export type Task = {
    id: number;
    description: string;
    completed: boolean;
    dueDate?: string;
    assignedToId: number;
    personId?: number | null;
    dealId?: number | null;
    workspaceId?: number;
    createdAt: string;
    updatedAt: string;
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
};

export type Note = {
    id: number;
    content: string;
    author: number;
    person?: number | null;
    deal?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type NoteDraft = {
    content: string;
    author: number;
    person?: number | null;
    deal?: number | null;
};

export type CreateNotePayload = {
    content: string;
    author: number;
    person?: number | null;
    deal?: number | null;
};

export type UpdateNotePayload = {
    content?: string;
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
};

export type Deal = {
    id: number;
    name: string;
    value: number;
    actualValue: number;
    currency: string;
    pipeline: number | null;
    stage: number | null;
    company: number | null;
    workspaceId?: number;
    ownerId?: number | null;
    expectedCloseDate?: string;
    closedAt?: string;
    closedReason?: string;
    /** Outcome when closed: true = won, false = lost, null/undefined = open. closedAt follows this. */
    won?: boolean | null;
    createdAt: string;
    updatedAt: string;
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

export type CustomFieldDefinition = {
    id: number;
    workspaceId: number;
    entityType: CustomFieldEntityType;
    fieldKey: string;
    label: string;
    fieldType: CustomFieldType;
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
    negate?: boolean;
};

export type SegmentDefinition = {
    match: SegmentMatch;
    conditions: SegmentCondition[];
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

export type Workspace = {
    id: number;
    name: string;
    slug: string;
    role: WorkspaceRole;
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
};