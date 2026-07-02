import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import Link from 'next/link';
import { ArrowLeftIcon } from '@heroicons/react/24/outline';
import { getLocale, getTranslations } from 'next-intl/server';

import {
    getAttachmentsFromCookie,
    getContacts,
    getCurrentUserFromCookie,
    getDeals,
    getUserActivitiesFromCookie,
    getUserNotesFromCookie,
    getUserTasksFromCookie,
    getUsers,
} from '@/app/lib/api';
import type { Contact, Deal, User } from '@/app/lib/types';
import { formatDate, formatDateTime } from '@/app/lib/utils';

import InfoRow from '@/app/components/me/InfoRow';
import StatCard from '@/app/components/me/StatCard';
import Timeline from '@/app/components/me/Timeline';
import EditSelfModal from '@/app/components/me/EditSelfModal';
import UserAvatar from '@/app/components/records/users/UserAvatar';
import Attachments from '@/app/components/attachments/Attachments';
import Rise from '@/app/components/motion/Rise';
import SectionHeader from '@/app/components/dashboard/SectionHeader';

export default async function MePage() {
    const t = await getTranslations('MePage');
    const locale = await getLocale();
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const init = { headers: { cookie: cookie ?? '' } } as const;
    const [tasks, activities, notes, users, persons, deals, attachments] = await Promise.all([
        getUserTasksFromCookie(user.id, cookie),
        getUserActivitiesFromCookie(user.id, cookie),
        getUserNotesFromCookie(user.id, cookie),
        getUsers(init).catch(() => [] as User[]),
        getContacts({}, init).catch(() => [] as Contact[]),
        getDeals(init).catch(() => [] as Deal[]),
        getAttachmentsFromCookie('user', user.id, cookie),
    ]);

    const initials = user.displayName?.slice(0, 1).toUpperCase() ?? '?';
    const openTasks = tasks.filter((t) => !t.completed).length;

    const greetings = [
        t('greetingHello'),
        t('greetingHi'),
        t('greetingHey'),
        t('greetingGoodDay'),
        t('greetingGoodMorning'),
        t('greetingGoodAfternoon'),
        t('greetingGoodEvening'),
        t('greetingHowdy'),
        t('greetingWhatsUp'),
    ];
    const greeting = greetings[user.id % greetings.length];

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-5xl flex-col gap-10">
                <Rise>
                    <header className="flex items-center gap-6">
                        {user.profilePictureUrl ? (
                            <UserAvatar user={user} type="2xlarge" />
                        ) : (
                            <div
                                aria-label={t('profilePicturePlaceholderAriaLabel')}
                                className="flex h-24 w-24 shrink-0 items-center justify-center rounded-full bg-brand-light text-4xl text-brand-dark ring-1 ring-border"
                            >
                                {initials}
                            </div>
                        )}
                        <h1 className="leading-tight tracking-tight">
                            <span className="block text-2xl font-medium text-muted-foreground">
                                {greeting}
                            </span>
                            <span className="mt-1 block text-4xl font-extrabold tracking-tight text-foreground">
                                {user.displayName}
                            </span>
                        </h1>
                    </header>
                </Rise>

                <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
                    <Rise delay={0.06}>
                        <aside>
                            <SectionHeader
                                title={t('profile')}
                                action={<EditSelfModal user={user} />}
                            />
                            <dl className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">
                                <InfoRow
                                    label={t('username')}
                                    value={`@${user.username}`}
                                />
                                <InfoRow
                                    label={t('email')}
                                    value={user.email ?? ''}
                                />
                                <InfoRow
                                    label={t('lastLogin')}
                                    value={formatDateTime(user.lastLoginAt, locale)}
                                />
                                <InfoRow
                                    label={t('memberSince')}
                                    value={formatDate(user.createdAt, locale)}
                                />
                            </dl>

                            <Attachments
                                entityType="user"
                                entityId={user.id}
                                initialAttachments={attachments}
                                className="mt-6"
                            />
                            <div className="mt-6 px-6">
                                <Link
                                    href="/dashboard"
                                    className="text-base text-brand hover:text-brand-hover"
                                >
                                    <span className="flex items-center gap-2">
                                        <ArrowLeftIcon className="h-4 w-4" />
                                        {t('backToDashboard')}
                                    </span>
                                </Link>
                            </div>
                        </aside>
                    </Rise>

                    <Rise delay={0.12}>
                        <section>
                            <SectionHeader title={t('myActivity')} />

                            <div className="grid grid-cols-3 gap-3">
                                <StatCard
                                    label={t('tasks')}
                                    value={tasks.length}
                                    subtitle={t('openCount', { count: openTasks })}
                                />
                                <StatCard
                                    label={t('activities')}
                                    value={activities.length}
                                />
                                <StatCard label={t('notes')} value={notes.length} />
                            </div>

                            <div className="mt-6 overflow-hidden rounded-2xl border border-border bg-card">
                                <Timeline
                                    tasks={tasks}
                                    activities={activities}
                                    notes={notes}
                                    users={users}
                                    persons={persons}
                                    deals={deals}
                                    currentUserId={user.id}
                                />
                            </div>
                        </section>
                    </Rise>
                </div>
            </div>
        </div>
    );
}
