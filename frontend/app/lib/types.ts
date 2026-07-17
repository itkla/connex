import type { Locale } from "@/i18n/config";

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

export type ContactsPageParams = PageParams & MemberScopeParams & {
    companies?: string[];
    titles?: string[];
    noCompany?: boolean;
};

export type CompaniesPageParams = PageParams & MemberScopeParams & {
    industry?: string[];
    noIndustry?: boolean;
    ids?: number[];
};

export type CompanySegmentPageParams = Omit<CompaniesPageParams, 'ids'> & {
    definition: SegmentDefinition;
};

export type ActivitiesPageParams = PageParams & {
    personId?: number;
    dealId?: number;
    createdById?: number;
};

export type NotesPageParams = PageParams & {
    workspaceOnly?: boolean;
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
    owners: FacetCount[];
};

export type CompanyFacets = {
    industries: string[];
    hasNoIndustry: boolean;
    owners: FacetCount[];
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
    value: number;
    currency: string;
};

export type DealRiskCurrencySummary = {
    currency: string;
    value: number;
    count: number;
    high: number;
    medium: number;
    low: number;
    factors: Array<{ code: DealRiskFactorCode; count: number }>;
};

export type DealRiskAnalytics = {
    currencies: DealRiskCurrencySummary[];
    truncated: boolean;
};

/** Why an AI deal brief is unavailable: AI is not configured for the org, or the provider call failed. */
export type DealBriefUnavailableReason = 'not_configured' | 'provider_error';

/** One titled section of an AI deal brief. */
export type DealBriefSection = {
    title: string;
    body: string;
};

/**
 * AI-generated "before you call" brief for a deal, or a graceful unavailability result. Presentation-only:
 * the deterministic risk/warmth signals remain the source of truth. {@code sections} is the structured
 * source of truth; {@code brief} is a plain-text flattening kept for backward compatibility. {@code warnings}
 * counts demasking integrity warnings; nonzero means parts of the brief may reference unknown placeholders.
 */
export type DealBrief = {
    dealId: number;
    available: boolean;
    sections?: DealBriefSection[] | null;
    brief?: string | null;
    generatedAt?: string | null;
    warnings: number;
    reason?: DealBriefUnavailableReason | null;
};

export type DealRationaleUnavailableReason = 'not_configured' | 'provider_error' | 'not_at_risk';

/**
 * AI-generated narrative rationale for an at-risk deal, or a graceful unavailability result.
 * Presentation-only: the deterministic {@link DealRisk} factors remain the source of truth and the
 * fallback. {@code not_at_risk} means the deal has no active risk signals to explain. {@code warnings}
 * counts demasking integrity warnings; nonzero means parts of the text may reference unknown placeholders.
 */
export type DealRationale = {
    dealId: number;
    available: boolean;
    narrative?: string | null;
    actions?: string[] | null;
    rationale?: string | null;
    generatedAt?: string | null;
    warnings: number;
    reason?: DealRationaleUnavailableReason | null;
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

export type IntroRationaleUnavailableReason = 'not_configured' | 'provider_error' | 'not_a_suggestion';

/**
 * AI-generated one-line rationale for a suggested reverse introduction, or a graceful unavailability
 * result. Presentation-only: the deterministic {@link IntroSuggestion} reasons/chips remain the source
 * of truth and the fallback. {@code not_a_suggestion} means the pair is no longer a current suggestion.
 */
export type IntroRationale = {
    personAId: number;
    personBId: number;
    available: boolean;
    rationale?: string | null;
    generatedAt?: string | null;
    warnings: number;
    reason?: IntroRationaleUnavailableReason | null;
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

export type WarmPathEvidence = 'connection' | 'colleagues' | 'former_colleagues';

export type WarmPathReachType = 'rewarm' | 'reach';

/** One avenue to a warm-path target: a warm bridge contact plus the labeled evidence tier. */
export type WarmPathBridge = {
    personId: number;
    name: string;
    title?: string | null;
    company?: string | null;
    imageUrl?: string | null;
    warmth?: TemperatureBand | null;
    evidenceType: WarmPathEvidence;
    evidenceCompany?: string | null;
    overlapStartYear?: number | null;
    overlapEndYear?: number | null;
    score: number;
};

/**
 * A warm introduction path surfaced to the user (the "receive side"): a target contact worth
 * reaching — dormant ({@code rewarm}) or never engaged ({@code reach}) — plus the best bridges
 * who can make the introduction, ordered by descending score.
 */
export type WarmPath = {
    targetId: number;
    targetName: string;
    targetTitle?: string | null;
    targetCompany?: string | null;
    targetImageUrl?: string | null;
    targetWarmth?: TemperatureBand | null;
    targetDaysSinceTouch?: number | null;
    reachType: WarmPathReachType;
    score: number;
    bridges: WarmPathBridge[];
};

/** Request body identifying the warm path an accept or dismiss targets. */
export type WarmPathPayload = {
    targetPersonId: number;
    bridgePersonId?: number;
    taskDescription?: string;
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
    locale: Locale;
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

export type BusinessCardDetectedField = {
    value?: string | null;
    confidence?: number | null;
};

export type BusinessCardScanResult = {
    fields: {
        name: BusinessCardDetectedField;
        email: BusinessCardDetectedField;
        phone: BusinessCardDetectedField;
        title: BusinessCardDetectedField;
    };
    company: BusinessCardDetectedField & {
        matchedCompanyId?: number | null;
    };
    warnings: string[];
};

export type BusinessCardCompanyAction =
    | { type: 'existing'; companyId: number }
    | { type: 'create'; companyName: string }
    | { type: 'none' };

export type BusinessCardRecoveryContext = {
    scope: string;
    workspaceId: string;
};

export type BusinessCardImportDraft = {
    requestId: string;
    recoveryContext: BusinessCardRecoveryContext;
    image: File;
    contact: CreateContactPayload;
    companyAction: BusinessCardCompanyAction;
};

export type BusinessCardImportReservation = {
    expiresAt: string;
};

export type BusinessCardImportResult = {
    contact: Pick<Contact, 'id' | 'name'> & Partial<Pick<Contact, 'email' | 'phone' | 'title' | 'imageUrl'>>;
    attachment: Pick<Attachment, 'id' | 'fileName' | 'url' | 'size'> & Partial<Pick<Attachment, 'contentType'>>;
    company?: (Pick<Company, 'id' | 'name'> & Partial<Pick<Company, 'website' | 'industry' | 'phone' | 'address' | 'logoUrl'>>) | null;
};

export type BusinessCardRequestErrorKind =
    | 'aborted'
    | 'unauthorized'
    | 'forbidden'
    | 'tooLarge'
    | 'unsupportedType'
    | 'unreadable'
    | 'busy'
    | 'conflict'
    | 'gone'
    | 'timeout'
    | 'unavailable'
    | 'recoveryStorage'
    | 'rejected'
    | 'failed';

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
    actualValue: number;
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
    ownerId?: number | null;
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
    persons: Array<Pick<Contact, 'id' | 'name' | 'imageUrl'>>;
    personCount: number;
    relatedUsers: User[];
    relatedUserCount: number;
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
};

export type Contact = {
    id: number;
    workspaceId?: number;
    ownerId?: number | null;
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
    /** APPI processing restrictions (issue #221); read-only, set via the restrictions endpoint. */
    suspendedAt?: string | null;
    provisionCeasedAt?: string | null;
};

export type DataSubjectRequestType = 'disclosure' | 'correction' | 'cease_use' | 'cease_provision';

export type DataSubjectRequestStatus =
    | 'received'
    | 'verifying'
    | 'in_progress'
    | 'responded'
    | 'refused'
    | 'closed';

export type DataSubjectRequest = {
    id: number;
    orgId: number;
    requestType: DataSubjectRequestType;
    status: DataSubjectRequestStatus;
    channel?: string | null;
    requesterName: string;
    subjectName: string;
    subjectEmail?: string | null;
    subjectWorkspaceId?: number | null;
    subjectPersonId?: number | null;
    receivedAt: string;
    identityVerifiedAt?: string | null;
    dueAt?: string | null;
    respondedAt?: string | null;
    closedAt?: string | null;
    summary?: string | null;
    resolution?: string | null;
    createdBy?: number | null;
    updatedBy?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type DataSubjectRequestBody = {
    requestType: DataSubjectRequestType;
    status?: DataSubjectRequestStatus;
    channel?: string;
    requesterName: string;
    subjectName: string;
    subjectEmail?: string;
    subjectWorkspaceId?: number;
    subjectPersonId?: number;
    receivedAt?: string;
    identityVerifiedAt?: string;
    dueAt?: string;
    respondedAt?: string;
    closedAt?: string;
    summary?: string;
    resolution?: string;
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

export type DealPrimaryContact = {
    dealId: number;
    personId: number;
    name: string;
    imageUrl: string;
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

export type CampaignStatus =
    | "draft"
    | "scheduled"
    | "active"
    | "paused"
    | "completed"
    | "archived";

export type Campaign = {
    id: number;
    name: string;
    objective: string | null;
    type: string;
    status: CampaignStatus;
    ownerUserId: number | null;
    budgetAmount: number | null;
    budgetCurrency: string | null;
    startAt: string | null;
    endAt: string | null;
    parentCampaignId: number | null;
    createdById: number | null;
    createdAt: string;
    updatedAt: string;
};

export type CampaignPayload = {
    name: string;
    objective?: string | null;
    type: string;
    status?: CampaignStatus | null;
    ownerUserId?: number | null;
    budgetAmount?: number | null;
    budgetCurrency?: string | null;
    startAt?: string | null;
    endAt?: string | null;
    parentCampaignId?: number | null;
};

export type CampaignAudienceRecordType = "person" | "company" | "deal";

export type CampaignAudience = {
    campaignId: number;
    recordType: CampaignAudienceRecordType;
    definition: SegmentDefinition;
    mode: string;
    updatedAt: string;
};

export type CampaignAudiencePayload = {
    recordType: CampaignAudienceRecordType;
    definition: SegmentDefinition;
};

export type RecordLabel = {
    id: number;
    label: string;
};

export type CampaignAudienceEstimate = {
    estimatedIncluded: number;
    excludedConsent: number;
    excludedSuppressed: number;
    excludedRestricted: number;
    excludedTotal: number;
    sampleLabels: RecordLabel[];
};

export type CampaignAudienceExclusionReason =
    | "consent_missing"
    | "suppressed"
    | "restricted";

export type CampaignAudienceMember = {
    recordType: CampaignAudienceRecordType;
    recordId: number;
    status: "included" | "excluded";
    exclusionReason: CampaignAudienceExclusionReason | null;
};

export type CampaignAudienceSnapshotSummary = {
    version: number;
    recordType: CampaignAudienceRecordType;
    estimatedIncluded: number;
    excludedTotal: number;
    excludedConsent: number;
    excludedSuppressed: number;
    excludedRestricted: number;
    createdById: number | null;
    createdAt: string;
};

export type CampaignAudienceSnapshot = CampaignAudienceSnapshotSummary & {
    campaignId: number;
    definition: SegmentDefinition;
    members: CampaignAudienceMember[];
};

export type CampaignChannel = "email";

export type CampaignMessageStatus = "draft" | "final";

export type CampaignMessageLocale = "en" | "ja";

/** One immutable, locale-scoped revision of a campaign message. */
export type CampaignMessageRevision = {
    version: number;
    locale: string;
    subject: string;
    bodyHtml: string;
    bodyText: string | null;
    createdAt: string;
};

/** A campaign message and its append-only revisions, newest first. */
export type CampaignMessage = {
    id: number;
    campaignId: number;
    channel: string;
    name: string;
    status: CampaignMessageStatus;
    createdById: number | null;
    createdAt: string;
    updatedAt: string;
    revisions: CampaignMessageRevision[];
};

export type CampaignMessagePayload = {
    name: string;
    channel: CampaignChannel;
};

export type CampaignMessageRevisionPayload = {
    locale: CampaignMessageLocale;
    subject: string;
    bodyHtml: string;
    bodyText?: string | null;
};

export type CampaignSendStatus =
    | "draft"
    | "queued"
    | "running"
    | "paused"
    | "completed"
    | "failed"
    | "cancelled";

/** A campaign send bound to a frozen audience snapshot and a message revision. */
export type CampaignSend = {
    id: number;
    campaignId: number;
    snapshotId: number;
    messageId: number;
    messageVersion: number;
    channel: string;
    purpose: string;
    providerId: string | null;
    status: CampaignSendStatus;
    scheduledAt: string | null;
    startedAt: string | null;
    completedAt: string | null;
    totalRecipients: number;
    dispatchedCount: number;
    skippedCount: number;
    failedCount: number;
    createdById: number | null;
    createdAt: string;
    updatedAt: string;
};

export type CampaignSendPayload = {
    snapshotVersion: number;
    messageId: number;
    messageVersion: number;
    purpose?: string | null;
    scheduledAt?: string | null;
};

export type CampaignExportStatus = "draft" | "running" | "completed" | "failed";

/** A campaign audience export bound to a frozen snapshot and an external connector. */
export type CampaignAudienceExport = {
    id: number;
    campaignId: number;
    snapshotId: number;
    connector: string;
    externalListId: string | null;
    status: CampaignExportStatus;
    totalMembers: number;
    pushedCount: number;
    failedCount: number;
    createdById: number | null;
    createdAt: string;
    updatedAt: string;
};

export type CampaignAudienceExportPayload = {
    snapshotVersion: number;
    connector: string;
};

/** Public confirmation payload for an unsubscribe link; the address is masked by the backend. */
export type DeliveryUnsubscribeInfo = {
    channel: string;
    address: string;
    unsubscribed: boolean;
};

export type ContactChannelConsent = {
    id: number;
    personId: number;
    channel: string;
    purpose: string;
    status: "granted" | "revoked" | "unknown";
    source: string | null;
    evidenceRef: string | null;
    capturedAt: string | null;
    updatedAt: string;
};

export type ContactChannelConsentPayload = {
    channel: string;
    purpose: string;
    status: "granted" | "revoked" | "unknown";
    source: string;
    evidenceRef?: string | null;
    capturedAt?: string | null;
};

export type SuppressionEntry = {
    id: number;
    scope: "workspace" | "global";
    channel: string;
    address: string;
    personId: number | null;
    reason: string;
    note: string | null;
    createdById: number | null;
    createdAt: string;
};

export type SuppressionEntryPayload = {
    scope: "workspace" | "global";
    channel: string;
    address: string;
    personId?: number | null;
    reason: string;
    note?: string | null;
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

export type SegmentFieldKind = "string" | "number" | "id" | "enum" | "tag" | "date";

export type SegmentValueSource =
    | "none"
    | "tags"
    | "industries"
    | "owners"
    | "stages"
    | "pipelines"
    | "companies";

export type SegmentCatalogField = {
    field: string;
    kind: SegmentFieldKind;
    valueSource: SegmentValueSource;
    operators: string[];
};

export type SegmentCatalogPredicate = {
    key: string;
    recordTypes: string[];
    acceptsDays: boolean;
    defaultDays: number | null;
    minDays: number | null;
    maxDays: number | null;
};

export type SegmentCatalogLimits = {
    maxConditions: number;
    maxGroupConditions: number;
    maxGroups: number;
    maxDepth: number;
};

export type SegmentCatalog = {
    recordType: string;
    fields: SegmentCatalogField[];
    predicates: SegmentCatalogPredicate[];
    enumOptions: Record<string, string[]>;
    limits: SegmentCatalogLimits;
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

export type ReportCadence = "weekly" | "monthly" | "quarterly" | "custom";

export type ReportScheduleCadence = Exclude<ReportCadence, "custom">;

export type ReportScheduleTimezone = string;

export type ReportBucket = "day" | "week" | "month";

export type ReportChartType = "bar" | "line-area" | "donut" | "funnel" | "table" | "kpi";

export type ReportDataSource = "deals" | "people" | "companies" | "activities" | "tasks" | "relationships";

export type ReportMeasure =
    | "count"
    | "new_pipeline_value"
    | "won_revenue"
    | "win_rate"
    | "avg_cycle_days"
    | "open_pipeline_value"
    | "open_deal_count"
    | "at_risk_revenue"
    | "company_count"
    | "coverage_gap_count"
    | "coverage_gap_open_pipeline_value"
    | "single_threaded_deal_count"
    | "single_threaded_deal_value"
    | "warm_intro_opportunity_value"
    | "warm_intro_reachable_account_count"
    | "reverse_intro_weighted_opportunities"
    | "forecast_best"
    | "forecast_weighted"
    | "forecast_worst"
    | "attainment";

export type ReportGroupBy =
    | "none"
    | "date"
    | "pipeline"
    | "stage"
    | "owner"
    | "status"
    | "company"
    | "deal"
    | "risk"
    | "activity_type"
    | "industry"
    | "warmth_band"
    | "trend"
    | "connector"
    | "pair";

export type ReportRange = {
    start: string;
    end: string;
};

export type ReportFilters = {
    pipelineIds: number[] | null;
    ownerIds: number[] | null;
    statuses: string[] | null;
    tagIds: number[] | null;
    warmthBands: string[] | null;
};

export type ReportWidgetConfig = {
    id: string;
    title: string | null;
    dataSource: ReportDataSource;
    measure: ReportMeasure;
    groupBy: ReportGroupBy | null;
    chartType: ReportChartType;
};

export type ReportLayoutItem = {
    widgetId: string;
    x: number;
    y: number;
    width: number;
    height: number;
};

export type ReportConfig = {
    widgets: ReportWidgetConfig[];
    filters: ReportFilters | null;
    range: ReportRange | null;
    bucket: ReportBucket;
    layout: ReportLayoutItem[];
};

export type ReportDefinitionInput = {
    name: string;
    description: string | null;
    cadence: ReportCadence;
    templateKey: string | null;
    config: ReportConfig;
};

export type ReportDefinition = ReportDefinitionInput & {
    id: number;
    createdBy: number | null;
    createdAt: string;
    updatedAt: string;
};

export type ReportTemplate = {
    key: string;
    name: string;
    description: string;
    cadence: ReportCadence;
    config: ReportConfig;
};

export type ReportGenerateInput = {
    start?: string | null;
    end?: string | null;
};

export type ReportNarrativeMode = "cached" | "full";

export type ReportDataPoint = {
    key: string;
    label: string;
    value: number;
    priorValue: number | null;
    sourceId: string;
};

export type ReportWidgetData = {
    widgetId: string;
    title: string;
    chartType: ReportChartType;
    dataSource: ReportDataSource;
    measure: ReportMeasure;
    groupBy: ReportGroupBy | null;
    unit: string | null;
    total: number | null;
    priorTotal: number | null;
    changePercent: number | null;
    points: ReportDataPoint[];
};

export type ReportAppendixRow = {
    sourceId: string;
    widgetId: string;
    label: string;
    value: number;
    priorValue: number | null;
    unit: string | null;
};

export type ReportCitation = {
    sourceId: string;
    widgetId: string;
    label: string;
    value: number;
    priorValue: number | null;
    unit: string | null;
};

export type ReportNarrativeClaim = {
    text: string;
    sourceIds: string[];
};

export type ReportNarrativeSection = {
    title: string;
    claims: ReportNarrativeClaim[];
};

export type ReportNarrative = {
    available: boolean;
    sections: ReportNarrativeSection[];
    findings: ReportNarrativeClaim[];
    reason: string | null;
    generatedAt: string | null;
    warnings: number;
};

export type ReportDocument = {
    definition: ReportDefinition;
    periodStart: string;
    periodEnd: string;
    priorPeriodStart: string;
    priorPeriodEnd: string;
    narrative: ReportNarrative;
    widgets: ReportWidgetData[];
    appendix: ReportAppendixRow[];
    citations: ReportCitation[];
    generatedAt: string;
};

export type ReportSnapshotSummary = {
    id: number;
    reportDefinitionId: number;
    periodStart: string;
    periodEnd: string;
    generatedBy: number | null;
    generatedAt: string;
};

export type ReportSnapshot = ReportSnapshotSummary & {
    computedResult: ReportDocument;
};

export type ReportScheduleRecipient = {
    userId: number;
    displayName: string;
    email: string;
};

export type ReportScheduleRequest = {
    cadence: ReportScheduleCadence;
    recipientUserIds: number[];
    timezone: ReportScheduleTimezone;
    hourOfDay: number;
    enabled: boolean;
};

export type ReportSchedule = ReportScheduleRequest & {
    id: number;
    reportDefinitionId: number;
    recipients: ReportScheduleRecipient[];
    runAsUserId: number;
    runAsLabel: string | null;
    nextRunAt: string;
    lastRunAt: string | null;
    createdBy: number;
    createdAt: string;
    updatedAt: string;
};

export type ReportGoalPeriodType = 'month' | 'quarter';

export type ReportGoalInput = {
    ownerId: number | null;
    metric: 'won_revenue';
    periodType: ReportGoalPeriodType;
    periodStart: string;
    targetValue: number;
    currency: string;
};

export type ReportGoal = ReportGoalInput & {
    id: number;
    ownerLabel: string | null;
    createdBy: number | null;
    createdAt: string;
    updatedAt: string;
};

export type UpdateContactPayload = {
    name?: string;
    email?: string;
    phone?: string;
    title?: string;
    companyId?: number | null;
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
    stateVersion?: number;
};

export type NotificationCounts = {
    unread: number;
    stateVersion: number;
    asOf: string;
    nextSnoozeExpiry?: string | null;
};

export type NotificationMarkAllResult = NotificationCounts & {
    cutoffId: number;
    readAt: string;
};

export type NotificationPage = Page<Notification> & {
    stateVersion: number;
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
    label?: string;
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

/**
 * Canonical member-scope wire params shared by record, metric, and facet endpoints:
 * `scope` selects Me / selected members / Unassigned (absent = all team), and
 * `memberIds` carries the selection when {@code scope === 'members'}.
 */
export type MemberScopeParams = {
    scope?: 'me' | 'members' | 'unassigned';
    memberIds?: number[];
};

export type DealFilterParams = MemberScopeParams & {
    q?: string;
    status?: Array<'open' | 'closed' | 'won' | 'lost'>;
    risk?: Array<'high' | 'medium' | 'low' | 'none'>;
    stageId?: number[];
    pipelineId?: number[];
    companyId?: number[];
    noCompany?: boolean;
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
    risk: FacetCount[];
    owners: FacetCount[];
};

export type CompanyEngagement = {
    persons: Array<Pick<Contact, 'id' | 'name' | 'imageUrl'>>;
    personCount: number;
    relatedUserIds: number[];
    relatedUserCount: number;
    pastRevenue: number;
    projectedRevenue: number;
    currency: string;
    numDeals: number;
    numTasks: number;
    openTasks: number;
    numActivities: number;
    numNotes: number;
    weeklyEngagement: CompanyMetrics['weeklyEngagement'];
};

export type CompanyTimeline = {
    activities: Activity[];
    tasks: Task[];
    notes: Note[];
};

/** Coherent relationship snapshot computed once for all dashboard relationship widgets. */
export type RelationshipDashboard = {
    warmthSummary: WarmthSummary;
    coolingContacts: Array<{ contact: Contact; temperature: RelationshipTemperature }>;
    coolingCompanies: Array<{ company: Company; temperature: RelationshipTemperature }>;
    dealRisks: Array<{ deal: Deal; company: Company | null; risk: DealRisk }>;
    dealRisksTruncated: boolean;
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

/**
 * Server-computed deal KPIs for the analytics/dashboard clusters over ALL deals in a range.
 * Scalars are current-period; {@code *Prev} are the previous window (null = no baseline).
 * The four series are 12 buckets, oldest→newest, over the current period.
 */
export type DealKpis = {
    wonRevenue: number;
    wonRevenuePrev: number | null;
    newPipeline: number;
    newPipelinePrev: number | null;
    wonCount: number;
    lostCount: number;
    wonValue: number;
    lostValue: number;
    wonCountPrev: number | null;
    lostCountPrev: number | null;
    avgCycleDays: number;
    avgCycleDaysPrev: number | null;
    wonSeries: number[];
    newPipelineSeries: number[];
    /** Win-rate per bucket as a 0–100 percent (note: the {@link DealKpis} scalar win rate is derived from wonCount/lostCount, not this series). */
    winRateSeries: number[];
    avgCycleSeries: number[];
};

/** Server-computed per-pipeline won-in-range + open rollup for the analytics pipeline-value chart. */
export type DealPipelineValue = {
    pipelineId: number | null;
    wonValue: number;
    openValue: number;
    openCount: number;
};

/** Server-computed per-stage open-deal age buckets for the deals-aging chart. */
export type DealAging = {
    stageId: number | null;
    fresh: number;
    active: number;
    aging: number;
    stalled: number;
};

/** Server-computed top open/won deals for the analytics top-deals widget. */
export type DealTop = {
    topOpen: DealSummary[];
    topWon: DealSummary[];
};

/** One time bucket of the activity-volume chart: counts per activity type. */
export type ActivityVolumeBucket = {
    bucketIndex: number;
    call: number;
    email: number;
    meeting: number;
    note: number;
    other: number;
};

/** One user's touch count (activities + completed tasks + notes) for the team leaderboard. */
export type TeamLeaderboardEntry = {
    userId: number;
    touches: number;
};

/** A bare server-computed count. */
export type Count = {
    count: number;
};

/** Server-computed task status + due-window counts (backs the task donut + greeting). */
export type TaskSummary = {
    todo: number;
    inProgress: number;
    done: number;
    overdue: number;
    dueSoon: number;
};

/** Relationship-temperature band counts. */
export type WarmthBandCounts = {
    hot: number;
    warm: number;
    cool: number;
    cold: number;
};

/** Contact warmth-trend counts. */
export type WarmthTrendCounts = {
    rising: number;
    steady: number;
    cooling: number;
};

/** Contact decay-horizon counts (days until cold): {@code soon} ≤30, {@code mid} ≤60, {@code later} ≤90. */
export type WarmthDecayCounts = {
    soon: number;
    mid: number;
    later: number;
};

/** Server-computed workspace-wide warmth summary over ALL contacts/companies (not a bounded slice). */
export type WarmthSummary = {
    contacts: WarmthBandCounts;
    companies: WarmthBandCounts;
    contactTrends: WarmthTrendCounts;
    contactDecay: WarmthDecayCounts;
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
    contentRedacted?: boolean;
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

export type DeliveryProvider = "smtp" | "http_esp";

export type DeliveryProviderConfig = {
    channel: string;
    provider: DeliveryProvider;
    endpoint: string | null;
    fromAddress: string | null;
    fromName: string | null;
    hasCredential: boolean;
    credentialLast4: string | null;
    webhookConfigured: boolean;
    enabled: boolean;
    updatedAt: string | null;
};

export type DeliveryProviderConfigPayload = {
    channel: string;
    provider: string;
    endpoint?: string | null;
    fromAddress?: string | null;
    fromName?: string | null;
    apiKey?: string | null;
    enabled: boolean;
};

export type DeliveryWebhookToken = {
    token: string;
    secret: string;
    signatureHeader: string;
};

export type ConnectorConfig = {
    connector: string;
    endpoint: string | null;
    externalListId: string | null;
    hasCredential: boolean;
    credentialLast4: string | null;
    enabled: boolean;
    updatedAt: string | null;
};

export type ConnectorConfigPayload = {
    connector: string;
    endpoint?: string | null;
    externalListId?: string | null;
    apiKey?: string | null;
    enabled: boolean;
};

export type InstanceCapabilities = {
    sso: boolean;
    socialLogin: { google: boolean; microsoft: boolean };
    mailManaged: boolean;
    businessCardScanning: boolean;
    businessCardImport: boolean;
};

export type BusinessCardAvailability = {
    scanning: boolean;
    importing: boolean;
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

export type AiProviderKind = "bedrock" | "azure_openai" | "vertex" | "openai_compatible";

export type AiProviderConfig = {
    provider: AiProviderKind | null;
    region: string | null;
    endpoint: string | null;
    apiVersion: string | null;
    deployment: string | null;
    projectId: string | null;
    allowInternalEndpoint: boolean;
    modelId: string | null;
    hasCredential: boolean;
    credentialLast4: string | null;
    noTrainingAttested: boolean;
    enabled: boolean;
    updatedAt: string | null;
};

export type AiProviderConfigRequest = {
    provider: AiProviderKind;
    modelId: string;
    noTrainingAttested: boolean;
    enabled: boolean;
    region?: string | null;
    endpoint?: string | null;
    apiVersion?: string | null;
    deployment?: string | null;
    projectId?: string | null;
    allowInternalEndpoint?: boolean;
    accessKeyId?: string | null;
    secretAccessKey?: string | null;
    sessionToken?: string | null;
    apiKey?: string | null;
    serviceAccountJson?: string | null;
};
