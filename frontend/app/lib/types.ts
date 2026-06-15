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

export type PersonFacets = {
    companies: string[];
    titles: string[];
    hasNoCompany: boolean;
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
    expectedCloseDate?: string;
    closedAt?: string;
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
    expectedCloseDate?: string;
    closedAt?: string;
};

export type UpdateDealPayload = {
    name: string;
    value: number;
    actualValue: number;
    currency: string;
    pipeline: number;
    stage: number;
    company?: number | null;
    expectedCloseDate?: string | null;
    closedAt?: string | null;
};

export type Pipeline = {
    id: number;
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

export type AttachmentEntityType = 'company' | 'person' | 'deal' | 'user';

export type Attachment = {
    id: number;
    entityType: string;
    entityId: number;
    fileName: string;
    url: string;
    contentType?: string;
    size?: number;
    uploadedBy?: number;
    uploadedByName?: string;
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
};