'use client';

import { Handle, Position, type NodeProps } from '@xyflow/react';
import { memo } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import type { UserNode as UserNodeType } from './graph/types';

function UserNodeImpl({ data }: NodeProps<UserNodeType>) {
    const { user } = data;
    const name = user.displayName || user.username;
    return (
        <div className="relative flex flex-col items-center">
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
            <Avatar className="h-14 w-14 ring-2 ring-brand/30 transition-transform hover:scale-110">
                <AvatarImage src={user.profilePictureUrl} alt={name} />
                <AvatarFallback className="bg-brand/10 text-sm font-semibold text-brand">
                    {name.charAt(0).toUpperCase()}
                </AvatarFallback>
            </Avatar>
            <span className="pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 max-w-[8rem] truncate text-center text-[11px] font-medium text-foreground">
                {name}
            </span>
        </div>
    );
}

export default memo(UserNodeImpl);