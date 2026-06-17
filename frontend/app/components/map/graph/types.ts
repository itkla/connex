import type { Node, Edge } from '@xyflow/react';
import type { Activity, Company, CompanyMetrics, Contact, Deal, Note, Task, User } from '@/app/lib/types';
import type { DealOutcome, StageClass } from '@/app/components/records/deals/dealOutcome';

// note to hunter in the future: yo this type file is ONLY used by the graph; it isn't a general object definition file like the other type.ts is. therefore you should keep the file here as-is

export type NodeKind = 'uc' | 'user' | 'company' | 'contact';

export type UCNodeData = {
    kind: 'uc';
    label: string;
};

export type UserNodeData = {
    kind: 'user';
    user: User;
};

export type CompanyNodeData = {
    kind: 'company';
    company: Company;
    metrics: CompanyMetrics;
    expanded: boolean;
    hovered?: boolean; // transient: this node's tree is hovered, so bloom to a logo
};

export type ContactNodeData = {
    kind: 'contact';
    contact: Contact;
    hasActivity: boolean;
    expanded: boolean;
    hovered?: boolean; // transient: this node's tree is hovered, so bloom to a pfp
};

export type UCNode = Node<UCNodeData, 'uc'>;
export type UserNode = Node<UserNodeData, 'user'>;
export type CompanyNode = Node<CompanyNodeData, 'company'>;
export type ContactNode = Node<ContactNodeData, 'contact'>;
export type AppNode = UCNode | UserNode | CompanyNode | ContactNode;

export type DealSummary = {
    id: number;
    name: string;
    outcome: DealOutcome; // 'open' | 'won' | 'lost' | 'closed'
    value: number;
    currency: string;
    stageName?: string;
    closedAt?: string;
};

export type RelationEdgeData = {
    variant: 'uc-user' | 'rel-cc' | 'cc-co';
    dashed: boolean;
    ucColor?: string;
    ccColor?: string;
    deals?: DealSummary[];
};

export type RelationEdge = Edge<RelationEdgeData, 'relation'>;

export type Graph = {
    nodes: AppNode[];
    edges: RelationEdge[];
};

export type GraphInput = {
    companies: Company[];
    contacts: Contact[];
    deals: Deal[];
    users: User[];
    activities: Activity[];
    tasks: Task[];
    notes: Note[];
    stageNames: Map<number, string>;
    stageClass: Map<number, StageClass>;
    ucLabel: string;
};