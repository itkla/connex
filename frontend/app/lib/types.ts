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
    /** Selects the archived contacts instead of the active ones (issue #854). */
    archived?: boolean;
};

export type CompaniesPageParams = PageParams & MemberScopeParams & {
    industry?: string[];
    noIndustry?: boolean;
    ids?: number[];
    /** Selects the archived companies instead of the active ones (issue #854). */
    archived?: boolean;
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

export type ExportEntity = ImportEntity | 'products';

export type ProductSearchParams = {
    q?: string;
};

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
    duplicateReviewProof?: string;
};

export type ImportRowStatus = 'create' | 'match' | 'skip' | 'invalid';

export type DuplicateMatchKind = 'EMAIL' | 'PHONE' | 'DOMAIN' | 'EXTERNAL_ID' | 'NAME';
export type DuplicateMatchStrength = 'STRONG' | 'WEAK';

export type DuplicateMatchEvidence = {
    kind: DuplicateMatchKind;
    normalizedValue: string;
    strength: DuplicateMatchStrength;
};

export type DuplicateCandidate = {
    recordId: number;
    recordType: 'person' | 'company';
    name: string;
    companyName?: string | null;
    title?: string | null;
    website?: string | null;
    industry?: string | null;
    ownedByActiveWorkspace: boolean;
    strength: DuplicateMatchStrength;
    matches: DuplicateMatchEvidence[];
};

export type DuplicatePreflightResponse = {
    recordType: 'person' | 'company';
    candidates: DuplicateCandidate[];
    truncated: boolean;
    reviewToken: string;
};

export type PersonDuplicatePreflightRequest = {
    name?: string | null;
    emails: string[];
    phones: string[];
};

export type CompanyDuplicatePreflightRequest = {
    name?: string | null;
    websites: string[];
    phones: string[];
};

export type ImportRowAnalysis = {
    rowIndex: number;
    status: ImportRowStatus;
    matchedId?: number | null;
    matchedLabel?: string | null;
    canonicalRowIndex?: number | null;
    mergedRowCount?: number | null;
    errors?: string[] | null;
    candidates?: DuplicateCandidate[] | null;
};

export type ImportPreviewResult = {
    total: number;
    toCreate: number;
    toUpdate: number;
    toSkip: number;
    invalid: number;
    rows: ImportRowAnalysis[];
    duplicateReviewProof?: string | null;
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

export type HistoryImportKind = 'activities' | 'notes' | 'tasks';

export type HistoryImportColumnMapping = {
    column: string;
    field: string;
};

export type HistoryImportRequest = {
    rows: Record<string, string>[];
    mapping: HistoryImportColumnMapping[];
    links?: Record<number, number>;
    duplicateReviewProof?: string;
};

export type HistoryImportRowStatus = 'ready' | 'already_imported' | 'needs_review' | 'invalid';

export type HistoryImportRowAnalysis = {
    rowIndex: number;
    status: HistoryImportRowStatus;
    participantId?: number | null;
    participantLabel?: string | null;
    candidates?: DuplicateCandidate[] | null;
    errors?: string[] | null;
};

export type HistoryImportPreviewResult = {
    total: number;
    toCreate: number;
    alreadyImported: number;
    needsReview: number;
    invalid: number;
    rows: HistoryImportRowAnalysis[];
    duplicateReviewProof?: string | null;
};

export type HistoryImportResult = {
    created: number;
    skipped: number;
    failed: ImportRowError[];
};

export type PersonFacets = {
    companies: string[];
    titles: string[];
    hasNoCompany: boolean;
    owners: FacetCount[];
    /** How many contacts the workspace currently holds archived (issue #854). */
    archivedCount: number;
};

export type CompanyFacets = {
    industries: string[];
    hasNoIndustry: boolean;
    owners: FacetCount[];
    /** How many companies the workspace currently holds archived (issue #854). */
    archivedCount: number;
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
    modelVersion?: string;
    asOf?: string;
};

export type RelationshipEvidenceSourceType = 'activity' | 'note' | 'task';

export type RelationshipEvidenceContributor = {
    sourceType: RelationshipEvidenceSourceType;
    sourceId: number;
    interactionType: string;
    occurredAt: string;
    baseWeight: number;
    decayedContribution: number;
    captureEvidence?: CapturedActivityEvidence | null;
};

export type RelationshipEvidence = {
    subjectType: 'person' | 'company';
    subjectId: number;
    temperature: RelationshipTemperature;
    asOf: string;
    attributionRule:
        | 'direct_person_touches'
        | 'present_day_person_company_or_deal_company'
        | 'touch_time_employer_or_present_day_deal_company';
    contributors: RelationshipEvidenceContributor[];
    totals: {
        contributorCount: number;
        returnedCount: number;
        omittedCount: number;
        totalDecayedContribution: number;
        returnedDecayedContribution: number;
        omittedDecayedContribution: number;
        sourceCounts: {
            activities: number;
            notes: number;
            tasks: number;
        };
    };
    coverage: {
        limitedEvidence: boolean;
        minimumContributorsForConfidence: number;
        callerPrivateNotesExcluded: number;
        privateNoteCountScope: 'current_caller_only';
    };
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
export type DealBriefUnavailableReason =
    | 'not_configured'
    | 'provider_error'
    | 'rate_limited'
    | 'insufficient_data';

/**
 * Kind of record a brief section cites — the raw wire value emitted by the backend registry
 * ({@code act} is an activity). Mapped to a localized label and record link on the client.
 */
export type DealBriefCitationKind = 'deal' | 'person' | 'act' | 'note' | 'task';

/**
 * A source record that informed a brief section — a real, in-context CRM record. Structurally
 * grounded (never fabricated), but not a semantic claim that the section is proven by this record.
 * {@code sourceId} is the positional prompt token (e.g. {@code note.0}) that may appear in prose.
 */
export type DealBriefCitation = {
    sourceId: string;
    kind: DealBriefCitationKind;
    id: number;
};

/** One titled section of an AI deal brief, with the source records that informed it. */
export type DealBriefSection = {
    title: string;
    body: string;
    citations?: DealBriefCitation[] | null;
};

/**
 * AI-generated "before you call" brief for a deal, or a graceful unavailability result. Presentation-only:
 * the deterministic risk/warmth signals remain the source of truth. {@code sections} is the structured
 * source of truth; {@code brief} is a plain-text flattening kept for backward compatibility. {@code warnings}
 * counts demasking integrity warnings; nonzero means parts of the brief may reference unknown placeholders.
 * {@code degraded} is deterministic: it marks that some relationship context could not be fetched when the
 * brief was assembled.
 */
export type DealBrief = {
    dealId: number;
    available: boolean;
    sections?: DealBriefSection[] | null;
    brief?: string | null;
    generatedAt?: string | null;
    warnings: number;
    degraded?: boolean | null;
    reason?: DealBriefUnavailableReason | null;
};

export type DealRationaleUnavailableReason =
    | 'not_configured'
    | 'provider_error'
    | 'not_at_risk'
    | 'rate_limited';

/** A recommended next step bound to the deterministic risk factor codes it addresses. */
export type DealRationaleAction = {
    text: string;
    factorCodes: DealRiskFactorCode[];
};

/**
 * AI-generated narrative rationale for an at-risk deal, or a graceful unavailability result.
 * Presentation-only: the deterministic {@link DealRisk} factors remain the source of truth and the
 * fallback. {@code not_at_risk} means the deal has no active risk signals to explain. {@code warnings}
 * counts demasking integrity warnings; nonzero means parts of the text may reference unknown placeholders.
 * {@code narrativeFactorCodes} binds the narrative to the deterministic factors it rests on;
 * {@code recommendedActions} carries the same binding per action, with {@code actions} kept as a
 * plain-text fallback.
 */
export type DealRationale = {
    dealId: number;
    available: boolean;
    narrative?: string | null;
    narrativeFactorCodes?: DealRiskFactorCode[] | null;
    recommendedActions?: DealRationaleAction[] | null;
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
    asOf: string;
    supportingPersonIds: number[];
    supportingEdgeIds: number[];
};

export type IntroRationaleUnavailableReason =
    | 'not_configured'
    | 'provider_error'
    | 'not_a_suggestion'
    | 'rate_limited';

/**
 * AI-generated one-line rationale for a suggested reverse introduction, or a graceful unavailability
 * result. Presentation-only: the deterministic {@link IntroSuggestion} reasons/chips remain the source
 * of truth and the fallback. {@code not_a_suggestion} means the pair is no longer a current suggestion.
 * {@code reasonCodes} binds the sentence to the deterministic suggestion reasons it rests on.
 */
export type IntroRationale = {
    personAId: number;
    personBId: number;
    available: boolean;
    rationale?: string | null;
    reasonCodes?: string[] | null;
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
    supportingPersonIds: number[];
    supportingEdgeIds: number[];
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
    asOf: string;
};

/** Request body identifying the warm path an accept or dismiss targets. */
export type WarmPathPayload = {
    targetPersonId: number;
    bridgePersonId?: number;
    taskDescription?: string;
};

/** Combined introductions feed — suggestions + warm paths from one backend warmth pass (#630). */
export type IntroEmptyReason =
    | 'insufficient_candidates'
    | 'missing_relationship_evidence'
    | 'policy_exclusion'
    | 'insufficient_path_strength'
    | 'unavailable_data';

export type IntroOverview = {
    suggestions: IntroSuggestion[];
    paths: WarmPath[];
    asOf: string;
    suggestionsEmptyReason?: IntroEmptyReason | null;
    pathsEmptyReason?: IntroEmptyReason | null;
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

export type UserReference =
    Pick<User, "id" | "displayName">
    & Partial<Pick<User, "username" | "profilePictureUrl">>;

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

export type EmailChangePayload = {
    newEmail: string;
    currentPassword: string;
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
    duplicateReviewToken?: string;
};

/** Which extraction path supplied a business-card field value. */
export type BusinessCardFieldOrigin = 'OCR' | 'AI';

export type BusinessCardDetectedField = {
    value?: string | null;
    confidence?: number | null;
    origin?: BusinessCardFieldOrigin | null;
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
    | { type: 'create'; companyName: string; duplicateReviewToken?: string }
    | { type: 'none' };

export type BusinessCardPersonAction =
    | { type: 'create' }
    | { type: 'existing'; personId: number; duplicateReviewToken: string };

export type BusinessCardRecoveryContext = {
    scope: string;
    workspaceId: string;
};

export type BusinessCardImportDraft = {
    requestId: string;
    recoveryContext: BusinessCardRecoveryContext;
    image: File;
    contact: CreateContactPayload;
    personAction: BusinessCardPersonAction;
    companyAction: BusinessCardCompanyAction;
};

export type BusinessCardImportReservation = {
    expiresAt: string;
};

export type BusinessCardImportResult = {
    contact: Pick<Contact, 'id' | 'name'> & Partial<Pick<Contact, 'email' | 'phone' | 'title' | 'imageUrl'>>;
    attachment: Pick<Attachment, 'id' | 'fileName' | 'url' | 'size'> & Partial<Pick<Attachment, 'contentType'>>;
    company?: (Pick<Company, 'id' | 'name'> & Partial<Pick<Company, 'website' | 'industry' | 'phone' | 'address' | 'logoUrl'>>) | null;
    disposition: 'created' | 'reused';
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
    captureEvidence?: CapturedActivityEvidence | null;
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
    /** Set while the company is archived (issue #854); read-only, cleared by restore. */
    archivedAt?: string | null;
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
    duplicateReviewToken?: string;
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
    /** Set while the contact is archived (issue #854); read-only, cleared by restore. */
    archivedAt?: string | null;
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
    /**
     * Where {@link value} comes from. `line_items` means the server derives it from the deal's
     * line-item totals and rejects manual edits; `manual` means it is operator-entered.
     */
    valueSource?: 'manual' | 'line_items';
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

export type DealPerson = {
    person: number;
    role: string | null;
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

export type BillingFrequency = 'one_time' | 'recurring';
export type LineDiscountType = 'amount' | 'percent';

/** A workspace-scoped catalog product/service. Money fields are server-authoritative. */
export type Product = {
    id: number;
    sku?: string | null;
    name: string;
    description?: string | null;
    active: boolean;
    unit?: string | null;
    unitPrice: number;
    currency: string;
    taxRate?: number | null;
    billingFrequency: BillingFrequency;
    effectiveStart?: string | null;
    effectiveEnd?: string | null;
    createdAt: string;
    updatedAt: string;
};

export type CreateProductPayload = {
    sku?: string | null;
    name: string;
    description?: string | null;
    active?: boolean;
    unit?: string | null;
    unitPrice: number;
    currency: string;
    taxRate?: number | null;
    billingFrequency: BillingFrequency;
    effectiveStart?: string | null;
    effectiveEnd?: string | null;
};

export type UpdateProductPayload = Partial<CreateProductPayload>;

/**
 * A line item on a deal. Catalog values are snapshotted at creation, so later product edits
 * never mutate an existing line. {@link lineSubtotal}/{@link lineTax}/{@link lineTotal} are
 * server-computed (BigDecimal) — the client never does money arithmetic.
 */
export type DealLineItem = {
    id: number;
    dealId: number;
    productId?: number | null;
    name: string;
    sku?: string | null;
    unit?: string | null;
    unitPrice: number;
    quantity: number;
    discountType?: LineDiscountType | null;
    discountValue?: number | null;
    taxRate?: number | null;
    billingFrequency: BillingFrequency;
    description?: string | null;
    servicePeriodStart?: string | null;
    servicePeriodEnd?: string | null;
    position: number;
    currency: string;
    lineSubtotal: number;
    lineTax: number;
    lineTotal: number;
    createdAt: string;
    updatedAt: string;
};

/** Server-computed deal roll-up; recurring vs one-time kept separate to avoid double-counting. */
export type DealLineItemTotals = {
    currency: string;
    subtotal: number;
    tax: number;
    oneTimeTotal: number;
    recurringTotal: number;
    grandTotal: number;
};

export type DealLineItemsResponse = {
    items: DealLineItem[];
    totals: DealLineItemTotals;
};

export type DealLineItemPayload = {
    productId?: number | null;
    name?: string;
    sku?: string | null;
    unit?: string | null;
    unitPrice?: number;
    quantity: number;
    discountType?: LineDiscountType | null;
    discountValue?: number | null;
    taxRate?: number | null;
    billingFrequency?: BillingFrequency;
    description?: string | null;
    servicePeriodStart?: string | null;
    servicePeriodEnd?: string | null;
    position?: number;
};

export type DocumentType = 'quote' | 'proposal' | 'order_form' | 'contract';

/** A mark on a document body text run (bold, italic, link, …), as ProseMirror/Tiptap JSON. */
export type DocumentBodyMark = {
    type: string;
    attrs?: Record<string, unknown>;
};

/**
 * One node in a document template's block body (ProseMirror/Tiptap JSON). The block builder
 * authors this tree; merge tokens live as inline {@code mergeToken} nodes and the line-items table
 * as a {@code lineItems} placeholder, both resolved server-side at generation.
 */
export type DocumentBodyNode = {
    type: string;
    attrs?: Record<string, unknown>;
    content?: DocumentBodyNode[];
    marks?: DocumentBodyMark[];
    text?: string;
};

/** A workspace-scoped commercial-document template. Sections may carry {{merge tokens}}. */
export type DocumentTemplate = {
    id: number;
    name: string;
    type: DocumentType;
    locale: string;
    title?: string | null;
    intro?: string | null;
    terms?: string | null;
    footer?: string | null;
    body?: string | null;
    active: boolean;
    createdAt: string;
    updatedAt: string;
};

export type CreateDocumentTemplatePayload = {
    name: string;
    type: DocumentType;
    locale?: string;
    title?: string | null;
    intro?: string | null;
    terms?: string | null;
    footer?: string | null;
    body?: string | null;
    active?: boolean;
};

export type UpdateDocumentTemplatePayload = Partial<CreateDocumentTemplatePayload>;

export type DocumentStatus = 'draft' | 'pending_approval' | 'approved' | 'final' | 'superseded';

/**
 * Statuses a client may request through the status endpoint. The approval states are owned by the
 * approval flow and are rejected server-side if sent here.
 */
export type DocumentClientStatus = 'draft' | 'final' | 'superseded';

export type DocumentApprovalStatus = 'pending' | 'approved' | 'rejected' | 'cancelled';

/** One approval request on a generated document, with its decision once made. */
export type DocumentApproval = {
    id: number;
    documentId: number;
    policyId?: number | null;
    status: DocumentApprovalStatus;
    requestedBy?: number | null;
    requestComment?: string | null;
    decidedBy?: number | null;
    decisionComment?: string | null;
    decidedAt?: string | null;
    createdAt: string;
};

/** Declares when a generated document requires internal approval before finalization. */
export type ApprovalPolicy = {
    id: number;
    name: string;
    active: boolean;
    documentType?: DocumentType | null;
    currency?: string | null;
    minTotal?: number | null;
    minDiscountPercent?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type CreateApprovalPolicyPayload = {
    name: string;
    active?: boolean;
    documentType?: DocumentType | null;
    currency?: string | null;
    minTotal?: number | null;
    minDiscountPercent?: number | null;
};

/**
 * Full-replace payload: the backend PUT nulls any omitted field and re-activates when
 * {@code active} is absent, so partial bodies are not safe — always send the complete policy.
 */
export type UpdateApprovalPolicyPayload = CreateApprovalPolicyPayload;

/** A party rendered on a document (workspace, company, or owner). */
export type DocumentParty = {
    name: string;
    address?: string | null;
};

/**
 * The immutable, resolved snapshot stored on a generated document. Merge tokens are already
 * substituted and the line items/totals frozen at generation — the client only renders this.
 */
export type DocumentContent = {
    generatedAt: string;
    workspace?: DocumentParty | null;
    company?: DocumentParty | null;
    owner?: DocumentParty | null;
    deal: { name: string; currency: string };
    sections: {
        title?: string | null;
        intro?: string | null;
        terms?: string | null;
        footer?: string | null;
    };
    body?: DocumentBodyNode | null;
    lineItems: DealLineItem[];
    totals: DealLineItemTotals;
};

/** A generated, immutable, versioned commercial document on a deal. */
export type DealDocument = {
    id: number;
    dealId: number;
    templateId?: number | null;
    type: DocumentType;
    locale: string;
    status: DocumentStatus;
    version: number;
    title?: string | null;
    currency: string;
    generatedAt: string;
    createdBy: number | null;
    content: DocumentContent;
    requiresApproval: boolean;
    latestApproval?: DocumentApproval | null;
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

/**
 * The member-facing projection of a custom-field definition, as returned by
 * `GET /api/custom-fields/schema`. Deliberately narrower than {@link CustomFieldDefinition}: it
 * omits data classifications and archived fields, which stay on the admin-gated catalog.
 */
export type CustomFieldSchemaEntry = {
    definitionId: number;
    label: string;
    fieldType: CustomFieldType;
    options: CustomFieldOption[] | null;
    required: boolean;
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

/**
 * The browser's flat working shape of a saved-view configuration. {@link visibleColumns},
 * {@link columnOrder}, and {@link pageSize} are carried purely so they survive a round-trip through
 * the persisted config DTO — capturing and applying them in the UI is deferred (issue #412).
 */
export type SavedViewConfig = {
    filters?: Record<string, string[]>;
    query?: string;
    sortKey?: string | null;
    sortDirection?: "asc" | "desc";
    segments?: SegmentDefinition;
    visibleColumns?: string[] | null;
    columnOrder?: string[] | null;
    pageSize?: number | null;
};

/** Who can see a saved view: only its owner, or everyone in the workspace. */
export type SavedViewVisibility = "private" | "workspace";

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
    | "consent_revoked"
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

export type CampaignChannel = "email" | "sms";

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

/**
 * Content for a new message revision. Which fields carry the content is channel-specific: an email
 * revision sends {@code subject} and {@code bodyHtml}, an SMS revision sends only {@code bodyText}.
 */
export type CampaignMessageRevisionPayload = {
    locale: CampaignMessageLocale;
    subject?: string | null;
    bodyHtml?: string | null;
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

/**
 * A new send. Deliberately carries no scheduled time: the dispatch worker claims on status alone,
 * so a `scheduledAt` the API still accepts would be stored and never honoured.
 */
export type CampaignSendPayload = {
    snapshotVersion: number;
    messageId: number;
    messageVersion: number;
    purpose?: string | null;
};

/** Per-channel delivery tally within a campaign's engagement rollup. */
export type CampaignChannelStat = {
    channel: string;
    deliveries: number;
};

/** Delivery-outcome rollup for a single send within a campaign's engagement view. */
export type CampaignSendEngagement = {
    sendId: number;
    status: string;
    channel: string;
    totalRecipients: number;
    dispatched: number;
    delivered: number;
    bounced: number;
    complained: number;
    unsubscribed: number;
    failed: number;
    skipped: number;
    skipReasons: Record<string, number>;
    eventCounts: Record<string, number>;
    deliveryReceiptsAvailable: boolean;
    deliveryRate: number | null;
    bounceRate: number | null;
    complaintRate: number | null;
};

/**
 * Campaign-wide engagement rollup aggregated across every send. Rate fields are {@code null} when
 * they cannot be measured (for example, an SMTP transport that returns no delivery receipts), which
 * the UI surfaces as "Not measured" rather than a misleading zero.
 */
export type CampaignEngagement = {
    campaignId: number;
    totalRecipients: number;
    dispatched: number;
    delivered: number;
    bounced: number;
    complained: number;
    unsubscribed: number;
    failed: number;
    skipped: number;
    skipReasons: Record<string, number>;
    eventCounts: Record<string, number>;
    channels: CampaignChannelStat[];
    deliveryReceiptsAvailable: boolean;
    deliveryRate: number | null;
    bounceRate: number | null;
    complaintRate: number | null;
    sends: CampaignSendEngagement[];
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
    workspaceId: number;
    recordType: SavedViewRecordType;
    name: string;
    visibility: SavedViewVisibility;
    ownerUserId: number;
    /** Whether the requesting user owns this view; drives owner-only menu actions. */
    ownedByCurrentUser: boolean;
    config: SavedViewConfig;
    position: number;
    pinned: boolean;
    /** Sort order among the user's pinned views; null when not pinned. */
    pinPosition: number | null;
    /** Whether this view is the requesting user's default for its record type. */
    default: boolean;
    createdAt: string;
    updatedAt: string;
};

export type SavedViewInput = {
    recordType: SavedViewRecordType;
    name: string;
    visibility?: SavedViewVisibility;
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
    | "employment_departure_count"
    | "employment_arrival_count"
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
    | "pair"
    | "person";

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

export type ReportComposerAvailability = {
    available: boolean;
    reason: string | null;
};

export type ReportComposerEvidence = {
    widgetId: string;
    dataSource: ReportDataSource;
    measure: ReportMeasure;
    groupBy: ReportGroupBy;
    chartType: ReportChartType;
};

export type ReportComposerPreview = {
    available: boolean;
    reason: string | null;
    definition: ReportDefinitionInput | null;
    assumptionCodes: string[];
    evidence: ReportComposerEvidence[];
    effectiveRange: ReportRange | null;
    generatedAt: string | null;
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

type AiGenerationBase = {
    handle: string;
    kind: string;
    retryAfterMs: number;
    pollWindowMs: number;
    expiresAt: string;
};

export type AiGenerationStatus<T> =
    | AiGenerationBase & {
        status: 'accepted' | 'running';
        result: null;
        reason: null;
    }
    | AiGenerationBase & {
        status: 'resolved';
        result: T;
        reason: null;
    }
    | AiGenerationBase & {
        status: 'failed' | 'timed_out';
        result: null;
        reason: string;
    };

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
    generation?: AiGenerationStatus<ReportDocument> | null;
};

/** How a frozen snapshot came to exist: created by hand, or frozen by scheduled delivery. */
export type ReportSnapshotOrigin = 'manual' | 'scheduled';

export type ReportSnapshotSummary = {
    id: number;
    reportDefinitionId: number;
    periodStart: string;
    periodEnd: string;
    origin: ReportSnapshotOrigin;
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

export type NotificationState = 'active' | 'unread' | 'snoozed' | 'history' | 'all';

/**
 * Server-side snooze presets. Exactly one of a preset or an explicit `until`
 * instant is sent per snooze request, always alongside the caller's IANA timezone.
 */
export type SnoozePreset = 'later_today' | 'tomorrow_morning' | 'next_week';

/**
 * Snooze request body: exactly one of a named preset or an explicit ISO-UTC
 * `until` instant, plus the caller's IANA timezone.
 */
export type SnoozeRequest =
    | { preset: SnoozePreset; timezone: string }
    | { until: string; timezone: string };

/**
 * Day-of-week names used by the quiet-hours contract. The wire format is the
 * uppercase English day name, independent of the display locale.
 */
export type QuietHoursDay =
    | 'MONDAY'
    | 'TUESDAY'
    | 'WEDNESDAY'
    | 'THURSDAY'
    | 'FRIDAY'
    | 'SATURDAY'
    | 'SUNDAY';

/**
 * Editable quiet-hours configuration sent to the server as a full replacement.
 */
export type QuietHoursConfig = {
    enabled: boolean;
    timezone: string;
    start: string;
    end: string;
    days: QuietHoursDay[];
    bypassPolicy: string;
};

/**
 * Quiet-hours state returned by the server: the editable config plus the
 * server-computed `activeNow` flag and the next start/end transition instant.
 */
export type QuietHours = QuietHoursConfig & {
    activeNow: boolean;
    nextTransitionAt?: string | null;
};

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
    snoozeTimezone?: string | null;
    createdAt: string;
    updatedAt: string;
    stateVersion?: number;
};

export type NotificationCounts = {
    unread: number;
    snoozed: number;
    stateVersion: number;
    asOf: string;
    nextSnoozeExpiry?: string | null;
    quietHoursActive: boolean;
    nextQuietHoursTransition?: string | null;
};

export type NotificationMarkAllResult = NotificationCounts & {
    cutoffId: number;
    readAt: string;
};

export type NotificationPage = Page<Notification> & {
    stateVersion: number;
    asOf: string;
};

export type NotificationParams = {
    status?: NotificationState;
    type?: string | string[];
    workspaceId?: number;
    category?: string | string[];
    severity?: string | string[];
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

export type AttachmentEntityType = 'company' | 'person' | 'deal' | 'user' | 'note';

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
    label?: string | null;
};

export type NotificationFacets = {
    categories: FacetCount[];
    severities: FacetCount[];
    workspaces: FacetCount[];
    stateVersion: number;
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

export type DealSegmentPageParams = DealsPageParams & {
    definition: SegmentDefinition;
};

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
    hasRelationshipEvidence: boolean;
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

/**
 * Calendar-aligned window params for the analytics series endpoints: inclusive local ISO
 * dates in {@code timezone} (IANA), with a bucketing {@code granularity} where the endpoint
 * returns a series.
 */
export type AnalyticsWindowParams = {
    from: string;
    to: string;
    granularity?: 'day' | 'week' | 'month';
    timezone?: string;
};

/** One calendar bucket's total; {@code periodStart} is the bucket's local start date ({@code yyyy-MM-dd}). */
export type DealPeriodTotal = {
    periodStart: string;
    total: number;
};

/** Server-computed calendar-bucketed revenue series (realized vs projected), zero-filled per bucket. */
export type DealRevenuePeriodSeries = {
    realized: DealPeriodTotal[];
    projected: DealPeriodTotal[];
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
 * The four series are per-bucket, oldest→newest, over the current period (12 fixed buckets on
 * the legacy range path, one per calendar bucket on the windowed path).
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

/**
 * One time bucket of the activity-volume chart: counts per activity type.
 * {@code periodStart} is the bucket's local start date ({@code yyyy-MM-dd}) on the
 * calendar-aligned windowed path; absent on the legacy rolling-range path.
 */
export type ActivityVolumeBucket = {
    bucketIndex: number;
    call: number;
    email: number;
    meeting: number;
    note: number;
    other: number;
    periodStart?: string | null;
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
    old?: unknown;
    new?: unknown;
};

export type WorkspaceRole = "owner" | "admin" | "member";

export type OrgRole = "owner" | "admin";

export type Workspace = {
    id: number;
    name: string;
    slug: string;
    timezone: string | null;
    identityVersion: number;
    role: WorkspaceRole;
    orgId: number;
    orgName: string;
    orgIdentityVersion: number;
    orgRole: OrgRole | null;
};

export type WorkspaceIdentity = {
    id: number;
    orgId: number;
    name: string;
    slug: string;
    timezone: string | null;
    identityVersion: number;
    updatedAt: string;
};

export type OrganizationIdentity = {
    id: number;
    name: string;
    slug: string;
    identityVersion: number;
    updatedAt: string;
};

export type OrganizationLayoutAuthorityMember = {
    userId: number;
    displayName: string;
    profilePictureUrl?: string | null;
    orgRole: OrgRole;
};

export type OrganizationLayoutWorkspaceMember = {
    workspaceId: number;
    userId: number;
    displayName: string;
    profilePictureUrl?: string | null;
    role: string;
    roleId?: number | null;
    status: "active" | "pending";
};

export type OrganizationLayoutWorkspace = {
    id: number;
    name: string;
    slug: string;
    timezone: string | null;
    rosterVisible: boolean;
    memberships: OrganizationLayoutWorkspaceMember[];
    membershipsTruncated: boolean;
};

export type OrganizationLayout = {
    organization: OrganizationIdentity;
    authorityMemberships: OrganizationLayoutAuthorityMember[];
    nextAuthorityMemberId: number | null;
    workspaces: OrganizationLayoutWorkspace[];
    nextWorkspaceId: number | null;
};

export type TenantExportGrant = {
    expiresAt: string;
    downloadPath: string;
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

export type WorkspaceSelection = {
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

/** Current lifecycle states emitted by the legacy rule execution engine. */
export type RuleExecutionStatus = "running" | "matched" | "partial" | "skipped" | "failed";

/** A bounded recent execution returned by the rule audit-log endpoint. */
export type RuleExecution = {
    id: number;
    triggerEntityType: string | null;
    triggerEntityId: number | null;
    status: RuleExecutionStatus;
    executedAt: string;
};

/** Latest execution fields included only in the rule list projection. */
export type RuleExecutionSummary = Pick<RuleExecution, "status" | "executedAt">;

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

/** A rule enriched with its latest execution for the workflows list. */
export type RuleListItem = Rule & {
    latestExecution: RuleExecutionSummary | null;
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

export type WorkflowRuntimeOwner = "legacy" | "canonical";

export type WorkflowRetrySafety = "transactional" | "deduplicated" | "none";

export type WorkflowExecutionMode = "user" | "system";

export type WorkflowNodeType = "TRIGGER" | "CONDITION" | "ACTION" | "DELAY" | "END";

export type WorkflowRuntimeNodeType = Lowercase<WorkflowNodeType>;

export type WorkflowEdgeOutcome = "next" | "yes" | "no";

export type WorkflowTriggerNode = {
    id: string;
    type: "TRIGGER";
    config: RuleTrigger;
};

export type WorkflowConditionNode = {
    id: string;
    type: "CONDITION";
    config: SegmentDefinition;
};

export type WorkflowActionNode = {
    id: string;
    type: "ACTION";
    config: RuleAction;
};

export type WorkflowDelayNode = {
    id: string;
    type: "DELAY";
    config: {
        durationSeconds: number;
    };
};

export type WorkflowEndNode = {
    id: string;
    type: "END";
};

export type WorkflowNode =
    | WorkflowTriggerNode
    | WorkflowConditionNode
    | WorkflowActionNode
    | WorkflowDelayNode
    | WorkflowEndNode;

export type WorkflowEdge = {
    id: string;
    sourceNodeId: string;
    targetNodeId: string;
    outcome: WorkflowEdgeOutcome;
};

export type WorkflowDefinition = {
    schemaVersion: 1;
    entryNodeId: string;
    nodes: WorkflowNode[];
    edges: WorkflowEdge[];
};

export type WorkflowCanvas = {
    positions: Record<string, { x: number; y: number }>;
    viewport: { x: number; y: number; zoom: number };
};

export type WorkflowDto = {
    id: number;
    name: string;
    description: string | null;
    enabled: boolean;
    runtimeOwner: WorkflowRuntimeOwner;
    archivedAt: string | null;
    intakePausedAt: string | null;
    intakePausedById: number | null;
    draftRevision: number;
    recordType: string | null;
    executionMode: WorkflowExecutionMode;
    runAsUserId: number | null;
    definition: WorkflowDefinition;
    canvas: WorkflowCanvas;
    activeVersionId: number | null;
    createdById: number | null;
    updatedById: number | null;
    createdAt: string;
    updatedAt: string;
};

export type WorkflowRunStatus =
    | "queued"
    | "running"
    | "waiting"
    | "succeeded"
    | "failed"
    | "skipped"
    | "cancelled"
    | "intervention_required";

export type WorkflowRunWireStatus = WorkflowRunStatus | "partial";

export type WorkflowListItem = Omit<WorkflowDto, "definition" | "canvas" | "activeVersionId"> & {
    activeVersion: {
        id: number;
        number: number;
        publishedAt: string;
    } | null;
    nodeCount: number;
    actionCount: number;
    latestRun: {
        runKey: string;
        source: "canonical" | "legacy";
        status: WorkflowRunWireStatus;
        legacyStatus: RuleExecutionStatus | null;
        startedAt: string;
        finishedAt: string | null;
        stepDetailAvailable: boolean;
    } | null;
};

export type WorkflowCreateRequest = {
    name: string;
    description: string | null;
    recordType: string | null;
    executionMode: WorkflowExecutionMode;
    definition: WorkflowDefinition;
    canvas: WorkflowCanvas;
};

export type WorkflowDraftRequest = WorkflowCreateRequest & {
    expectedRevision: number;
};

export type WorkflowDiagnosticCode =
    | "canvas_node_position_required"
    | "trigger_count_invalid"
    | "entry_node_required"
    | "entry_trigger_invalid"
    | "branch_outcome_duplicate"
    | "incoming_edge_forbidden"
    | "incoming_edge_required"
    | "incoming_edge_count_invalid"
    | "branch_outcome_required"
    | "branch_outcome_not_allowed"
    | "outgoing_edge_forbidden"
    | "node_unreachable"
    | "graph_cycle"
    | "node_type_unsupported"
    | "schedule_enrollment_condition_required"
    | "trigger_config_required"
    | "condition_config_required"
    | "action_required"
    | "action_config_required"
    | "config_field_invalid"
    | "record_type_invalid"
    | "execution_mode_invalid"
    | "condition_record_type_unsupported"
    | "condition_empty"
    | "condition_limit_exceeded"
    | "condition_depth_exceeded"
    | "condition_match_invalid"
    | "condition_group_empty"
    | "condition_type_invalid"
    | "condition_predicate_unknown"
    | "condition_predicate_record_type_unsupported"
    | "condition_field_required"
    | "condition_field_unknown"
    | "condition_operator_required"
    | "condition_operator_unsupported"
    | "condition_value_required"
    | "condition_value_invalid"
    | "trigger_type_invalid"
    | "entity_change_record_type_unsupported"
    | "trigger_events_required"
    | "trigger_event_unsupported"
    | "schedule_record_type_unsupported"
    | "schedule_cadence_invalid"
    | "schedule_condition_required"
    | "action_type_invalid"
    | "action_record_type_unsupported"
    | "action_field_required"
    | "delay_duration_required"
    | "delay_duration_below_minimum"
    | "delay_duration_above_maximum"
    | "cumulative_delay_above_maximum"
    | "legacy_projection_unsupported"
    | "actor_unavailable"
    | "record_unavailable"
    | "trigger_filter_not_matched"
    | "action_permission_missing"
    | "action_tag_unavailable"
    | "action_target_member_unavailable"
    | "action_stage_unavailable"
    | "action_stage_pipeline_mismatch"
    | "definition_corrupt"
    | "traversal_limit"
    | "trigger_ready"
    | "condition_matched"
    | "condition_not_matched"
    | "enrollment_not_matched"
    | "action_ready"
    | "delay_wait"
    | "end_reached";

export type WorkflowDiagnostic = {
    code: WorkflowDiagnosticCode;
    nodeId: string | null;
    edgeId: string | null;
    fieldPath: string | null;
    params: Record<string, string>;
};

export type WorkflowValidation = {
    draftRevision: number;
    valid: boolean;
    canPublish: boolean;
    systemAuthoringAllowed: boolean;
    requiredPermissions: string[];
    missingPermissions: string[];
    errors: WorkflowDiagnostic[];
};

export type WorkflowSimulation = {
    result: "would_complete" | "not_enrolled" | "would_wait" | "blocked";
    path: Array<{
        nodeId: string;
        nodeType: WorkflowRuntimeNodeType;
        status: string;
        outcome: WorkflowEdgeOutcome | null;
        actionType: string | null;
        code: WorkflowDiagnosticCode;
    }>;
    blockers: Array<{
        code: WorkflowDiagnosticCode;
        nodeId: string | null;
        fieldPath: string | null;
        params: Record<string, string>;
    }>;
};

export type WorkflowVersion = {
    id: number;
    versionNumber: number;
    name: string;
    description: string | null;
    recordType: string | null;
    executionMode: WorkflowExecutionMode;
    runAsUserId: number | null;
    createdById: number | null;
    publishedById: number | null;
    definition: WorkflowDefinition;
    canvas: WorkflowCanvas;
    publishedAt: string;
};

export type WorkflowRunFailure = {
    nodeId: string | null;
    code: string;
    message: string;
};

export type WorkflowRunSummary = {
    runKey: string;
    source: "canonical" | "legacy";
    status: WorkflowRunWireStatus;
    legacyStatus: RuleExecutionStatus | null;
    version: {
        id: number;
        number: number;
        definitionHash: string;
        publishedAt: string;
    } | null;
    trigger: {
        type: "entity_change" | "schedule" | "manual";
        event: string | null;
        recordType: string | null;
        recordId: number | null;
    } | null;
    runtimeState: {
        waitKind: string | null;
        resumeAt: string | null;
        cancellationRequested: boolean;
    } | null;
    startedAt: string;
    finishedAt: string | null;
    durationMs: number | null;
    failure: WorkflowRunFailure | null;
    stepDetailAvailable: boolean;
};

export type WorkflowStepRun = {
    sequence: number;
    nodeId: string;
    nodeType: WorkflowRuntimeNodeType;
    status: Exclude<WorkflowRunStatus, "intervention_required">;
    attempts: number;
    retrySafety: WorkflowRetrySafety;
    selectedOutcome: WorkflowEdgeOutcome | null;
    selectedEdgeId: string | null;
    nextNodeId: string | null;
    startedAt: string;
    finishedAt: string | null;
    durationMs: number | null;
    failure: WorkflowRunFailure | null;
};

export type WorkflowRunDetail = Omit<WorkflowRunSummary, "version"> & {
    workflowId: number;
    version: {
        id: number;
        number: number;
        definitionHash: string;
        publishedAt: string;
        definition: WorkflowDefinition;
        canvas: WorkflowCanvas;
    } | null;
    execution: {
        mode: WorkflowExecutionMode;
        actorUserId: number | null;
        attributionUserId: number | null;
    } | null;
    path: WorkflowStepRun[];
};

export type WorkflowRunPage = {
    items: WorkflowRunSummary[];
    nextCursor: string | null;
};

export type WorkflowRunOperation = {
    runKey: string;
    status: WorkflowRunStatus;
    cancellationRequested: boolean;
};

export type WorkflowIntervention = {
    id: number;
    runKey: string;
    stepNodeId: string | null;
    category: string;
    reasonCode: string;
    ownerUserId: number | null;
    status: string;
    sourceVersion: number;
    createdAt: string;
    updatedAt: string;
};

export type WorkflowOperationsSummary = {
    workflowCount: number;
    healthyCount: number;
    pausedCount: number;
    disabledCount: number;
    interventionRequiredCount: number;
    queuedCount: number;
    waitingCount: number;
    overdueCount: number;
    openInterventionCount: number;
    recentFailureCount: number;
    triggerDiagnostics: WorkflowTriggerDiagnostic[];
};

export type WorkflowTriggerDiagnostic = {
    outboxId: number;
    workflowId: number;
    workflowName: string;
    triggerType: string;
    reasonCode: string;
    failedAt: string;
};

export type WorkflowOperationsRunItem = {
    workflowId: number;
    workflowName: string;
    recipeKey: string | null;
    run: WorkflowRunSummary;
    failureCategory: string | null;
    intervention: WorkflowIntervention | null;
};

export type WorkflowOperationsRunPage = {
    items: WorkflowOperationsRunItem[];
    nextCursor: string | null;
};

export type WorkflowDefinitionChange = {
    fromVersion: number | null;
    toVersion: number;
    publishedAt: string;
    publishedById: number | null;
    addedNodeIds: string[];
    removedNodeIds: string[];
    changedNodeIds: string[];
};

export type WorkflowOperationsDetail = {
    recipeKey: string | null;
    workflow: {
        id: number;
        name: string;
        enabled: boolean;
        archivedAt: string | null;
        intakePausedAt: string | null;
        intakePausedById: number | null;
        runtimeOwner: WorkflowRuntimeOwner;
    };
    health: {
        state: string;
        signals: string[];
    };
    activeVersion: {
        id: number;
        number: number;
        definitionHash: string;
        publishedAt: string;
        publishedById: number | null;
    } | null;
    recentDefinitionChanges: WorkflowDefinitionChange[];
    backlog: {
        queuedCount: number;
        oldestQueuedAt: string | null;
        waitingCount: number;
        dueNowCount: number;
        overdueCount: number;
        nextResumeAt: string | null;
        recentFailureCount: number;
    };
    openInterventions: WorkflowIntervention[];
};

export type WorkflowManualSourceSurface =
    | "record"
    | "record_list"
    | "saved_view"
    | "search"
    | "command_palette";

export type WorkflowManualFilter = {
    query?: string;
    companies?: string[];
    titles?: string[];
    industry?: string[];
    noCompany?: boolean;
    currency?: string;
    pipelineIds?: number[];
    stageIds?: number[];
    companyIds?: number[];
    statuses?: string[];
    risks?: string[];
    memberScope?: string;
    memberIds?: number[];
};

export type WorkflowManualResolvedScope =
    | { kind: "single_record"; recordId: number }
    | { kind: "page_selection" | "explicit_selection"; recordIds: number[] }
    | { kind: "filter_match"; filter: WorkflowManualFilter }
    | { kind: "smart_segment"; definition: SegmentDefinition }
    | { kind: "saved_view"; savedViewId: number }
    | { kind: "search_snapshot"; query: string };

export type WorkflowManualScope = WorkflowManualResolvedScope | {
    kind: "command_palette";
    resolvedScope: WorkflowManualResolvedScope;
};

export type WorkflowManualPrepareRequest = {
    sourceSurface: WorkflowManualSourceSurface;
    scope: WorkflowManualScope;
};

export type WorkflowManualExpectedSkips = {
    permission: number;
    staleState: number;
    missingReference: number;
    limit: number;
    unsupportedContext: number;
};

export type WorkflowManualPreparation = {
    invocationId: number;
    workflowId: number;
    workflowName: string;
    workflowVersionId: number;
    versionNumber: number;
    definitionHash: string;
    executionMode: WorkflowExecutionMode;
    actorUserId: number | null;
    scopeKind: WorkflowManualScope["kind"];
    resolvedScopeKind: WorkflowManualResolvedScope["kind"];
    sourceSurface: WorkflowManualSourceSurface;
    recordType: string;
    scopeToken: string;
    scopeHash: string;
    expiresAt: string;
    exactCount: number;
    readyCount: number;
    expectedSkips: WorkflowManualExpectedSkips;
    samples: Array<{ recordId: number; label: string }>;
    actions: Array<{ nodeId: string; actionType: string; retrySafety: WorkflowRetrySafety }>;
    confirmable: boolean;
    blockers: string[];
};

export type WorkflowInvocationRecord = {
    recordId: number;
    status: string;
    reasonCode: string | null;
    runKey: string | null;
};

export type WorkflowInvocationResult = {
    invocationId: number;
    status: string;
    exactCount: number;
    queuedCount: number;
    runningCount: number;
    waitingCount: number;
    succeededCount: number;
    failedCount: number;
    interventionRequiredCount: number;
    cancelledCount: number;
    skippedCount: number;
    createdAt: string;
    confirmedAt: string | null;
    completedAt: string | null;
    records: WorkflowInvocationRecord[];
};

export type WorkflowRecipeAction = {
    actionType: string;
    retrySafety: WorkflowRetrySafety;
};

export type WorkflowRecipe = {
    recipeKey: string;
    recipeVersion: number;
    schemaVersion: number;
    titleKey: string;
    descriptionKey: string;
    sourceEvent: string;
    actorModel: string;
    dataRead: string[];
    dataWritten: string[];
    requiredParameters: string[];
    requiredPermissions: string[];
    lockedFields: string[];
    editableFields: string[];
    sideEffects: string[];
    actions: WorkflowRecipeAction[];
    disableBehavior: string;
    removeBehavior: string;
};

export type WorkflowRecipeParameters = Record<string, string | number | boolean | null>;

export type WorkflowRecipePreview = {
    recipe: WorkflowRecipe;
    previewHash: string;
    definition: WorkflowDefinition;
    canvas: WorkflowCanvas;
    unresolvedParameters: string[];
    validation: WorkflowValidation;
    plannedActions: Array<{ nodeId: string; actionType: string; retrySafety: WorkflowRetrySafety }>;
    exampleResult: WorkflowSimulation | null;
    writesCreated: false;
};

export type WorkflowRecipeInstallResult = {
    recipeKey: string;
    recipeVersion: number;
    templateHash: string;
    workflow: WorkflowDto;
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
    changes?: Record<string, unknown> | null;
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

export type DeliveryEmailProvider = "smtp" | "http_esp";

export type DeliverySmsProvider = "sms_http";

export type DeliveryProvider = DeliveryEmailProvider | DeliverySmsProvider;

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
    connectedAccounts: { google: boolean; microsoft: boolean };
    connectedCapture: { google: boolean; microsoft: boolean };
    mailManaged: boolean;
    businessCardScanning: boolean;
    businessCardImport: boolean;
    campaignDelivery: boolean;
};

export type ConnectedAccountProvider = 'google' | 'microsoft';

export type ProviderConnectionStatus =
    | 'connected'
    | 'paused'
    | 'error'
    | 'revoked'
    | 'disconnecting'
    | 'purge_failed';

/** A user's OAuth connection to an external mail/calendar provider (masked, no token material). */
export type ProviderConnection = {
    provider: ConnectedAccountProvider;
    status: ProviderConnectionStatus;
    providerAccountEmail?: string | null;
    grantedScopes?: string | null;
    hasCredential: boolean;
    lastSyncAt?: string | null;
    errorCode?: string | null;
    createdAt: string;
    updatedAt: string;
};

export type CaptureStream = 'calendar' | 'mail_inbox' | 'mail_sent';

export type CaptureHealthStatus =
    | 'idle'
    | 'queued'
    | 'backfilling'
    | 'syncing'
    | 'retrying'
    | 'intervention_required'
    | 'paused'
    | 'purging';

export type CaptureAdmissionMode = 'manual' | 'review' | 'automatic';

export type ProviderCapturePolicy = {
    enabled: boolean;
    calendar: boolean;
    mailInbox: boolean;
    mailSent: boolean;
    backfillDays: number;
    includeBodies: boolean;
    admissionMode: CaptureAdmissionMode;
    reviewBeforeCapture: boolean;
    excludedPeople: string[];
    excludedConversations: string[];
    version: number;
    updatedAt: string | null;
};

export type WorkspaceCapturePolicy = {
    allowed: boolean;
    calendar: boolean;
    mailInbox: boolean;
    mailSent: boolean;
    maxBackfillDays: number;
    bodyCaptureAllowed: boolean;
    reviewRequired: boolean;
    excludePrivateEvents: boolean;
    excludeInternalOnly: boolean;
    excludedDomains: string[];
    version: number;
    updatedAt: string | null;
};

export type EffectiveCapturePolicy = {
    enabled: boolean;
    calendar: boolean;
    mailInbox: boolean;
    mailSent: boolean;
    backfillDays: number;
    includeBodies: boolean;
    admissionMode: CaptureAdmissionMode;
    restrictionCodes: string[];
};

export type CaptureStreamState = {
    stream: CaptureStream;
    status: CaptureHealthStatus;
    processedItems: number;
    estimatedItems: number | null;
    lastAttemptAt: string | null;
    lastSuccessAt: string | null;
    nextAttemptAt: string | null;
    errorCode: string | null;
};

export type CaptureDisclosures = {
    scopes: string[];
    admittedFields: string[];
    materialExclusions: string[];
    visibility: string[];
    retention: string[];
};

export type CapturePurgeStatus = 'idle' | 'disconnecting' | 'purge_failed';

export type CapturePurgeState = {
    active: boolean;
    status: CapturePurgeStatus;
    errorCode: string | null;
};

export type ProviderCaptureOverview = {
    provider: ConnectedAccountProvider;
    userPolicy: ProviderCapturePolicy;
    workspacePolicy: WorkspaceCapturePolicy;
    effectivePolicy: EffectiveCapturePolicy;
    streams: CaptureStreamState[];
    reviewCount: number;
    pendingApprovalCount: number;
    activationReady: boolean;
    disclosures: CaptureDisclosures;
    purge: CapturePurgeState;
};

export type CaptureOverview = {
    providers: ProviderCaptureOverview[];
};

export type CaptureReviewAction = 'attach' | 'create' | 'ignore';

export type CaptureReviewHeldReason =
    | 'no_match'
    | 'multiple_matches'
    | 'invalid_identity'
    | 'restricted_person'
    | 'approval_required';

export type CaptureReviewCandidate = {
    personId: number;
    name: string;
};

export type CaptureReviewItem = {
    id: number;
    version: number;
    interactionId: number;
    interactionVersion: number;
    provider: ConnectedAccountProvider;
    stream: CaptureStream;
    interactionType: string;
    subject: string | null;
    occurredAt: string;
    participantRole: string;
    displayName: string | null;
    email: string | null;
    matchState: string;
    heldReason: CaptureReviewHeldReason;
    candidates: CaptureReviewCandidate[];
    allowedActions: CaptureReviewAction[];
};

export type CaptureReviewPage = {
    items: CaptureReviewItem[];
    total: number;
    page: number;
    size: number;
};

export type CaptureReviewDecision =
    | {
        action: 'attach';
        personId: number;
        rememberExact: boolean;
        version: number;
    }
    | {
        action: 'create';
        contact: CreateContactPayload;
        duplicateReviewToken: string;
        rememberExact: boolean;
        version: number;
    }
    | {
        action: 'ignore';
        rememberExact: boolean;
        version: number;
    };

export type CapturedActivityEvidence = {
    provider: ConnectedAccountProvider;
    stream: CaptureStream;
    sourceId: string;
    capturedAt: string;
    captureAsOf: string;
    visibility: string;
    admittedFields: string[];
    materialExclusions: string[];
    editable: false;
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

export type ClientErrorReportPayload = {
    digest?: string | null;
    message: string;
    stack?: string | null;
    path?: string | null;
};

export type SecretStoreKeyDiagnostic = {
    keyId: string | null;
    status: string;
    active: boolean;
    configured: boolean;
    disabled: boolean;
    version: string | null;
    algorithm: string | null;
    owner: string | null;
    scope: string | null;
    createdAt: string | null;
    rotatedAt: string | null;
    disabledAt: string | null;
    secretCount: number;
    staleSecretCount: number;
    mismatchedSecretCount: number;
    unsupportedAlgorithmSecretCount: number;
};

export type SecretStoreSecretDiagnostic = {
    secretId: number;
    scopeType: string;
    scopeId: number;
    purpose: string | null;
    keyId: string | null;
    status: string;
    reason: string | null;
};

export type SecretStoreDiagnostics = {
    scopeType: string | null;
    scopeId: number | null;
    activeKeyId: string | null;
    activeKeyConfigured: boolean;
    activeKeyDisabled: boolean;
    available: boolean;
    healthy: boolean;
    totalSecrets: number;
    activeSecrets: number;
    staleSecrets: number;
    missingKeySecrets: number;
    disabledKeySecrets: number;
    mismatchedSecrets: number;
    unsupportedAlgorithmSecrets: number;
    keys: SecretStoreKeyDiagnostic[];
    failures: SecretStoreSecretDiagnostic[];
};

export type DiagnosticsScopeType = "workspace" | "organization";

export type DiagnosticsMailMode =
    | "managed"
    | "workspace_override"
    | "instance_default"
    | "unconfigured";

export type JobRunStatus = "succeeded" | "failed" | "skipped";

export type DiagnosticsFindingSeverity = "info" | "warning";

export type DnsAdvisoryStatus = "present" | "unknown" | "not_configured";

export type MailTransportOutcome = "succeeded" | "failed" | "unconfigured";

export type DiagnosticsScope = {
    type: DiagnosticsScopeType;
    id: number;
};

export type DiagnosticsCapability = {
    capability: string;
    profileAllowed: boolean;
    available: boolean;
};

export type DiagnosticsDeployment = {
    profile: string | null;
    configured: boolean;
    capabilities: DiagnosticsCapability[];
};

export type DiagnosticsAiReadiness = {
    ready: boolean;
    imageInputReady: boolean;
};

export type DiagnosticsOcrReadiness = {
    scanningAvailable: boolean;
    importAvailable: boolean;
};

export type DiagnosticsMailReadiness = {
    mode: DiagnosticsMailMode;
    configured: boolean;
};

export type DiagnosticsDeliveryReadiness = {
    channel: string;
    implemented: boolean;
    ready: boolean;
};

export type DiagnosticsCaptureStream = {
    provider: string;
    stream: string;
    status: string;
    stateCount: number;
    stableCursorCount: number;
    pageCursorCount: number;
    processedItems: number;
    estimatedItems: number;
    lastAttemptAt: string | null;
    lastSuccessAt: string | null;
    nextAttemptAt: string | null;
    errorCodes: string[];
};

export type DiagnosticsWorkspaceProviders = {
    workspaceId: number;
    mail: DiagnosticsMailReadiness;
    delivery: DiagnosticsDeliveryReadiness[];
    capture: DiagnosticsCaptureStream[];
};

export type DiagnosticsProviders = {
    ai: DiagnosticsAiReadiness;
    ocr: DiagnosticsOcrReadiness;
    workspaces: DiagnosticsWorkspaceProviders[];
};

export type DiagnosticsJobRun = {
    workspaceId: number | null;
    status: JobRunStatus;
    startedAt: string;
    finishedAt: string;
    detail: Record<string, string | number | boolean> | null;
};

export type DiagnosticsJob = {
    jobName: string;
    last: DiagnosticsJobRun | null;
    lastSuccess: DiagnosticsJobRun | null;
    lastFailure: DiagnosticsJobRun | null;
    workspacesFailingLatest: number;
};

export type DiagnosticsFinding = {
    code: string;
    severity: DiagnosticsFindingSeverity;
    workspaceId?: number | null;
    capability?: string | null;
    provider?: string | null;
    channel?: string | null;
    stream?: string | null;
};

export type DiagnosticsSectionFault = {
    section: string;
    reason: string;
};

export type TenantDiagnostics = {
    scope: DiagnosticsScope;
    deployment: DiagnosticsDeployment;
    providers: DiagnosticsProviders;
    jobs: DiagnosticsJob[];
    findings: DiagnosticsFinding[];
    secretStore: SecretStoreDiagnostics | null;
    unavailableSections: DiagnosticsSectionFault[];
};

export type MailDnsAdvisoryRecord = {
    queryName: string;
    status: DnsAdvisoryStatus;
};

export type MailDnsAdvisory = {
    advisory: boolean;
    domain: string | null;
    spf: MailDnsAdvisoryRecord;
    dkim: MailDnsAdvisoryRecord;
    dmarc: MailDnsAdvisoryRecord;
};

export type MailDiagnosticSender = {
    address: string | null;
    displayName: string | null;
};

export type MailDiagnosticTransport = {
    mode: DiagnosticsMailMode;
    host?: string | null;
    port?: number | null;
    outcome: MailTransportOutcome;
    errorCode: string | null;
};

export type MailDiagnosticTest = {
    correlationId: string | null;
    sender: MailDiagnosticSender;
    transport: MailDiagnosticTransport;
    dns: MailDnsAdvisory;
};
