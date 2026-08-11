import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

import { ApiError, DEFAULT_CAPABILITIES } from '@/app/lib/api';
import {
    canCreateExport,
    canCreateSend,
    canEstimateAudience,
    canFreezeSnapshot,
    resolveCampaignAccess,
} from '@/app/lib/campaignAccess';
import { NO_NAV_ACCESS, resolveNavAccess } from '@/app/lib/navAccess';
import { loadCollection } from '@/app/lib/recordAccess';

const CAMPAIGN_DETAIL = 'app/components/marketing/campaigns/CampaignDetail.tsx';
const CAMPAIGN_LIST = 'app/(app)/marketing/campaigns/page.tsx';
const CAMPAIGN_DETAIL_PAGE = 'app/(app)/marketing/campaigns/[id]/page.tsx';
const DELIVERY_PANEL = 'app/components/marketing/campaigns/CampaignDelivery.tsx';
const EXPORT_PANEL = 'app/components/marketing/campaigns/CampaignExportPanel.tsx';
const BROWSER = 'app/components/marketing/campaigns/CampaignsBrowser.tsx';
const SIDEBAR = 'app/components/Sidebar.tsx';
const NAV_BRIDGE = 'app/components/actions/NavActionsBridge.tsx';
const SEED_ACTIONS = 'app/lib/actions/seedActions.ts';

function source(relativePath: string): string {
    return readFileSync(path.resolve(process.cwd(), relativePath), 'utf8');
}

type MessageValue = string | { [key: string]: MessageValue };

function isMessageTree(value: unknown): value is { [key: string]: MessageValue } {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Reads one leaf string from a locale catalog, so a missing or restructured key fails loudly. */
function message(locale: 'en' | 'ja', namespace: string, key: string): string {
    const parsed: unknown = JSON.parse(source(`messages/${locale}/campaigns.json`));
    if (!isMessageTree(parsed)) {
        throw new Error(`messages/${locale}/campaigns.json is not a JSON object`);
    }
    const scope = parsed[namespace];
    if (!isMessageTree(scope)) {
        throw new Error(`messages/${locale}/campaigns.json has no ${namespace} namespace`);
    }
    const value = scope[key];
    if (typeof value !== 'string') {
        throw new Error(`messages/${locale}/campaigns.json is missing ${namespace}.${key}`);
    }
    return value;
}

/** Mirrors `WorkspaceService.memberPermissions()`. */
const MEMBER_PERMISSIONS: readonly string[] = [
    'COMPANY_CREATE', 'COMPANY_UPDATE',
    'PERSON_CREATE', 'PERSON_UPDATE', 'PERSON_DELETE',
    'DEAL_CREATE', 'DEAL_UPDATE', 'DEAL_DELETE',
    'ACTIVITY_CREATE', 'ACTIVITY_UPDATE', 'ACTIVITY_DELETE',
    'NOTE_CREATE', 'NOTE_UPDATE', 'NOTE_DELETE',
    'TASK_CREATE', 'TASK_UPDATE', 'TASK_DELETE',
    'ATTACHMENT_CREATE', 'ATTACHMENT_DELETE',
    'REPORT_READ', 'REPORT_CREATE', 'REPORT_UPDATE', 'REPORT_DELETE',
    'GOAL_READ', 'CAMPAIGN_VIEW',
];

/** Mirrors the campaign-relevant additions in `WorkspaceService.adminPermissions()`. */
const ADMIN_PERMISSIONS: readonly string[] = [
    ...MEMBER_PERMISSIONS,
    'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND', 'CONSENT_MANAGE',
];

/**
 * The backend guards person data with `CONSENT_MANAGE` on top of the campaign permissions, and the
 * built-in `member` role holds neither that nor `CAMPAIGN_MANAGE`. These assert the resolved
 * contract rather than the presence of a gate in the source, because an earlier revision of this
 * file pinned the *wrong* contract — that estimating needs only `CAMPAIGN_VIEW` — and stayed green.
 */
describe('the built-in member role is offered no campaign control that would 403', () => {
    const member = resolveCampaignAccess(MEMBER_PERMISSIONS);

    it('holds campaign view but neither campaign management nor consent', () => {
        expect(member).toEqual({ manage: false, send: false, consent: false });
    });

    it('cannot estimate a person audience, which is the UI default', () => {
        expect(canEstimateAudience(member, 'person')).toBe(false);
    });

    it('can still estimate audiences that never reach person data', () => {
        expect(canEstimateAudience(member, 'company')).toBe(true);
        expect(canEstimateAudience(member, 'deal')).toBe(true);
    });

    it('cannot freeze a snapshot of any audience', () => {
        expect(canFreezeSnapshot(member, 'person')).toBe(false);
        expect(canFreezeSnapshot(member, 'company')).toBe(false);
    });

    it('cannot materialize a send or push an export', () => {
        expect(canCreateSend(member)).toBe(false);
        expect(canCreateExport(member)).toBe(false);
    });
});

describe('the built-in admin role is offered every campaign control', () => {
    const admin = resolveCampaignAccess(ADMIN_PERMISSIONS);

    it('resolves all three campaign permissions', () => {
        expect(admin).toEqual({ manage: true, send: true, consent: true });
    });

    it('may estimate, freeze, send and export', () => {
        expect(canEstimateAudience(admin, 'person')).toBe(true);
        expect(canFreezeSnapshot(admin, 'person')).toBe(true);
        expect(canCreateSend(admin)).toBe(true);
        expect(canCreateExport(admin)).toBe(true);
    });
});

describe('a custom role with campaign management but no consent is still held back', () => {
    const manager = resolveCampaignAccess(['CAMPAIGN_VIEW', 'CAMPAIGN_MANAGE', 'CAMPAIGN_SEND']);

    it('may author a campaign but not reach person data', () => {
        expect(manager.manage).toBe(true);
        expect(canEstimateAudience(manager, 'person')).toBe(false);
        expect(canFreezeSnapshot(manager, 'person')).toBe(false);
        expect(canCreateSend(manager)).toBe(false);
        expect(canCreateExport(manager)).toBe(false);
    });

    it('may still work an audience that never reaches person data', () => {
        expect(canFreezeSnapshot(manager, 'company')).toBe(true);
    });
});

describe('the campaign surface consumes the resolved contract', () => {
    it('disables estimating on the consent-aware answer, not on campaign view', () => {
        const detail = source(CAMPAIGN_DETAIL);

        expect(detail).toContain('canEstimateAudience(access, recordType)');
        expect(detail).toContain('!audienceSaved || isEstimating || !canEstimate');
    });

    it('gates freezing on the consent-aware answer', () => {
        const detail = source(CAMPAIGN_DETAIL);

        expect(detail).toContain('canFreezeSnapshot(access, recordType)');
        expect(detail).toContain('{canFreeze && (');
    });

    it('gates send and export creation on consent as well as management', () => {
        expect(source(DELIVERY_PANEL)).toContain('canCreateSend(access)');
        expect(source(DELIVERY_PANEL)).toContain('{canMaterializeSend && (');
        expect(source(EXPORT_PANEL)).toContain('canCreateExport(access)');
        expect(source(EXPORT_PANEL)).toContain('{canPushExport && (');
    });

    it('gates campaign creation, which needs campaign management', () => {
        expect(source(BROWSER)).toContain('canCreate');
        expect(source(CAMPAIGN_LIST)).toContain('includes("CAMPAIGN_MANAGE")');
    });
});

/**
 * The operations article states that "the delivery and export panels say up front when sending is
 * not enabled on this instance". That is a customer-facing claim, so both banners must sit outside
 * the permission gates — the reader who most needs to tell a disabled instance from a missing
 * permission is exactly the one who cannot act.
 */
describe('both delivery panels state availability to everyone who can see them', () => {
    it('renders the export banner outside the permission gate', () => {
        const panel = source(EXPORT_PANEL);

        expect(panel.indexOf('{deliveryAvailability === "unavailable" ? (')).toBeGreaterThan(-1);
        expect(panel.indexOf('{deliveryAvailability === "unavailable" ? (')).toBeLessThan(
            panel.indexOf('{canPushExport && ('),
        );
    });

    it('renders the delivery banner outside the permission gate', () => {
        const panel = source(DELIVERY_PANEL);

        expect(panel.indexOf('{deliveryAvailability === "unavailable" ? (')).toBeGreaterThan(-1);
        expect(panel.indexOf('{deliveryAvailability === "unavailable" ? (')).toBeLessThan(
            panel.indexOf('{canMaterializeSend && ('),
        );
    });

    it('latches a stale capability snapshot exactly as the delivery panel does', () => {
        expect(source(EXPORT_PANEL)).toContain(
            'const exportUnavailable = deliveryAvailability !== "enabled" || exportRefused;',
        );
        expect(source(DELIVERY_PANEL)).toContain(
            'const deliveryUnavailable = deliveryAvailability !== "enabled" || deliveryRefused;',
        );
    });

    it('keeps the refusal path reachable rather than gating it on what it reports', () => {
        const panel = source(EXPORT_PANEL);

        expect(panel).not.toContain('err.status === 403 && deliveryAvailability !== "enabled"');
        expect(panel).toContain('setExportRefused(true);');
    });
});

describe('/marketing/campaigns never manufactures an empty campaign list', () => {
    it('reports a refused campaign fetch as forbidden rather than as empty', async () => {
        const access = await loadCollection(async () => {
            throw new ApiError('Requires the CAMPAIGN_VIEW permission in this workspace', 403);
        });

        expect(access).toEqual({ kind: 'forbidden' });
    });

    it('does not swallow a refused fetch into an empty list', () => {
        const page = source(CAMPAIGN_LIST);

        expect(page).not.toMatch(/\.catch\s*\(/);
        expect(page).toContain('loadCollection(');
        expect(page).not.toContain('getCampaignsFromCookie');
    });

    it('returns the denial state for a viewer who lacks campaign access', () => {
        const page = source(CAMPAIGN_LIST);

        expect(page).toMatch(/access\.kind === "forbidden"/);
        expect(page).toContain('<AccessDeniedPage');
        expect(page.indexOf('forbidden')).toBeLessThan(page.indexOf('<CampaignsBrowser'));
    });

    it('localizes the denial copy in both supported locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            const title = message(locale, 'CampaignsPage', 'deniedTitle');
            const body = message(locale, 'CampaignsPage', 'deniedBody');

            expect(title).toBeTruthy();
            expect(body).toBeTruthy();
            expect(body).not.toBe(message(locale, 'CampaignsPage', 'emptyHint'));
        }
    });
});

describe('restricted snapshots are reported as restricted, not as none', () => {
    it('classifies a refused snapshot listing instead of catching it away', () => {
        const page = source(CAMPAIGN_DETAIL_PAGE);

        expect(page).toContain('loadCollection(() => getCampaignSnapshots(id, init))');
        expect(page).not.toContain('getCampaignSnapshots(id, init).catch');
        expect(page).toContain('snapshotsRestricted={snapshotsAccess.kind === "forbidden"}');
    });

    it('renders the denial grammar rather than the empty-snapshot copy', () => {
        const detail = source(CAMPAIGN_DETAIL);

        expect(detail).toContain('{snapshotsRestricted ? (');
        expect(detail).toContain('variant="inline"');
        expect(detail.indexOf('snapshotsRestricted ?')).toBeLessThan(
            detail.indexOf('at("noSnapshots")'),
        );
    });

    it('localizes the restriction copy in both supported locales', () => {
        for (const locale of ['en', 'ja'] as const) {
            expect(message(locale, 'CampaignAudience', 'snapshotsDeniedTitle')).toBeTruthy();
            expect(message(locale, 'CampaignAudience', 'snapshotsDeniedBody')).not.toBe(
                message(locale, 'CampaignAudience', 'noSnapshots'),
            );
        }
    });
});

describe('the campaigns nav is gated on the permission it needs', () => {
    it('offers campaigns to a viewer holding CAMPAIGN_VIEW', () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ['CAMPAIGN_VIEW']).campaigns).toBe(true);
    });

    it('hides campaigns from a custom role built without CAMPAIGN_VIEW', () => {
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ['GOAL_READ']).campaigns).toBe(false);
    });

    it('stays visible on an instance that cannot dispatch, since planning still works', () => {
        expect(DEFAULT_CAPABILITIES.campaignDelivery).toBe(false);
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, ['CAMPAIGN_VIEW']).campaigns).toBe(true);
    });

    it('fails closed when permissions could not be resolved', () => {
        expect(NO_NAV_ACCESS.campaigns).toBe(false);
        expect(resolveNavAccess(DEFAULT_CAPABILITIES, []).campaigns).toBe(false);
    });

    it('gates the sidebar section and the palette on the same resolved access', () => {
        expect(source(SIDEBAR)).toContain('...(navAccess.campaigns ? [marketingSection] : [])');
        expect(source(NAV_BRIDGE)).toContain('if (navAccess.campaigns) {');
        expect(source(SEED_ACTIONS)).not.toContain('/marketing/campaigns');
    });
});
