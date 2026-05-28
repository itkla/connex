'use client';

import { Handle, Position, type NodeProps } from '@xyflow/react';
import { memo } from 'react';
import { BuildingOffice2Icon } from '@heroicons/react/24/solid';
import type { UCNode as UCNodeType } from './graph/types';

function UCNodeImpl({ data }: NodeProps<UCNodeType>) {
    return (
        <div className="relative flex flex-col items-center">
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
            <div className="flex h-24 w-24 items-center justify-center rounded-3xl bg-brand text-white shadow-lg ring-4 ring-brand/20">
                <BuildingOffice2Icon className="size-12" />
            </div>
            <span className="pointer-events-none absolute left-1/2 top-full mt-1.5 -translate-x-1/2 max-w-[12rem] truncate text-center text-sm font-semibold text-neutral-800">
                {data.label}
            </span>
        </div>
    );
}

export default memo(UCNodeImpl);