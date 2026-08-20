import { getTranslations } from 'next-intl/server';

import { Badge } from '@/components/ui/badge';
import type { ContactChannelMarketingState } from '@/app/lib/types';

const LABEL_KEY: Record<ContactChannelMarketingState, string> = {
    opted_out: 'marketingOptedOut',
    do_not_contact: 'marketingDoNotContact',
};

/**
 * States that a contact is excluded from marketing on the channel this address belongs to, so a
 * member sees it before writing to them. Renders nothing when the channel is contactable.
 *
 * Deliberately never speaks for a privacy hold: that restriction belongs to the record rather than
 * to a channel, and the record states it in its own words beside the contact's name.
 *
 * @param state - the channel's exclusion state, or null when the channel is contactable
 */
export default async function MarketingExclusionBadge({
    state,
}: {
    state: ContactChannelMarketingState | null;
}) {
    if (state === null) return null;
    const t = await getTranslations('ContactsPage');
    return (
        <Badge variant={state === 'do_not_contact' ? 'destructive' : 'secondary'}>
            {t(LABEL_KEY[state])}
        </Badge>
    );
}
