"use client";

import { useRef, useState } from "react";
import { ChevronLeftIcon, ChevronRightIcon } from "@heroicons/react/24/outline";
import Link from "next/link";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";

import { durationMicro, easeCalm, instant, springSnappy } from "@/app/lib/motion";
import type { SettingsNavGroup, SettingsNavModel, SettingsNavScope } from "@/app/lib/settingsNavigation";

const ROW_CLASS =
    "flex w-full items-center justify-between gap-4 px-3 py-3 text-left outline-none transition-colors duration-(--motion-micro) hover:bg-muted focus-visible:ring-2 focus-visible:ring-brand/40 motion-reduce:transition-none";

/** Which level of the drill-down is on screen, as the ids the model can be re-resolved from. */
type DrillPath = {
    scope: string | null;
    group: string | null;
};

/**
 * Where keyboard focus belongs after the level changes: on the new level's heading when drilling
 * in, or back on the row the reader came from when stepping out.
 *
 * Held in a ref and claimed by the target's own callback ref rather than applied from an effect:
 * `AnimatePresence` in `wait` mode mounts the incoming level only after the outgoing one has
 * finished leaving, so an effect keyed on the level would run while the element it wants to focus
 * does not exist yet.
 */
type FocusIntent = { kind: "heading" } | { kind: "row"; id: string } | null;

/**
 * The narrow-viewport settings navigation: one list at a time, drilling from the authorization
 * scopes to the groups inside one, to the destinations inside one group.
 *
 * A group holding a single destination is a link rather than another level, so the reader never
 * taps into a list of one. Every level below the first carries a back control naming its parent, so
 * the way out is always the same control in the same place, and focus follows the level rather than
 * staying on a control that no longer exists: drilling in moves it to the new heading, stepping out
 * returns it to the row that opened the level.
 *
 * @param scopes - the resolved navigation
 * @param homeName - what the top level is called, for the back control one level down
 * @param backLabel - renders the back control's label from the parent's name
 */
export default function SettingsDrillDown({
    scopes,
    homeName,
    backLabel,
}: {
    scopes: SettingsNavModel;
    homeName: string;
    backLabel: (name: string) => string;
}) {
    const [path, setPath] = useState<DrillPath>({ scope: null, group: null });
    const pendingFocus = useRef<FocusIntent>(null);
    const rows = useRef(new Map<string, HTMLButtonElement | null>());
    const reduce = useReducedMotion() ?? false;

    const scope: SettingsNavScope | null = scopes.find((candidate) => candidate.scope === path.scope) ?? null;
    const group: SettingsNavGroup | null = scope?.groups.find((candidate) => candidate.id === path.group) ?? null;
    const level = group ? "destinations" : scope ? "groups" : "scopes";
    const heading = group?.title ?? scope?.label ?? null;
    const parentName = group ? (scope?.label ?? homeName) : homeName;

    function claimFocus(element: HTMLElement | null, matches: (intent: NonNullable<FocusIntent>) => boolean) {
        const intent = pendingFocus.current;
        if (element === null || intent === null || !matches(intent)) return;
        pendingFocus.current = null;
        element.focus();
    }

    function drillTo(next: DrillPath) {
        pendingFocus.current = { kind: "heading" };
        setPath(next);
    }

    function stepOut() {
        const origin = path.group ?? path.scope;
        pendingFocus.current = origin === null ? null : { kind: "row", id: origin };
        setPath((current) =>
            current.group === null ? { scope: null, group: null } : { scope: current.scope, group: null },
        );
    }

    const transition = reduce ? instant : springSnappy;
    const enter = reduce ? { opacity: 1 } : { opacity: 1, transform: "translateX(0px)" };
    const from = reduce ? { opacity: 0 } : { opacity: 0, transform: "translateX(16px)" };
    const exitTo = reduce
        ? { opacity: 0, transition: { duration: durationMicro } }
        : { opacity: 0, transform: "translateX(-8px)", transition: { duration: durationMicro, ease: easeCalm } };

    return (
        <div>
            {heading ? (
                <div className="mb-2 flex items-center gap-1">
                    <button
                        type="button"
                        onClick={stepOut}
                        className="-ml-2 inline-flex items-center gap-1 px-2 py-1 text-sm text-muted-foreground outline-none transition-colors duration-(--motion-micro) hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40 motion-reduce:transition-none"
                    >
                        <ChevronLeftIcon aria-hidden className="size-4" />
                        {backLabel(parentName)}
                    </button>
                </div>
            ) : null}
            <AnimatePresence mode="wait" initial={false}>
                <motion.div
                    key={`${path.scope ?? ""}/${path.group ?? ""}`}
                    initial={from}
                    animate={enter}
                    exit={exitTo}
                    transition={transition}
                >
                    {heading ? (
                        <h2
                            ref={(element) => {
                                claimFocus(element, (intent) => intent.kind === "heading");
                            }}
                            tabIndex={-1}
                            className="mb-2 px-3 text-base font-semibold text-foreground outline-none"
                        >
                            {heading}
                        </h2>
                    ) : null}
                    <ul className="-mx-3 divide-y divide-border/60">
                        {level === "scopes"
                            ? scopes.map((candidate) => (
                                  <li key={candidate.scope}>
                                      <button
                                          type="button"
                                          ref={(element) => {
                                              rows.current.set(candidate.scope, element);
                                              claimFocus(
                                                  element,
                                                  (intent) =>
                                                      intent.kind === "row" && intent.id === candidate.scope,
                                              );
                                          }}
                                          onClick={() => drillTo({ scope: candidate.scope, group: null })}
                                          className={ROW_CLASS}
                                      >
                                          <span className="min-w-0">
                                              <span className="block truncate text-sm font-medium text-foreground">
                                                  {candidate.name}
                                              </span>
                                              {candidate.qualifier ? (
                                                  <span className="block truncate text-xs text-muted-foreground">
                                                      {candidate.qualifier}
                                                  </span>
                                              ) : null}
                                          </span>
                                          <ChevronRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                                      </button>
                                  </li>
                              ))
                            : null}
                        {level === "groups" && scope
                            ? scope.groups.map((candidate) =>
                                  candidate.destinations.length > 1 ? (
                                      <li key={candidate.id}>
                                          <button
                                              type="button"
                                              ref={(element) => {
                                                  rows.current.set(candidate.id, element);
                                                  claimFocus(
                                                      element,
                                                      (intent) =>
                                                          intent.kind === "row" && intent.id === candidate.id,
                                                  );
                                              }}
                                              onClick={() => drillTo({ scope: scope.scope, group: candidate.id })}
                                              className={ROW_CLASS}
                                          >
                                              <span className="min-w-0 truncate text-sm font-medium text-foreground">
                                                  {candidate.title}
                                              </span>
                                              <ChevronRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                                          </button>
                                      </li>
                                  ) : (
                                      <li key={candidate.id}>
                                          <Link href={candidate.href} className={ROW_CLASS}>
                                              <span className="min-w-0 truncate text-sm font-medium text-foreground">
                                                  {candidate.title}
                                              </span>
                                              <ChevronRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                                          </Link>
                                      </li>
                                  ),
                              )
                            : null}
                        {level === "destinations" && group
                            ? group.destinations.map((destination) => (
                                  <li key={destination.id}>
                                      <Link href={destination.href} className={ROW_CLASS}>
                                          <span className="min-w-0 truncate text-sm font-medium text-foreground">
                                              {destination.title}
                                          </span>
                                          <ChevronRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                                      </Link>
                                  </li>
                              ))
                            : null}
                    </ul>
                </motion.div>
            </AnimatePresence>
        </div>
    );
}
