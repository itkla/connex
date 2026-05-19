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

export type Task = {
    id: number;
    description: string;
    completed: boolean;
    dueDate?: string;
    assignedTo: number;
    person?: number | null;
    deal?: number | null;
    createdAt: string;
    updatedAt: string;
};

export type Activity = {
    id: number;
    type: string;
    subject: string;
    notes?: string;
    person?: number | null;
    deal?: number | null;
    createdBy: number;
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
    createdAt: string;
    updatedAt: string;
};

export type Contact = {
    id: number;
    name: string;
    email: string;
    phone: string;
    company?: Company;
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
    companyId?: number;
    imageUrl?: string;
};