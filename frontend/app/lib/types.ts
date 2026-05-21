// type definitions becaue the api.ts library was getting too bloated

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

export type CreateActivityPayload = {
    type: string;
    subject: string;
    notes?: string;
    personId?: number;
    dealId?: number;
    createdById: number;
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
    createdAt: string;
    updatedAt: string;
};

// metrics for a company, filled via relationship traversal
export type CompanyMetrics = {
    persons: Contact[];
    relatedUsers: User[];
    pastRevenue: number;
    projectedRevenue: number;
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
    currency: string;
    pipeline: number;
    stage: number;
    company: number;
    expectedCloseDate: string;
    closedAt: string;
    createdAt: string;
    updatedAt: string;
};

export type Pipeline = {
    id: number;
    name: string;
    createdAt: string;
    updatedAt: string;
};

export type Tag = {
    id: number;
    name: string;
    color: string;
    createdAt: string;
    updatedAt: string;
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