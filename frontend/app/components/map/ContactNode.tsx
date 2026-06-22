'use client';

import { Handle, Position, useReactFlow, type NodeProps } from '@xyflow/react';
import { memo } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { ChevronRightIcon, EnvelopeIcon, PhoneIcon } from '@heroicons/react/24/outline';
import { cn } from '@/lib/utils';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import type { ContactNode as ContactNodeType } from './graph/types';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import Link from 'next/link';
import { buttonVariants } from '@/components/ui/button';
import NodeDot from '@/app/components/map/NodeDot';
import { useDotEnabled } from '@/app/hooks/useNodeTier';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

function ContactNodeImpl({ id, data }: NodeProps<ContactNodeType>) {
    const { updateNodeData } = useReactFlow();
    const { contact, hasActivity, expanded, hovered } = data;
    const dotEnabled = useDotEnabled();
    const reduceMotion = useReducedMotion();
    const toggle = () => updateNodeData(id, { expanded: !expanded });

    // TODO: fix the misalignment bug where the image isn't perfectly centered in the ring
    const ring = cn(
        'flex items-center justify-center rounded-full border-2 p-0.5 transition-transform hover:scale-110',
        hasActivity ? 'border-solid border-emerald-500 dark:border-emerald-400' : 'border-dashed border-border',
    );

    if (dotEnabled && !expanded && !hovered) {
        return (
            <NodeDot
                shape="circle"
                className={hasActivity ? 'bg-emerald-500 dark:bg-emerald-400' : 'bg-muted-foreground/50'}
                title={contact.name}
                onClick={toggle}
            />
        );
    }

    if (expanded) {
        return (
            <motion.div
                initial={reduceMotion ? { opacity: 0 } : { opacity: 0, scale: 0.96, y: 2 }}
                animate={reduceMotion ? { opacity: 1 } : { opacity: 1, scale: 1, y: 0 }}
                transition={{ duration: reduceMotion ? 0.12 : 0.18, ease: EASE_OUT }}
                className="relative z-10 w-64 rounded-2xl bg-card p-4 ring-1 ring-border shadow-[0_10px_30px_-12px_rgb(0_0_0/0.20)] dark:shadow-[0_18px_45px_-18px_rgb(0_0_0/0.65)]"
            >
                <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
                <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
                <div className="flex items-center gap-2">
                    <button type="button" onClick={toggle} className="flex min-w-0 flex-1 items-center gap-3 text-left">
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
                    <Tooltip>
                        <TooltipTrigger asChild>
                            <Link
                                href={`/records/contacts/${contact.id}`}
                                aria-label="Open contact record"
                                className={cn(
                                    buttonVariants({ variant: 'ghost', size: 'icon-sm' }),
                                    'nodrag group/open shrink-0 rounded-full bg-muted text-muted-foreground shadow-none hover:bg-muted/80 hover:text-foreground',
                                )}
                            >
                                <ChevronRightIcon className="size-4 transition-transform duration-150 ease-out group-hover/open:translate-x-0.5" />
                            </Link>
                        </TooltipTrigger>
                        <TooltipContent side="left" align="center">
                            View contact
                        </TooltipContent>
                    </Tooltip>
                </div>
                {(contact.email || contact.phone) ? (
                    <div className="mt-3 space-y-0.5 border-t border-border pt-3 text-xs text-muted-foreground">
                        {contact.email ? (
                            <a
                                href={`mailto:${contact.email}`}
                                className="nodrag group/row -mx-1.5 flex items-center gap-2 rounded-md px-1.5 py-1 transition-colors hover:bg-muted hover:text-foreground"
                            >
                                <EnvelopeIcon className="size-3.5 shrink-0 text-muted-foreground transition-colors group-hover/row:text-brand-dark" />
                                <span className="truncate">{contact.email}</span>
                            </a>
                        ) : null}
                        {contact.phone ? (
                            <a
                                href={`tel:${contact.phone}`}
                                className="nodrag group/row -mx-1.5 flex items-center gap-2 rounded-md px-1.5 py-1 transition-colors hover:bg-muted hover:text-foreground"
                            >
                                <PhoneIcon className="size-3.5 shrink-0 text-muted-foreground transition-colors group-hover/row:text-brand-dark" />
                                <span className="truncate tabular-nums">{contact.phone}</span>
                            </a>
                        ) : null}
                    </div>
                ) : null}
            </motion.div>
        );
    }

    return (
        <div className="map-node-bloom relative flex flex-col items-center">
            <Handle type="target" position={Position.Top} isConnectable={false} className="!opacity-0" />
            <Handle type="source" position={Position.Bottom} isConnectable={false} className="!opacity-0" />
            <button type="button" onClick={toggle} className={ring} title={contact.name}>
                <ContactAvatar contact={contact} type="medium" />
            </button>
            <span className="map-node-label pointer-events-none absolute left-1/2 top-full mt-1 -translate-x-1/2 max-w-[8rem] truncate text-center text-[11px] font-medium text-foreground">
                {contact.name}
            </span>
        </div>
    );
}

export default memo(ContactNodeImpl);