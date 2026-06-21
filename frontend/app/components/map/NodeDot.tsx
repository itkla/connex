'use client';

import { Handle, Position } from '@xyflow/react';
import { cn } from '@/lib/utils';

export default function NodeDot({
    shape,
    className,
    title,
    onClick,
}: {
    shape: 'circle' | 'square';
    className?: string;
    title: string;
    onClick?: () => void;
}) {
    return (
        <div className="relative flex items-center justify-center">
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
            <button
                type="button"
                onClick={onClick}
                title={title}
                className={cn(
                    'size-3 ring-1 ring-border shadow-sm transition-transform hover:scale-150',
                    shape === 'circle' ? 'rounded-full' : 'rounded-[3px]',
                    className,
                )}
            />
            <span className="map-node-label pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 max-w-[7rem] truncate text-center text-[10px] font-medium text-foreground">
                {title}
            </span>
        </div>
    );
}
