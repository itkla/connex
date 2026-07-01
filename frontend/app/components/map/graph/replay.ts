import type { ReplayDealResolution, ReplayFrame, TemperatureBand } from '@/app/lib/types';
import { parseMysqlDateTime } from '@/app/lib/utils';
import { COLOR_LOST, COLOR_UC, COLOR_WON, UC_ID, buildGraph, companyNodeId, contactNodeId } from './buildGraph';
import type { DealSummary, Graph, GraphInput, RelationEdge } from './types';

/**
 * A single replay frame resolved against the master graph: which node/edge ids are present, each
 * present node's warmth band, and each relationship edge's deal-resolution colour. This is everything
 * the map needs to render frame F by toggling node/edge state, without recomputing the graph or the
 * force layout.
 */
export type ComputedFrame = {
    /** The frame's calendar date as a UTC {@code yyyy-MM-dd} string. */
    date: string;
    presentNodeIds: Set<string>;
    nodeWarmth: Map<string, TemperatureBand>;
    presentEdgeIds: Set<string>;
    /** Relationship-edge id → deal-resolution colour as of this frame. */
    edgeCcColor: Map<string, string>;
};

/**
 * Builds the master graph for replay: the live graph (every still-existing node is the union of nodes
 * that can appear in any frame) augmented with a company→contact edge for every employer a contact
 * held across the window, plus an org-node fallback edge for any frame in which they were unaffiliated,
 * so employment moves render by toggling which edge is present rather than by relaying out.
 */
export function buildMasterGraph(input: GraphInput, frames: ReplayFrame[]): Graph {
    const base = buildGraph(input);
    const edges = base.edges.slice();
    const edgeIds = new Set(edges.map((e) => e.id));
    const companyNodeIds = new Set(base.nodes.filter((n) => n.data.kind === 'company').map((n) => n.id));

    const employersByContact = new Map<number, Set<number>>();
    const unaffiliated = new Set<number>();
    for (const frame of frames) {
        for (const c of frame.contacts) {
            if (c.employerId == null) {
                unaffiliated.add(c.id);
            } else {
                let set = employersByContact.get(c.id);
                if (!set) {
                    set = new Set();
                    employersByContact.set(c.id, set);
                }
                set.add(c.employerId);
            }
        }
    }

    const addEmploymentEdge = (source: string, contactId: number) => {
        const id = `${source}--${contactNodeId(contactId)}`;
        if (edgeIds.has(id)) return;
        edgeIds.add(id);
        const edge: RelationEdge = {
            id,
            type: 'relation',
            source,
            target: contactNodeId(contactId),
            data: { variant: 'cc-co', dashed: false },
        };
        edges.push(edge);
    };

    for (const [contactId, employers] of employersByContact) {
        for (const employerId of employers) {
            const source = companyNodeId(employerId);
            if (companyNodeIds.has(source)) addEmploymentEdge(source, contactId);
        }
    }
    for (const contactId of unaffiliated) addEmploymentEdge(UC_ID, contactId);

    return { nodes: base.nodes, edges };
}

/**
 * Resolves each replay frame against the master graph into the per-frame presence, warmth, and
 * edge-colour maps the renderer applies. The org node and team members are present in every frame;
 * companies, contacts, and deals follow the backend's as-of membership, and each present contact has
 * exactly one employment edge (to its as-of employer, or the org node when unaffiliated).
 */
export function toComputedFrames(frames: ReplayFrame[], master: Graph): ComputedFrame[] {
    const alwaysPresent = new Set(
        master.nodes.filter((n) => n.data.kind === 'uc' || n.data.kind === 'user').map((n) => n.id),
    );
    const companyNodeIds = new Set(master.nodes.filter((n) => n.data.kind === 'company').map((n) => n.id));

    const dealsByCompanyNode = new Map<string, DealSummary[]>();
    for (const e of master.edges) {
        if (e.data && e.data.variant === 'rel-cc' && e.data.deals) dealsByCompanyNode.set(e.target, e.data.deals);
    }

    return frames.map((frame) => {
        const presentNodeIds = new Set(alwaysPresent);
        const nodeWarmth = new Map<string, TemperatureBand>();
        for (const c of frame.companies) {
            const id = companyNodeId(c.id);
            presentNodeIds.add(id);
            nodeWarmth.set(id, c.band);
        }
        const employerOf = new Map<number, number | null>();
        for (const c of frame.contacts) {
            const id = contactNodeId(c.id);
            presentNodeIds.add(id);
            nodeWarmth.set(id, c.band);
            employerOf.set(c.id, c.employerId ?? null);
        }

        const resolution = new Map<number, ReplayDealResolution>();
        for (const d of frame.deals) resolution.set(d.id, d.resolution);

        const presentEdgeIds = new Set<string>();
        const edgeCcColor = new Map<string, string>();
        for (const e of master.edges) {
            if (!e.data) continue;
            if (e.data.variant === 'uc-user') {
                presentEdgeIds.add(e.id);
            } else if (e.data.variant === 'rel-cc' && presentNodeIds.has(e.target)) {
                presentEdgeIds.add(e.id);
                const deals = dealsByCompanyNode.get(e.target);
                if (deals) edgeCcColor.set(e.id, ccColorForFrame(deals, resolution));
            }
        }
        for (const [contactId, employerId] of employerOf) {
            const source =
                employerId != null && companyNodeIds.has(companyNodeId(employerId))
                    ? companyNodeId(employerId)
                    : UC_ID;
            presentEdgeIds.add(`${source}--${contactNodeId(contactId)}`);
        }

        return { date: frame.asOf, presentNodeIds, nodeWarmth, presentEdgeIds, edgeCcColor };
    });
}

/** Deal-resolution colour for a company's relationship edge as of one frame (latest close wins). */
function ccColorForFrame(deals: DealSummary[], resolution: Map<number, ReplayDealResolution>): string {
    const closed = deals
        .filter((d) => {
            const r = resolution.get(d.id);
            return r === 'won' || r === 'lost';
        })
        .sort((a, b) => parseMysqlDateTime(b.closedAt) - parseMysqlDateTime(a.closedAt));
    const latest = closed[0] ? resolution.get(closed[0].id) : undefined;
    if (latest === 'won') return COLOR_WON;
    if (latest === 'lost') return COLOR_LOST;
    return COLOR_UC;
}
