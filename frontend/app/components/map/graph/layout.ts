import { UC_ID } from './buildGraph';
import type { AppNode, CompanyNode, ContactNode, Graph, UserNode } from './types';

export const RING_RADIUS: Record<string, number> = {
    uc: 0,
    user: 180,
    company: 410,
    contact: 500,
};

const TAU = Math.PI * 2;

function ring(r: number, a: number) {
    return { x: r * Math.cos(a), y: r * Math.sin(a) };
}

export function radialLayout(graph: Graph): AppNode[] {
    const { nodes, edges } = graph;

    const users = nodes.filter((n): n is UserNode => n.type === 'user');
    const companies = nodes.filter((n): n is CompanyNode => n.type === 'company');
    const contacts = nodes.filter((n): n is ContactNode => n.type === 'contact');

    const ccParent = new Map<string, string>();
    const coParent = new Map<string, string>();
    for (const e of edges) {
        if (e.data?.variant === 'rel-cc' && !ccParent.has(e.target)) ccParent.set(e.target, e.source);
        else if (e.data?.variant === 'cc-co') coParent.set(e.target, e.source);
    }

    const angleOf = new Map<string, number>();
    const posOf = new Map<string, { x: number; y: number }>();
    posOf.set(UC_ID, { x: 0, y: 0 });

    users.forEach((u, i) => {
        const a = (i / Math.max(users.length, 1)) * TAU;
        angleOf.set(u.id, a);
        posOf.set(u.id, ring(RING_RADIUS.user, a));
    });

    placeChildren(companies, ccParent, angleOf, posOf, RING_RADIUS.company);
    placeChildren(contacts, coParent, angleOf, posOf, RING_RADIUS.contact);

    return nodes.map((n) => ({ ...n, position: posOf.get(n.id) ?? { x: 0, y: 0 } }));
}

function placeChildren<T extends AppNode>(
    children: T[],
    parentOf: Map<string, string>,
    angleOf: Map<string, number>,
    posOf: Map<string, { x: number; y: number }>,
    radius: number,
) {
    const byParent = new Map<string, T[]>();
    for (const c of children) {
        const p = parentOf.get(c.id) ?? UC_ID;
        const arr = byParent.get(p);
        if (arr) arr.push(c);
        else byParent.set(p, [c]);
    }

    for (const [parent, group] of byParent) {
        const k = group.length;
        const spread = Math.min(k * 0.18, 1.4); // arc width shared by siblings
        const base = angleOf.get(parent); // undefined for UC -> spread full circle
        group.forEach((c, j) => {
            const a =
                base == null
                    ? (j / Math.max(k, 1)) * TAU
                    : base - spread / 2 + ((j + 0.5) / k) * spread;
            angleOf.set(c.id, a);
            posOf.set(c.id, ring(radius, a));
        });
    }
}