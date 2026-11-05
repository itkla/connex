# Dates and time zones

API timestamps are UTC instants, including offset-less ISO and MySQL datetime strings. Parse them
with `parseMysqlDateTime`, never with `new Date(rawApiValue)`. Display them with the shared
`formatDateTime`, `formatUtcDateTime`, `formatDate`, or `formatShortDate` helpers and pass the zone
from next-intl's `useTimeZone` / `getTimeZone`. Relative-time helpers also take that zone for their
absolute-date fallback. Do not use the browser or server host's zone as a preference.

`i18n/request.ts` supplies the request zone and shared request clock. `requestTimeZone` resolves the
authenticated profile zone first, then the active workspace zone, then UTC. It reads workspace
membership through the existing authenticated API and does not interpret the workspace cookie as
authority. Public requests without a session use UTC without fetching the backend. The root
`NextIntlClientProvider` inherits this configuration, so server rendering and hydration agree.
Refreshing after a profile save updates the provider's zone. The profile picker filters localized
zone names and Tokyo/UTC aliases while retaining canonical API identifiers.

Commercial document money and generated-date values use the frozen `documentLocale`, including
preview, print and recipient views. Date values still use the shared UTC parser and resolved display
zone; the frozen locale does not freeze a timezone. Surrounding UI labels and acceptance-flow expiry
information retain the viewer's UI locale.

Calendar-only values (`yyyy-MM-dd`, including task due dates, birthdays and expected close dates)
remain dates. `formatDate` and `formatShortDate` recognize this shape and render it on a fixed UTC
calendar. For custom next-intl options, use `parseCalendarDateUtc` with `timeZone: 'UTC'`, or
`formatCalendarDate`. Do not create a local-midnight instant and then apply the viewer's zone.
`parseCalendarDate` remains the local civil-date adapter for date-grid widgets; those `Date`
objects are calendar placeholders and must not be formatted as profile-zone instants.

Calendar urgency compares date-only UTC anchors with `calendarDayInTimeZone(sharedNow, zone)`.
Use `useLiveNow` for urgency, today actions and current calendar windows. `useNow` deliberately
retains the layout's initial timestamp across navigation; it is only a hydration reference.
This avoids 23/25-hour DST days. Calendar event grouping and timed placement use the display zone;
all-day event keys preserve the API date. Dashboard and My Work use the same greeting function and
request clock. Japanese greetings retain the complete display name.

Datetime-local editors use `toDatetimeLocalValue(value, zone)` and
`toMysqlDateTime(input, zone, previous)`. Missing DST times are rejected, an unchanged repeated time
keeps its original instant, and a changed repeated time chooses the earlier occurrence. API formats
and stored data are unchanged. Reporting windows and revenue buckets retain their separately
specified workspace reporting calendar; displaying an individual instant follows the profile rule.

Deal close-date adapters in `dealDates.ts` delegate parsing, formatting and civil-input conversion
to these same shared helpers. Close-date displays and editors use the profile zone first; a reporting
zone prop is only a workspace fallback. Imported Tokyo midnight instants still display as the imported
day for a Tokyo profile, and bare imported calendar dates never shift. Legacy minute-offset zones such
as `UTC+9` normalize once in the shared resolver. The shared fixed-offset formatting adapter also
preserves second offsets across midnight. Import interpretation and revenue buckets retain their
explicit workspace reporting calendar. Deal validation still rejects years before 1000 and invalid
civil inputs; unchanged repeated-hour instants and timestamp seconds survive unrelated edits.

Calendar slot creation carries the selected `yyyy-MM-dd` and minutes as a civil input string through
`calendarSlotInputValue`. It must not use `Date.setHours` or epoch milliseconds in the browser zone:
the browser's DST gap may be a valid time in the profile zone. The activity form converts that
civil input once at submission. My Work evidence uses its calendar `date` when present and formats
`occurredAt` as an instant only when no calendar date exists.

`intlFormatters.ts` caches immutable Intl formatters by locale, zone and normalized options.
Each formatter family uses a bounded LRU of 512 entries. Display helpers and call sites share it,
including zone validation and civil-input conversion, so a long list reuses formatters instead of
constructing them for each row. Values being formatted are never cached.
Cache entries contain no user or workspace identity: equal locale/zone/options may share a formatter,
while switching the profile or workspace zone selects a different key. Previously returned formatters
retain their own zone even when a caller reuses and changes its options object.

## API-bound datetime-local inventory

The audit found 12 controls in seven components. Each input converts exactly once; API clients
receive UTC values and never reinterpret them as civil inputs.

| Component | Field | Read/write boundary and original value |
| --- | --- | --- |
| `ActivityDialogForm` | `when` | Profile-zone input → UTC MySQL string on create; no original instant |
| `EditActivitySheet` | `timestamp` | Zoned input ↔ UTC; passes `activity.timestamp` as previous |
| `QuickEditDealSheet` | `closedAt` | Zoned input → UTC draft once in `onChange`; passes the current UTC `draft.closedAt`; parent sends the UTC draft directly |
| `EditCampaignSheet` via `CampaignDetail` | `startAt` | Zoned input ↔ UTC ISO civil shape; unchanged values retain `current.startAt` |
| `EditCampaignSheet` via `CampaignDetail` | `endAt` | Zoned input ↔ UTC ISO civil shape; unchanged values retain `current.endAt` |
| `DataRequestDialog` | `receivedAt` | Zoned input ↔ UTC ISO civil shape; passes `editing.receivedAt` |
| `DataRequestDialog` | `identityVerifiedAt` | Same boundary; passes `editing.identityVerifiedAt` |
| `DataRequestDialog` | `dueAt` | Same boundary; passes `editing.dueAt` |
| `DataRequestDialog` | `respondedAt` | Same boundary; passes `editing.respondedAt` |
| `DataRequestDialog` | `closedAt` | Same boundary; passes `editing.closedAt` |
| `SnoozeCustomDialog` | `customValue` | Profile-zone bounds and validation → UTC ISO instant; new deadline |
| `RadarSnoozeDialog` | `value` | Profile-zone bounds and validation → UTC ISO instant; new deadline |

Notification snooze presets also send the profile zone. `NewCampaignDialog` has no date inputs:
`campaignInstantCreatePayload` sends name, type and draft status only; the builder owns its window.
Dynamic input types in custom fields, editable cells, auth/user forms and recipes add no datetime
controls. Date-only controls and report schedule hour/day rules retain their separate contracts.

`test/unit/timeZoneDisplay.test.tsx` covers Tokyo/UTC, east/west calendar dates, DST, fallbacks,
greetings and hydration. `test/unit/requestTimeZone.test.ts` covers request preference resolution.
`test/e2e/profile-timezone.spec.ts` exercises EN/JA desktop/phone with a Tokyo profile and a
Los Angeles browser, then changes the profile to UTC and verifies the due day stays fixed.
