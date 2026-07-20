'use client';

import { useEffect, useRef, useState, useSyncExternalStore, type ComponentType } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { AnimatePresence, motion, useReducedMotion, type Variants } from 'motion/react';
import {
    Bars3Icon,
    CheckCircleIcon,
    HomeIcon,
    MagnifyingGlassIcon,
    PlusIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import { springJiggle, springSnappy, springSmooth, easeOut, instant } from '@/app/lib/motion';
import { getTaskSummary } from '@/app/lib/api';
import { useIsMobile } from '@/app/hooks/useIsMobile';

const GLASS_FILTER_ID = 'connex-liquid-glass';

/** Base inward displacement (px) applied to the sampled backdrop; edges bend, centre stays put. */
const DISTORTION = -108;
/** Per-channel displacement deltas that produce the chromatic-aberration fringe at the glass edge. */
const GREEN_OFFSET = 6;
const BLUE_OFFSET = 12;
/** Displacement-map authoring params: a blurred neutral centre keeps refraction to the rim. */
const MAP_BRIGHTNESS = 50;
const MAP_OPACITY = 0.92;
const MAP_BLUR = 9;
const MAP_EDGE_RATIO = 0.11;

let svgBackdropSupport: boolean | null = null;

/**
 * Feature-detects Chromium's (non-standard) support for an SVG filter as a `backdrop-filter`, memoized so
 * it is a stable snapshot for {@link useSyncExternalStore}.
 */
function detectSvgBackdrop(): boolean {
    if (svgBackdropSupport !== null) return svgBackdropSupport;
    if (typeof document === 'undefined') return false;
    const ua = navigator.userAgent;
    if ((/Safari/.test(ua) && !/Chrome/.test(ua)) || /Firefox/.test(ua)) {
        svgBackdropSupport = false;
        return false;
    }
    const el = document.createElement('div');
    el.style.backdropFilter = `url(#${GLASS_FILTER_ID})`;
    svgBackdropSupport = el.style.backdropFilter !== '';
    return svgBackdropSupport;
}

/** No-op subscribe: SVG-backdrop support never changes for the lifetime of the document. */
const subscribeNoop = () => () => {};

/**
 * Builds the displacement map as a data-URI SVG: a red horizontal and blue vertical gradient encode X/Y
 * displacement, and a blurred neutral inner rect cancels displacement in the centre so the refraction
 * concentrates at the rounded edge (a lens bevel) rather than warping the whole backdrop.
 */
function buildDisplacementMap(width: number, height: number, radius: number): string {
    const edge = Math.min(width, height) * MAP_EDGE_RATIO;
    const svg = `<svg viewBox="0 0 ${width} ${height}" xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="r" x1="100%" y1="0%" x2="0%" y2="0%"><stop offset="0%" stop-color="#0000"/><stop offset="100%" stop-color="red"/></linearGradient><linearGradient id="b" x1="0%" y1="0%" x2="0%" y2="100%"><stop offset="0%" stop-color="#0000"/><stop offset="100%" stop-color="blue"/></linearGradient></defs><rect width="${width}" height="${height}" fill="black"/><rect width="${width}" height="${height}" rx="${radius}" fill="url(#r)"/><rect width="${width}" height="${height}" rx="${radius}" fill="url(#b)" style="mix-blend-mode:difference"/><rect x="${edge}" y="${edge}" width="${width - edge * 2}" height="${height - edge * 2}" rx="${radius}" fill="hsl(0 0% ${MAP_BRIGHTNESS}% / ${MAP_OPACITY})" style="filter:blur(${MAP_BLUR}px)"/></svg>`;
    return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/** Liquid-glass entrance: the pill rises and settles, then staggers its slots into place. */
const barVariants: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { ...springSmooth, staggerChildren: 0.045, delayChildren: 0.05 } },
};

/** Each slot pops in with a soft overshoot, matching the pill's liquid settle. */
const slotVariants: Variants = {
    hidden: { opacity: 0, y: 10, scale: 0.8 },
    show: { opacity: 1, y: 0, scale: 1, transition: springSnappy },
};

const slotBase =
    'group relative flex flex-1 flex-col items-center justify-center gap-1 rounded-full py-1 text-muted-foreground transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 focus-visible:ring-offset-background';

const indicatorWrap = 'relative z-10 flex h-7 w-12 items-center justify-center';

const labelBase = 'relative z-10 max-w-full truncate text-[10px] leading-none font-medium';

/** Dispatches the app-shell custom event that the always-mounted GlobalSearch listens for. */
function openSearch() {
    window.dispatchEvent(new CustomEvent('connex:open-search'));
}

/** Dispatches the app-shell custom event that the always-mounted QuickCreateLauncher listens for. */
function openQuickCreate() {
    window.dispatchEvent(new CustomEvent('connex:open-quick-create'));
}

/**
 * The active destination's liquid-glass lens: a brand-tinted capsule that fills the whole slot (icon and
 * label) and springs in behind them, blurring out on exit, so navigating reads as the light re-settling
 * rather than a hard swap.
 */
function ActiveLens({ reduce }: { reduce: boolean }) {
    return (
        <motion.span
            aria-hidden
            initial={reduce ? false : { opacity: 0, scale: 0.85, filter: 'blur(4px)' }}
            animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
            exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.9, filter: 'blur(4px)' }}
            transition={reduce ? instant : { default: springJiggle, filter: { duration: 0.2, ease: easeOut } }}
            className="absolute inset-0 rounded-full bg-brand/20 shadow-[inset_0_0.5px_0_rgb(255_255_255/0.35)]"
        />
    );
}

function BarLink({
    href,
    label,
    caption,
    Icon,
    active,
    reduce,
    badge,
    badgeLabel,
}: {
    href: string;
    label: string;
    caption: string;
    Icon: ComponentType<{ className?: string }>;
    active: boolean;
    reduce: boolean;
    badge?: number;
    badgeLabel?: string;
}) {
    return (
        <motion.div
            className="flex flex-1"
            variants={reduce ? undefined : slotVariants}
            whileTap={reduce ? undefined : { scale: 0.9 }}
            transition={reduce ? instant : springSnappy}
        >
            <Link
                href={href}
                aria-label={label}
                aria-current={active ? 'page' : undefined}
                className={cn(slotBase, active ? 'text-brand-dark dark:text-brand' : 'hover:text-foreground')}
            >
                <AnimatePresence>{active && <ActiveLens key="lens" reduce={reduce} />}</AnimatePresence>
                <span className={indicatorWrap}>
                    <Icon className="relative z-10 size-6" />
                    {badge != null && badge > 0 && (
                        <span
                            aria-hidden
                            className="absolute z-10 -top-1 right-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-none font-semibold text-white ring-2 ring-background"
                        >
                            {badge > 99 ? '99+' : badge}
                        </span>
                    )}
                </span>
                <span className={labelBase}>{caption}</span>
                {badge != null && badge > 0 && badgeLabel && <span className="sr-only">{badgeLabel}</span>}
            </Link>
        </motion.div>
    );
}

function BarButton({
    label,
    caption,
    Icon,
    onClick,
    reduce,
}: {
    label: string;
    caption: string;
    Icon: ComponentType<{ className?: string }>;
    onClick: () => void;
    reduce: boolean;
}) {
    return (
        <motion.button
            type="button"
            onClick={onClick}
            aria-label={label}
            variants={reduce ? undefined : slotVariants}
            whileTap={reduce ? undefined : { scale: 0.9 }}
            transition={reduce ? instant : springSnappy}
            className={cn(slotBase, 'hover:text-foreground')}
        >
            <span className={indicatorWrap}>
                <Icon className="relative z-10 size-6" />
            </span>
            <span className={labelBase}>{caption}</span>
        </motion.button>
    );
}

/**
 * Mobile-only floating action bar spanning the viewport width (inset by the shell padding): Home ·
 * Search · New · Tasks · More. Hidden at the `md` breakpoint where the desktop sidebar takes over. The
 * surface is a real Liquid-Glass approximation (per Apple's iOS 26 material): a nearly-transparent pane
 * whose backdrop is refracted at the rounded edge via an SVG displacement map, with three-channel
 * chromatic aberration, rather than a frosted/glossy panel. Chromium renders the refraction; Safari/iOS
 * and Firefox degrade to a plain frosted blur. All motion collapses under reduced-motion.
 *
 * @param onOpenMore - opens the full sidebar drawer (workspace/account/all destinations)
 */
export default function MobileBottomBar({ onOpenMore }: { onOpenMore: () => void }) {
    const pathname = usePathname();
    const reduce = useReducedMotion() ?? false;
    const isMobile = useIsMobile();
    const tNav = useTranslations('CommonSidebar');
    const tActions = useTranslations('Actions');
    const t = useTranslations('MobileNav');

    const barRef = useRef<HTMLDivElement>(null);
    const [displacementMap, setDisplacementMap] = useState('');
    const refract = useSyncExternalStore(subscribeNoop, detectSvgBackdrop, () => false);

    useEffect(() => {
        const el = barRef.current;
        if (!el) return;
        const update = () => {
            const rect = el.getBoundingClientRect();
            if (rect.width === 0 || rect.height === 0) return;
            setDisplacementMap(buildDisplacementMap(rect.width, rect.height, rect.height / 2));
        };
        update();
        const ro = new ResizeObserver(update);
        ro.observe(el);
        return () => ro.disconnect();
    }, []);

    const [attention, setAttention] = useState(0);
    useEffect(() => {
        if (!isMobile) return;
        let active = true;
        getTaskSummary()
            .then((summary) => {
                if (active) setAttention(summary.overdue + summary.dueSoon);
            })
            .catch(() => {});
        return () => {
            active = false;
        };
    }, [pathname, isMobile]);

    const onDashboard = pathname === '/dashboard';
    const onTasks = pathname === '/activity/tasks' || pathname.startsWith('/activity/tasks/');

    const glassStyle = refract
        ? {
              backdropFilter: `url(#${GLASS_FILTER_ID}) blur(2px) saturate(1.5) brightness(1.03)`,
              WebkitBackdropFilter: `blur(12px) saturate(1.6)`,
          }
        : {
              backdropFilter: `blur(14px) saturate(1.6)`,
              WebkitBackdropFilter: `blur(14px) saturate(1.6)`,
          };

    return (
        <nav
            aria-label={t('barLabel')}
            className="pointer-events-none fixed inset-x-0 bottom-[calc(env(safe-area-inset-bottom)+0.75rem)] z-30 px-4 md:hidden"
        >
            <motion.div
                ref={barRef}
                initial={reduce ? false : 'hidden'}
                animate={reduce ? undefined : 'show'}
                variants={reduce ? undefined : barVariants}
                style={glassStyle}
                className="pointer-events-auto relative flex w-full items-stretch gap-0.5 rounded-full bg-background/18 px-1.5 py-1.5 shadow-[inset_0_0.5px_0_rgb(255_255_255/0.4),inset_0_0_8px_rgb(255_255_255/0.06),0_8px_28px_-8px_rgb(0_0_0/0.4),0_1px_2px_rgb(0_0_0/0.12)] ring-1 ring-white/10 ring-inset"
            >
                <svg aria-hidden className="pointer-events-none absolute h-0 w-0">
                    <filter
                        id={GLASS_FILTER_ID}
                        colorInterpolationFilters="sRGB"
                        x="0%"
                        y="0%"
                        width="100%"
                        height="100%"
                    >
                        <feImage
                            href={displacementMap || undefined}
                            x="0"
                            y="0"
                            width="100%"
                            height="100%"
                            preserveAspectRatio="none"
                            result="map"
                        />
                        <feDisplacementMap
                            in="SourceGraphic"
                            in2="map"
                            scale={DISTORTION}
                            xChannelSelector="R"
                            yChannelSelector="G"
                            result="dispR"
                        />
                        <feColorMatrix
                            in="dispR"
                            type="matrix"
                            values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0"
                            result="red"
                        />
                        <feDisplacementMap
                            in="SourceGraphic"
                            in2="map"
                            scale={DISTORTION + GREEN_OFFSET}
                            xChannelSelector="R"
                            yChannelSelector="G"
                            result="dispG"
                        />
                        <feColorMatrix
                            in="dispG"
                            type="matrix"
                            values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0"
                            result="green"
                        />
                        <feDisplacementMap
                            in="SourceGraphic"
                            in2="map"
                            scale={DISTORTION + BLUE_OFFSET}
                            xChannelSelector="R"
                            yChannelSelector="G"
                            result="dispB"
                        />
                        <feColorMatrix
                            in="dispB"
                            type="matrix"
                            values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0"
                            result="blue"
                        />
                        <feBlend in="red" in2="green" mode="screen" result="rg" />
                        <feBlend in="rg" in2="blue" mode="screen" result="blended" />
                        <feGaussianBlur in="blended" stdDeviation="0.4" />
                    </filter>
                </svg>

                <BarLink
                    href="/dashboard"
                    label={tNav('navDashboard')}
                    caption={tNav('navDashboard')}
                    Icon={HomeIcon}
                    active={onDashboard}
                    reduce={reduce}
                />
                <BarButton
                    label={tActions('palette.trigger')}
                    caption={t('search')}
                    Icon={MagnifyingGlassIcon}
                    onClick={openSearch}
                    reduce={reduce}
                />

                <motion.button
                    type="button"
                    onClick={openQuickCreate}
                    variants={reduce ? undefined : slotVariants}
                    whileTap={reduce ? undefined : { scale: 0.9 }}
                    transition={reduce ? instant : springJiggle}
                    aria-label={tActions('quickCreate.trigger')}
                    className="group flex flex-1 items-center justify-center rounded-2xl focus-visible:outline-none"
                >
                    <span className="flex size-11 items-center justify-center rounded-full bg-brand text-brand-foreground shadow-[inset_0_0.5px_0_rgb(255_255_255/0.4),0_2px_6px_-2px_rgb(0_0_0/0.35)] transition-colors group-hover:bg-brand-hover group-active:bg-brand-hover group-focus-visible:ring-2 group-focus-visible:ring-brand group-focus-visible:ring-offset-2 group-focus-visible:ring-offset-background">
                        <PlusIcon className="size-6" />
                    </span>
                </motion.button>

                <BarLink
                    href="/activity/tasks"
                    label={tNav('navTasks')}
                    caption={tNav('navTasks')}
                    Icon={CheckCircleIcon}
                    active={onTasks}
                    reduce={reduce}
                    badge={attention}
                    badgeLabel={t('tasksBadge', { count: attention })}
                />
                <BarButton
                    label={t('more')}
                    caption={t('more')}
                    Icon={Bars3Icon}
                    onClick={onOpenMore}
                    reduce={reduce}
                />
            </motion.div>
        </nav>
    );
}
