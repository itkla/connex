import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { getCurrentUserFromCookie } from '../lib/api';

export default async function Dashboard() {
    const cookie = (await headers()).get('cookie');
    const user = await getCurrentUserFromCookie(cookie);

    if (!user) {
        redirect('/auth/login');
    }

    return (
        <div className="flex min-h-screen items-center justify-center bg-white px-6">
            <div className="w-full max-w-md">
                <h1 className="text-center leading-tight tracking-tight">
                    <span className="block font-['Instrument_Serif'] text-5xl text-black">
                        Dashboard - {user.displayName}
                    </span>
                </h1>
            </div>
        </div>
    );
}