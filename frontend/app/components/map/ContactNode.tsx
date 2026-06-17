'use client';

import { Handle, Position, useReactFlow, type NodeProps } from '@xyflow/react';
import { memo } from 'react';
import { ChevronRightIcon, EnvelopeIcon, PhoneIcon } from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import type { ContactNode as ContactNodeType } from './graph/types';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import Link from 'next/link';
import { Button } from '@/components/ui/button';
import NodeDot from '@/app/components/map/NodeDot';
import { useIsDotTier } from '@/app/hooks/useNodeTier';

function ContactNodeImpl({ id, data }: NodeProps<ContactNodeType>) {
    const { updateNodeData } = useReactFlow();
    const { contact, hasActivity, expanded, revealed } = data;
    const isDot = useIsDotTier();
    const toggle = () => updateNodeData(id, { expanded: !expanded });

    // TODO: fix the misalignment bug where the image isn't perfectly centered in the ring
    const ring = cn(
        'flex items-center justify-center rounded-full border-2 p-0.5 transition-transform hover:scale-110',
        hasActivity ? 'border-solid border-emerald-500 dark:border-emerald-400' : 'border-dashed border-border',
    );

    if (isDot && !expanded && !revealed) {
        return (
            <NodeDot
                shape="circle"
                className={hasActivity ? 'bg-emerald-500 dark:bg-emerald-400' : 'bg-muted-foreground/50'}
                title={contact.name}
                onClick={() => updateNodeData(id, { revealed: true })}
            />
        );
    }

    if (expanded) {

        return (
            <div className="relative w-56 rounded-xl border border-border bg-card p-4 shadow-xl">
                <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
                <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
                <div className="flex items-center gap-3 justify-between">
                    <button type="button" onClick={toggle} className="flex w-full items-center gap-3 text-left">
                        <span className={ring}>
                            <ContactAvatar contact={contact} type="small" />
                        </span>
                        <div className="min-w-0">
                            <p className="truncate text-sm font-semibold text-foreground">{contact.name}</p>
                            {contact.title ? (
                                <p className="truncate text-xs text-muted-foreground">{contact.title}</p>
                            ) : null}
                        </div>
                    </button>
                    <div className="flex items-center">
                        <Tooltip>
                            <TooltipTrigger asChild>
                                <Link href={`/records/contacts/${contact.id}`} className="nodrag shrink-0 flex items-center">
                                    <Button variant="outline" size="icon-lg" aria-label="Open company record" className="flex items-center justify-center bg-muted shadow-none hover:bg-muted/80">
                                        {/* <ArrowUpRightIcon className="size-3.5 text-neutral-500" /> */}
                                        <ChevronRightIcon className="size-3.5 text-muted-foreground" />
                                    </Button>
                                </Link>
                            </TooltipTrigger>
                            <TooltipContent side="left" align="center">
                                View Contact
                            </TooltipContent>
                        </Tooltip>

                    </div>
                </div>
                <div className="mt-3 space-y-1.5 text-xs text-muted-foreground">
                    {contact.email ? (
                        <p className="flex items-center gap-1.5">
                            <EnvelopeIcon className="size-3.5 shrink-0 text-muted-foreground" />
                            <span className="truncate">{contact.email}</span>
                        </p>
                    ) : null}
                    {contact.phone ? (
                        <p className="flex items-center gap-1.5">
                            <PhoneIcon className="size-3.5 shrink-0 text-muted-foreground" />
                            <span className="truncate">{contact.phone}</span>
                        </p>
                    ) : null}
                </div>
                <div className="flex relative">
                    {/* <AvatarGroup>
                        {interactionUsers.map((user) => (
                            <Tooltip key={user.id}>
                                <TooltipTrigger asChild>
                                    <Avatar key={user.id}>
                                <AvatarImage src={user.profilePictureUrl} />
                                        <AvatarFallback>{user.displayName.charAt(0)}</AvatarFallback>
                                    </Avatar>
                                </TooltipTrigger>
                                <TooltipContent side="bottom" align="center">
                                    {user.displayName}
                                </TooltipContent>
                            </Tooltip>
                        ))}
                    </AvatarGroup> */}
                </div>
            </div>
        );
    }

    return (
        <div className="relative flex flex-col items-center">
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
            <button type="button" onClick={toggle} className={ring} title={contact.name}>
                <ContactAvatar contact={contact} type="medium" />
            </button>
            <span className="pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 max-w-[8rem] truncate text-center text-[11px] font-medium text-foreground">
                {contact.name}
            </span>
        </div>
    );
}

export default memo(ContactNodeImpl);