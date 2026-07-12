import React from 'react';
import { getTranslations } from 'next-intl/server';

// 'use client';

interface ProfileCardProps {
    name?: string;
    description?: string;
}

const ProfileCard = async ({
    name,
    description,
}: ProfileCardProps) => {
    const t = await getTranslations('CommonProfileCard');
    const displayName = name ?? t('defaultName');
    // description default kept for API parity; not currently rendered.
    void (description ?? t('defaultDescription'));
    return (
        <div className="w-[380px] max-w-full overflow-hidden rounded-3xl bg-card shadow-lg ring-1 ring-border">
            <div className="flex items-center gap-4 px-6 pt-6 pb-4">
                <div className="h-14 w-14 shrink-0 rounded-full bg-muted" />
                <div>
                    <div className="text-xl font-medium text-foreground">{displayName}</div>
                    <div className="text-base text-muted-foreground">{t('companyName')}</div>
                </div>
            </div>

            <div className="px-6 pt-4">
                <div className="text-sm text-muted-foreground">{t('yourConnections')}</div>
            </div>

            <div className="relative mx-4 mt-3 mb-4 overflow-hidden rounded-2xl bg-brand">
                <div
                    className="aspect-[4/5] w-full bg-muted"
                    aria-hidden="true"
                />
                <div className="flex items-end justify-between bg-brand px-5 pt-4 pb-5 text-brand-foreground">
                    <div>
                        <div className="text-lg font-semibold">{t('sampleName')}</div>
                        <div className="text-base opacity-90">{t('sampleCompany')}</div>
                    </div>
                    <div className="text-base font-medium opacity-90">{t('sampleRole')}</div>
                </div>
            </div>
        </div>
    );
};

export default ProfileCard;
