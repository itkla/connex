"use client";

import * as React from "react";
import { motion, useReducedMotion } from "motion/react";
import { useTheme } from "next-themes";
import {
  ArrowDownTrayIcon,
  ArrowUpTrayIcon,
  Bars2Icon,
  Bars3Icon,
  EllipsisVerticalIcon,
  MoonIcon,
  PlusIcon,
  SunIcon,
  Squares2X2Icon,
  TableCellsIcon,
} from "@heroicons/react/24/outline";

import { cn } from "@/lib/utils";
import { PageShell } from "@/app/components/PageShell";
import { useShortcutPlatform } from "@/app/hooks/useShortcutPlatform";
import { formatShortcut } from "@/app/lib/actions/shortcut";
import {
  instant,
  springJiggle,
  springSmooth,
  springSnappy,
} from "@/app/lib/motion";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { SplitButton } from "@/components/ui/split-button";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerHeader,
  DrawerTitle,
  DrawerTrigger,
} from "@/components/ui/drawer";
import {
  ResponsiveDialog,
  ResponsiveDialogClose,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
  ResponsiveDialogTrigger,
} from "@/components/ui/responsive-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command";

type Token = { name: string; cssVar: string };
type ColorGroup = { title: string; note: string; tokens: Token[] };

const COLOR_GROUPS: ColorGroup[] = [
  {
    title: "Semantic",
    note: "Surface, ink, and state — the neutral spine of every screen.",
    tokens: [
      { name: "background", cssVar: "--background" },
      { name: "foreground", cssVar: "--foreground" },
      { name: "card", cssVar: "--card" },
      { name: "popover", cssVar: "--popover" },
      { name: "primary", cssVar: "--primary" },
      { name: "primary-foreground", cssVar: "--primary-foreground" },
      { name: "secondary", cssVar: "--secondary" },
      { name: "muted", cssVar: "--muted" },
      { name: "muted-foreground", cssVar: "--muted-foreground" },
      { name: "accent", cssVar: "--accent" },
      { name: "destructive", cssVar: "--destructive" },
      { name: "destructive-foreground", cssVar: "--destructive-foreground" },
      { name: "border", cssVar: "--border" },
      { name: "input", cssVar: "--input" },
      { name: "ring", cssVar: "--ring" },
    ],
  },
  {
    title: "Brand",
    note: "The Connex green. Reserved for primary actions and selection — never decoration.",
    tokens: [
      { name: "brand", cssVar: "--color-brand" },
      { name: "brand-hover", cssVar: "--color-brand-hover" },
      { name: "brand-dark", cssVar: "--color-brand-dark" },
      { name: "brand-light", cssVar: "--color-brand-light" },
    ],
  },
  {
    title: "Warmth",
    note: "Relationship temperature. Use for warmth scores, not generic status.",
    tokens: [
      { name: "warmth-hot", cssVar: "--warmth-hot" },
      { name: "warmth-warm", cssVar: "--warmth-warm" },
      { name: "warmth-cool", cssVar: "--warmth-cool" },
      { name: "warmth-cold", cssVar: "--warmth-cold" },
    ],
  },
  {
    title: "Chart",
    note: "Data-viz series and deal outcomes. Pair with recharts/d3 instead of raw colors.",
    tokens: [
      { name: "chart-1", cssVar: "--chart-1" },
      { name: "chart-2", cssVar: "--chart-2" },
      { name: "chart-3", cssVar: "--chart-3" },
      { name: "chart-4", cssVar: "--chart-4" },
      { name: "chart-5", cssVar: "--chart-5" },
      { name: "chart-won", cssVar: "--chart-won" },
      { name: "chart-lost", cssVar: "--chart-lost" },
      { name: "chart-open", cssVar: "--chart-open" },
    ],
  },
  {
    title: "Risk",
    note: "Deal-risk severity for the AI risk rationale surfaces.",
    tokens: [
      { name: "risk-high", cssVar: "--risk-high" },
      { name: "risk-medium", cssVar: "--risk-medium" },
      { name: "risk-low", cssVar: "--risk-low" },
    ],
  },
];

const RADII: { label: string; className: string; use: string }[] = [
  { label: "rounded-lg", className: "rounded-lg", use: "Inputs & controls" },
  { label: "rounded-xl", className: "rounded-xl", use: "Dialogs" },
  { label: "rounded-2xl", className: "rounded-2xl", use: "Cards & drawers" },
  { label: "rounded-full", className: "rounded-full", use: "Buttons & pills" },
];

const WIDTH_RULES: { name: string; className: string; maxWidth: string; use: string }[] = [
  { name: "Page", className: "no cap", maxWidth: "100%", use: "Every routed surface, at every screen size" },
  { name: "Editor body", className: "max-w-3xl", maxWidth: "48rem", use: "The note and document measure, inside a spanning page" },
  { name: "Generated prose", className: "max-w-[70ch]", maxWidth: "70ch", use: "Briefs, rationales, and other long text blocks" },
  { name: "Record rail", className: "minmax(16rem,20rem)", maxWidth: "20rem", use: "The record-detail left rail; the main column takes the rest" },
];

const RHYTHM: { label: string; token: string; size: string }[] = [
  { label: "Page gutter", token: "px-2", size: "0.5rem" },
  { label: "Page top", token: "pt-8", size: "2rem" },
  { label: "Page bottom", token: "pb-12", size: "3rem" },
  { label: "Section gap", token: "gap-10", size: "2.5rem" },
  { label: "Card interior", token: "p-6", size: "1.5rem" },
];

const NAV = [
  { id: "foundations", label: "Foundations" },
  { id: "layout", label: "Layout" },
  { id: "buttons", label: "Buttons" },
  { id: "overlays", label: "Overlays" },
  { id: "motion", label: "Motion" },
];

/** The D4 context height scale, read off the live reference pages. */
const BUTTON_CONTEXTS = [
  { size: "page", height: "h-9", use: "Page-header action cluster" },
  { size: "dialog", height: "h-9", use: "Dialog and drawer footers" },
  { size: "toolbar", height: "h-8", use: "Browser toolbars and filter rows" },
  { size: "inline", height: "h-6", use: "Inside a row, cell, or card" },
] as const;

function useMounted(): boolean {
  return React.useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  );
}

function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const mounted = useMounted();
  const isDark = mounted && resolvedTheme === "dark";

  return (
    <Button
      variant="outline"
      size="icon-sm"
      aria-label="Toggle theme"
      onClick={() => setTheme(isDark ? "light" : "dark")}
    >
      {isDark ? <MoonIcon /> : <SunIcon />}
    </Button>
  );
}

function Section({
  id,
  title,
  description,
  children,
}: {
  id: string;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <section id={id} className="scroll-mt-8 flex flex-col gap-6">
      <div className="flex flex-col gap-1 border-b border-border pb-4">
        <h2 className="text-2xl font-bold tracking-tight text-foreground">{title}</h2>
        <p className="max-w-prose text-sm text-muted-foreground">{description}</p>
      </div>
      {children}
    </section>
  );
}

function SubHeading({ children }: { children: React.ReactNode }) {
  return (
    <h3 className="font-mono text-xs font-medium tracking-wide text-muted-foreground uppercase">
      {children}
    </h3>
  );
}

function Swatch({ token }: { token: Token }) {
  return (
    <div className="flex flex-col gap-1.5">
      <div
        className="h-14 rounded-lg ring-1 ring-inset ring-border"
        style={{ background: `var(${token.cssVar})` }}
      />
      <div className="flex min-w-0 flex-col">
        <span className="truncate font-mono text-xs text-foreground">{token.name}</span>
        <span className="truncate font-mono text-[0.6875rem] text-muted-foreground">
          {token.cssVar}
        </span>
      </div>
    </div>
  );
}

function SpringDemo({ label, spec, transition }: { label: string; spec: string; transition: typeof springJiggle }) {
  const reduce = useReducedMotion() ?? false;
  const [on, setOn] = React.useState(false);
  return (
    <button
      type="button"
      onClick={() => setOn((v) => !v)}
      className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-5 text-left transition-colors hover:bg-muted/40"
    >
      <div className="relative h-9 w-full overflow-hidden rounded-lg bg-muted/60">
        <motion.div
          animate={{ x: on ? "calc(100% + 0.5rem)" : 0 }}
          transition={reduce ? instant : transition}
          className="absolute top-1 left-1 size-7 rounded-md bg-brand"
        />
      </div>
      <div className="flex flex-col">
        <span className="text-sm font-medium text-foreground">{label}</span>
        <span className="font-mono text-[0.6875rem] text-muted-foreground">{spec}</span>
      </div>
    </button>
  );
}

function CssMotionDemo({ label, spec, style }: { label: string; spec: string; style: React.CSSProperties }) {
  const [on, setOn] = React.useState(false);
  return (
    <button
      type="button"
      onClick={() => setOn((v) => !v)}
      className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-5 text-left transition-colors hover:bg-muted/40"
    >
      <div className="relative h-9 w-full overflow-hidden rounded-lg bg-muted/60">
        <div
          className="absolute top-1 left-1 size-7 rounded-md bg-brand"
          style={{ ...style, transform: on ? "translateX(calc(100% + 0.5rem))" : "translateX(0)" }}
        />
      </div>
      <div className="flex flex-col">
        <span className="text-sm font-medium text-foreground">{label}</span>
        <span className="font-mono text-[0.6875rem] text-muted-foreground">{spec}</span>
      </div>
    </button>
  );
}

export default function DesignSystemPage() {
  const [paletteOpen, setPaletteOpen] = React.useState(false);
  const [demoView, setDemoView] = React.useState<"grid" | "table">("grid");
  const [demoDensity, setDemoDensity] = React.useState<"comfortable" | "compact">("comfortable");
  const shortcutPlatform = useShortcutPlatform();

  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <PageShell>
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-2">
          <Badge variant="secondary" className="font-mono">
            Connex Design System
          </Badge>
          <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
            Foundations, layout, overlays &amp; motion
          </h1>
          <p className="max-w-prose text-sm text-muted-foreground">
            The canonical in-app reference. Every value here is a design token or a shared primitive —
            build against these, not ad-hoc hex, px, or one-off animation configs. Toggle the theme to see
            each token resolve.
          </p>
        </div>
        <ThemeToggle />
      </header>

      <div className="grid grid-cols-1 gap-10 lg:grid-cols-[10rem_minmax(0,1fr)]">
        <nav className="hidden lg:block">
          <ul className="sticky top-8 flex flex-col gap-1">
            {NAV.map((item) => (
              <li key={item.id}>
                <a
                  href={`#${item.id}`}
                  className="block rounded-lg px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                >
                  {item.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <div className="flex min-w-0 flex-col gap-16">
          <Section
            id="foundations"
            title="Foundations"
            description="Color, type, radius, spacing, and elevation. All colors are OKLCH tokens defined once in globals.css and themed for light and dark."
          >
            <div className="flex flex-col gap-10">
              {COLOR_GROUPS.map((group) => (
                <div key={group.title} className="flex flex-col gap-3">
                  <div className="flex flex-col gap-0.5">
                    <SubHeading>{group.title}</SubHeading>
                    <p className="text-xs text-muted-foreground">{group.note}</p>
                  </div>
                  <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                    {group.tokens.map((token) => (
                      <Swatch key={token.cssVar} token={token} />
                    ))}
                  </div>
                </div>
              ))}

              <div className="flex flex-col gap-3">
                <SubHeading>Typography</SubHeading>
                <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-6">
                  <p className="font-display text-3xl text-foreground">Instrument Serif — display</p>
                  <p className="text-4xl font-extrabold tracking-tight text-foreground">
                    Inter — page title / text-4xl
                  </p>
                  <p className="text-xl font-semibold text-foreground">Inter semibold — text-xl</p>
                  <p className="text-sm text-foreground">Inter — body / text-sm</p>
                  <p className="text-sm text-muted-foreground">Inter — muted body / text-sm</p>
                  <p className="text-2xl text-foreground">日本語 — Noto Sans JP</p>
                </div>
              </div>

              <div className="flex flex-col gap-3">
                <SubHeading>Radius</SubHeading>
                <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
                  {RADII.map((radius) => (
                    <div key={radius.className} className="flex flex-col gap-2">
                      <div
                        className={cn(
                          "h-16 border border-border bg-muted/60",
                          radius.className,
                        )}
                      />
                      <div className="flex flex-col">
                        <span className="font-mono text-xs text-foreground">{radius.label}</span>
                        <span className="text-[0.6875rem] text-muted-foreground">{radius.use}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              <div className="flex flex-col gap-3">
                <SubHeading>Spacing &amp; rhythm</SubHeading>
                <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-6">
                  {RHYTHM.map((row) => (
                    <div key={row.token} className="flex items-center gap-4">
                      <div className="w-28 shrink-0 text-sm text-foreground">{row.label}</div>
                      <div className="h-3 rounded-full bg-brand/70" style={{ width: row.size }} />
                      <span className="font-mono text-xs text-muted-foreground">
                        {row.token} · {row.size}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              <div className="flex flex-col gap-3">
                <SubHeading>Elevation</SubHeading>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                  <div className="rounded-2xl border border-border bg-card p-5">
                    <p className="text-sm font-medium text-foreground">Card</p>
                    <p className="mt-1 font-mono text-[0.6875rem] text-muted-foreground">
                      rounded-2xl border border-border bg-card
                    </p>
                  </div>
                  <div className="rounded-2xl bg-muted/30 p-5 ring-1 ring-inset ring-border">
                    <p className="text-sm font-medium text-foreground">Inset</p>
                    <p className="mt-1 font-mono text-[0.6875rem] text-muted-foreground">
                      bg-muted/30 ring-1 ring-border
                    </p>
                  </div>
                  <div className="rounded-2xl bg-popover p-5 ring-1 ring-inset ring-foreground/10 shadow-2xl">
                    <p className="text-sm font-medium text-foreground">Overlay</p>
                    <p className="mt-1 font-mono text-[0.6875rem] text-muted-foreground">
                      bg-popover ring-1 ring-foreground/10
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </Section>

          <Section
            id="layout"
            title="Layout"
            description="No page-width cap, one page rhythm, one primitive. Every routed surface renders inside <PageShell> and spans the full content area."
          >
            <div className="flex flex-col gap-3">
              <SubHeading>Where width is capped</SubHeading>
              <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-6">
                {WIDTH_RULES.map((rule) => (
                  <div key={rule.name} className="flex flex-col gap-1.5">
                    <div className="flex items-baseline justify-between">
                      <span className="text-sm font-medium text-foreground">{rule.name}</span>
                      <span className="font-mono text-xs text-muted-foreground">{rule.className}</span>
                    </div>
                    <div
                      className="h-8 rounded-lg bg-brand/15 ring-1 ring-inset ring-brand/30"
                      style={{ maxWidth: rule.maxWidth, width: "100%" }}
                    />
                    <span className="text-xs text-muted-foreground">{rule.use}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>PageShell</SubHeading>
              <p className="max-w-prose text-sm text-muted-foreground">
                <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">&lt;PageShell&gt;</code>{" "}
                encodes the responsive gutter, vertical padding, and the standard{" "}
                <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">gap-10</code> between
                sections. It takes no width prop: the column is uncapped, so a page never leaves a dead
                gutter. A surface that needs a readable measure puts it on the text block, never on the
                page. This very page is wrapped in one.
              </p>
            </div>
          </Section>

          <Section
            id="buttons"
            title="Buttons"
            description="Pill-shaped, one height per context, chevroned menu triggers, circular tooltipped icon buttons, one capsule for a split, a segmented control for mode switching, and exactly one primary action per region. Everything here is a variant of the same primitive."
          >
            <div className="flex flex-col gap-3">
              <SubHeading>Context height scale</SubHeading>
              <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-6">
                {BUTTON_CONTEXTS.map((context) => (
                  <div key={context.size} className="flex flex-wrap items-center gap-4">
                    <Button variant="outline" size={context.size}>
                      {context.size}
                    </Button>
                    <span className="font-mono text-xs text-muted-foreground">{context.height}</span>
                    <span className="text-xs text-muted-foreground">{context.use}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Menu triggers &amp; icon buttons</SubHeading>
              <div className="flex flex-wrap items-center gap-3">
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="toolbar" menu>
                      Columns
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start">
                    <DropdownMenuItem>Name</DropdownMenuItem>
                    <DropdownMenuItem>Owner</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <IconButton variant="outline" size="icon-toolbar" label="More actions">
                      <EllipsisVerticalIcon className="size-4" />
                    </IconButton>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="end">
                    <DropdownMenuItem>Assign owner</DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="destructive">Delete</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
                <span className="text-xs text-muted-foreground">
                  <code className="rounded bg-muted px-1 py-0.5 font-mono">menu</code> draws the chevron ·
                  IconButton makes the tooltip mandatory
                </span>
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Split button</SubHeading>
              <div className="flex flex-wrap items-center gap-3">
                <SplitButton
                  label="New contact"
                  icon={<PlusIcon className="size-4" />}
                  onClick={() => {}}
                  menuLabel="More actions"
                >
                  <DropdownMenuItem>
                    <ArrowUpTrayIcon className="size-4" />
                    Import
                  </DropdownMenuItem>
                  <DropdownMenuItem>
                    <ArrowDownTrayIcon className="size-4" />
                    Export current view
                  </DropdownMenuItem>
                </SplitButton>
                <SplitButton
                  variant="outline"
                  size="toolbar"
                  label="Save"
                  onClick={() => {}}
                  menuLabel="More save options"
                >
                  <DropdownMenuItem>Save as new view</DropdownMenuItem>
                </SplitButton>
                <span className="text-xs text-muted-foreground">
                  One capsule: pill caps outside, a straight seam inside, an inset hairline divider, and a
                  press dip that moves the whole shape.
                </span>
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Segmented control</SubHeading>
              <div className="flex flex-wrap items-center gap-3">
                <SegmentedControl
                  ariaLabel="View"
                  value={demoView}
                  onChange={setDemoView}
                  options={[
                    { value: "grid", icon: <Squares2X2Icon className="size-4" />, ariaLabel: "Grid view" },
                    { value: "table", icon: <TableCellsIcon className="size-4" />, ariaLabel: "Table view" },
                  ]}
                />
                <SegmentedControl
                  ariaLabel="Density"
                  value={demoDensity}
                  onChange={setDemoDensity}
                  options={[
                    { value: "comfortable", label: "Comfortable", icon: <Bars2Icon className="size-4" /> },
                    { value: "compact", label: "Compact", icon: <Bars3Icon className="size-4" /> },
                  ]}
                />
                <span className="text-xs text-muted-foreground">
                  One travelling thumb on <code className="rounded bg-muted px-1 py-0.5 font-mono">springSnappy</code>{" "}
                  · arrow keys move the selection
                </span>
              </div>
            </div>
          </Section>

          <Section
            id="overlays"
            title="Overlays"
            description="One vocabulary: bg-popover on a hairline ring, CSS fade+zoom for popovers, an iOS slide for sheets. ResponsiveDialog is the default — Dialog on desktop, Drawer on mobile."
          >
            <div className="flex flex-col gap-3">
              <SubHeading>Dialog — sizes</SubHeading>
              <div className="flex flex-wrap gap-2">
                {(["sm", "md", "lg", "xl"] as const).map((size) => (
                  <Dialog key={size}>
                    <DialogTrigger asChild>
                      <Button variant="outline">Dialog · {size}</Button>
                    </DialogTrigger>
                    <DialogContent size={size}>
                      <DialogHeader>
                        <DialogTitle>Dialog · {size}</DialogTitle>
                        <DialogDescription>
                          Centered, origin-center scale-in. Size is a typed prop — no !-important overrides.
                        </DialogDescription>
                      </DialogHeader>
                      <DialogFooter>
                        <DialogClose asChild>
                          <Button variant="ghost">Cancel</Button>
                        </DialogClose>
                        <DialogClose asChild>
                          <Button variant="brand">Confirm</Button>
                        </DialogClose>
                      </DialogFooter>
                    </DialogContent>
                  </Dialog>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Drawer — directions</SubHeading>
              <div className="flex flex-wrap gap-2">
                {(["down", "up", "left", "right"] as const).map((direction) => (
                  <Drawer key={direction} swipeDirection={direction} showSwipeHandle={direction === "down"}>
                    <DrawerTrigger render={<Button variant="outline" />}>Drawer · {direction}</DrawerTrigger>
                    <DrawerContent>
                      <DrawerHeader>
                        <DrawerTitle>Drawer · {direction}</DrawerTitle>
                        <DrawerDescription>
                          Base UI drawer, iOS slide curve, swipe-dismissable. One primitive, four directions.
                        </DrawerDescription>
                      </DrawerHeader>
                      <DrawerFooter>
                        <DrawerClose render={<Button variant="brand" />}>Done</DrawerClose>
                      </DrawerFooter>
                    </DrawerContent>
                  </Drawer>
                ))}
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>ResponsiveDialog</SubHeading>
              <div className="flex flex-wrap items-center gap-3">
                <ResponsiveDialog>
                  <ResponsiveDialogTrigger asChild>
                    <Button variant="outline">Open responsive</Button>
                  </ResponsiveDialogTrigger>
                  <ResponsiveDialogContent>
                    <ResponsiveDialogHeader>
                      <ResponsiveDialogTitle>Responsive surface</ResponsiveDialogTitle>
                      <ResponsiveDialogDescription>
                        A centered dialog here on desktop; a bottom drawer below the md breakpoint. Resize the
                        window and reopen to see it switch.
                      </ResponsiveDialogDescription>
                    </ResponsiveDialogHeader>
                    <ResponsiveDialogFooter>
                      <ResponsiveDialogClose asChild>
                        <Button variant="brand">Got it</Button>
                      </ResponsiveDialogClose>
                    </ResponsiveDialogFooter>
                  </ResponsiveDialogContent>
                </ResponsiveDialog>
                <span className="text-xs text-muted-foreground">Dialog on desktop · Drawer on mobile</span>
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Menus, tooltips &amp; command</SubHeading>
              <div className="flex flex-wrap items-center gap-2">
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="outline">Dropdown</Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent align="start">
                    <DropdownMenuLabel>Actions</DropdownMenuLabel>
                    <DropdownMenuItem>Edit</DropdownMenuItem>
                    <DropdownMenuItem>Duplicate</DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="destructive">Delete</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>

                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button variant="outline">Hover for tooltip</Button>
                  </TooltipTrigger>
                  <TooltipContent>Inverted surface, instant on subsequent hovers</TooltipContent>
                </Tooltip>

                <Button variant="outline" onClick={() => setPaletteOpen(true)}>
                  Command palette
                  <Badge
                    variant="ghost"
                    className={cn("w-16 justify-center font-mono", !shortcutPlatform && "invisible")}
                  >
                    {formatShortcut("mod+k", shortcutPlatform ?? "other")}
                  </Badge>
                </Button>
              </div>
            </div>
          </Section>

          <Section
            id="motion"
            title="Motion"
            description="Three speeds and two characters, defined once in app/globals.css and mirrored in app/lib/motion.ts. Springs give physics; the easing tokens give consistent curves. Everything falls back to instant under prefers-reduced-motion. Click any tile."
          >
            <div className="flex flex-col gap-3">
              <SubHeading>Springs</SubHeading>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <SpringDemo label="springJiggle" spec="playful · press, staggers" transition={springJiggle} />
                <SpringDemo label="springSnappy" spec="precise · tabs, morphs" transition={springSnappy} />
                <SpringDemo label="springSmooth" spec="gentle · panels, slides" transition={springSmooth} />
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Easings</SubHeading>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <CssMotionDemo
                  label="--motion-ease-hand"
                  spec="ease-hand · press, pop-in"
                  style={{ transition: "transform 600ms var(--motion-ease-hand)" }}
                />
                <CssMotionDemo
                  label="--motion-ease-calm"
                  spec="ease-calm · state changes"
                  style={{ transition: "transform 600ms var(--motion-ease-calm)" }}
                />
                <CssMotionDemo
                  label="--ease-out"
                  spec="enter / exit"
                  style={{ transition: "transform 600ms var(--ease-out)" }}
                />
              </div>
            </div>

            <div className="flex flex-col gap-3">
              <SubHeading>Speeds</SubHeading>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                <CssMotionDemo
                  label="--motion-micro"
                  spec="150ms · hover, toggle, press, menus"
                  style={{ transition: "transform var(--motion-micro) var(--motion-ease-hand)" }}
                />
                <CssMotionDemo
                  label="--motion-standard"
                  spec="250ms · overlays, entrances"
                  style={{ transition: "transform var(--motion-standard) var(--motion-ease-calm)" }}
                />
                <CssMotionDemo
                  label="--motion-expressive"
                  spec="400ms · rare, memorable"
                  style={{ transition: "transform var(--motion-expressive) var(--ease-out)" }}
                />
              </div>
            </div>
          </Section>
        </div>
      </div>

      <CommandDialog open={paletteOpen} onOpenChange={setPaletteOpen}>
        <CommandInput placeholder="Search the design system…" />
        <CommandList>
          <CommandEmpty>No results.</CommandEmpty>
          <CommandGroup heading="Sections">
            {NAV.map((item) => (
              <CommandItem key={item.id} onSelect={() => setPaletteOpen(false)}>
                {item.label}
              </CommandItem>
            ))}
          </CommandGroup>
        </CommandList>
      </CommandDialog>
    </PageShell>
  );
}
