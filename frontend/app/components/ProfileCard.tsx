import React from 'react';

// 'use client';

interface ProfileCardProps {
    name?: string;
    description?: string;
}

const ProfileCard: React.FC<ProfileCardProps> = ({
    name = 'Profile Name',
    description = 'Profile description goes here',
}) => {
    return (
        <div className="w-[380px] max-w-full overflow-hidden rounded-3xl bg-white shadow-[0_20px_50px_-15px_rgba(0,0,0,0.15)] ring-1 ring-black/5">
            <div className="flex items-center gap-4 px-6 pt-6 pb-4">
                <div className="h-14 w-14 shrink-0 rounded-full bg-neutral-300" />
                <div>
                    <div className="text-xl font-medium text-black">John Pork</div>
                    <div className="text-base text-neutral-500">Voice Co. Ltd.</div>
                </div>
            </div>

            <div className="px-6 pt-4">
                <div className="text-sm text-neutral-500">Your Connections</div>
            </div>

            <div className="relative mx-4 mt-3 mb-4 overflow-hidden rounded-2xl bg-[#73D200]">
                <div
                    className="aspect-[4/5] w-full"
                    style={{
                        background:
                            'linear-gradient(180deg, #cdd5dc 0%, #b6bfc6 60%, #9aa4ad 100%)',
                    }}
                    aria-hidden="true"
                />
                <div className="flex items-end justify-between bg-[#73D200] px-5 pt-4 pb-5 text-white">
                    <div>
                        <div className="text-lg font-semibold">Tahm Kench</div>
                        <div className="text-base opacity-90">LOL Inc.</div>
                    </div>
                    <div className="text-base font-medium opacity-90">CTO</div>
                </div>
            </div>
        </div>
    );
};

export default ProfileCard;