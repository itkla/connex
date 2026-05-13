import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import Image from 'next/image';
import Link from 'next/link';
import { ArrowLeftIcon } from '@heroicons/react/24/outline';
import { Skeleton } from '@/components/ui/skeleton'; // TODO: add skeleton loaders to render before the PRomises resolve

import {
    getCurrentUserFromCookie,
    getUserActivitiesFromCookie,
    getUserNotesFromCookie,
    getUserTasksFromCookie,
} from '@/app/lib/api';
import { formatDate, formatDateTime } from '@/app/lib/utils';

import InfoRow from '@/app/components/me/InfoRow';
import StatCard from '@/app/components/me/StatCard';
import Timeline from '@/app/components/me/Timeline';
import EditSelfModal from '@/app/components/me/EditSelfModal';

export default async function MePage() {
    // TODO: move this block to a separate component OR put it into a lib/hook
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    const [tasks, activities, notes] = await Promise.all([
        getUserTasksFromCookie(user.id, cookie),
        getUserActivitiesFromCookie(user.id, cookie),
        getUserNotesFromCookie(user.id, cookie),
    ]);

    const initials = user.displayName?.slice(0, 1).toUpperCase() ?? '?';
    const openTasks = tasks.filter((t) => !t.completed).length;

    const greetings = [
        "Hello,",
        "Hi,",
        "Hey,",
        "Good day,",
        "Good morning,",
        "Good afternoon,",
        "Good evening,",
        "Howdy,",
        "What's up,",
    ];
    const randomGreeting = greetings[Math.floor(Math.random() * greetings.length)];

    return (
        <div className="min-h-screen bg-white px-6 pt-20 pb-12 md:flex md:h-screen md:flex-col md:overflow-hidden">
            <div className="mx-auto w-full max-w-5xl md:flex md:min-h-0 md:flex-1 md:flex-col">
                <header className="flex items-center gap-6">
                    {user.profilePictureUrl ? (
                        <Image
                            src={user.profilePictureUrl}
                            alt={`${user.displayName}'s profile picture`}
                            width={96}
                            height={96}
                            className="h-24 w-24 shrink-0 rounded-full object-cover shadow-[0_20px_50px_-15px_rgba(0,0,0,0.15)] ring-1 ring-black/5"
                        />
                    ) : (
                        <div
                            aria-label="Profile picture placeholder"
                            className="flex h-24 w-24 shrink-0 items-center justify-center rounded-full bg-brand-light text-4xl text-brand-dark ring-1 ring-black/5"
                        >
                            {initials}
                        </div>
                    )}
                    <h1 className="leading-tight tracking-tight">
                        <span className="block text-2xl font-medium text-neutral-500">
                            {randomGreeting}
                        </span>
                        <span className="mt-1 block text-4xl font-extrabold tracking-tight text-black">
                            {user.displayName}
                        </span>
                    </h1>
                </header>

                <div className="mt-12 grid grid-cols-1 gap-8 md:grid-cols-[minmax(0,1fr)_minmax(0,2fr)] md:min-h-0 md:flex-1">
                    <aside>
                        <div className="mb-3 flex h-8 items-center justify-between">
                            <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                                Profile
                            </h2>
                            <EditSelfModal user={user} />
                        </div>
                        <dl className="divide-y divide-neutral-200 overflow-hidden rounded-2xl bg-neutral-100 ring-1 ring-black/5">
                            <InfoRow
                                label="Username"
                                value={`@${user.username}`}
                            />
                            <InfoRow
                                label="Email"
                                value={user.email ?? ''}
                            />
                            <InfoRow
                                label="Last login"
                                value={formatDateTime(user.lastLoginAt)}
                            />
                            <InfoRow
                                label="Member since"
                                value={formatDate(user.createdAt)}
                            />
                        </dl>
                        {/* TODO: find a better place to put this */}
                        <div className="mt-6 px-6">
                            {/* TODO: fix Link attaching to parent div, causing the whole section to be clickable and not just the text + arrow */}
                            <Link
                                href="/dashboard"
                                className="text-base text-brand hover:text-brand-hover"
                            >
                                <span className="flex items-center gap-2">
                                    <ArrowLeftIcon className="h-4 w-4" />
                                    Back to dashboard
                                </span>
                            </Link>
                        </div>
                    </aside>

                    <section className="md:flex md:min-h-0 md:flex-col">
                        <div className="mb-3 flex h-8 items-center">
                            <h2 className="px-6 text-xs font-medium tracking-[0.12em] text-neutral-500 uppercase">
                                My Activity
                            </h2>
                        </div>

                        <div className="grid grid-cols-3 gap-3">
                            <StatCard
                                label="Tasks"
                                value={tasks.length}
                                subtitle={`${openTasks} open`}
                            />
                            <StatCard
                                label="Activities"
                                value={activities.length}
                            />
                            <StatCard label="Notes" value={notes.length} />
                        </div>

                        <div className="mt-6 overflow-hidden rounded-2xl bg-white ring-1 ring-black/5 md:flex md:min-h-0 md:flex-1 md:flex-col">
                            <div className="md:min-h-0 md:flex-1 md:overflow-y-auto md:[-webkit-mask-image:linear-gradient(to_bottom,transparent_0,black_24px)] md:[mask-image:linear-gradient(to_bottom,transparent_0,black_24px)]">
                                <Timeline
                                    tasks={tasks}
                                    activities={activities}
                                    notes={notes}
                                />
                            </div>
                        </div>
                    </section>
                </div>
            </div>
        </div>
    );
}
