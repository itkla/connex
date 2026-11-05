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

Calendar-only values (`yyyy-MM-dd`, including task due dates, birthdays and expected close dates)
remain dates. `formatDate` and `formatShortDate` recognize this shape and render it on a fixed UTC
calendar. For custom next-intl options, use `parseCalendarDateUtc` with `timeZone: 'UTC'`, or
`formatCalendarDate`. Do not create a local-midnight instant and then apply the viewer's zone.
`parseCalendarDate` remains the local civil-date adapter for date-grid widgets; those `Date`
objects are calendar placeholders and must not be formatted as profile-zone instants.

Calendar urgency compares date-only UTC anchors with `calendarDayInTimeZone(sharedNow, zone)`.
This avoids 23/25-hour DST days. Calendar event grouping and timed placement use the display zone;
all-day event keys preserve the API date. Dashboard and My Work use the same greeting function and
request clock. Japanese greetings retain the complete display name.

Datetime-local activity editors use `toDatetimeLocalValue(value, zone)` and
`toMysqlDateTime(input, zone, previous)`. Missing DST times are rejected, an unchanged repeated time
keeps its original instant, and a changed repeated time chooses the earlier occurrence. API formats
and stored data are unchanged. Reporting windows and revenue buckets retain their separately
specified workspace reporting calendar; displaying an individual instant follows the profile rule.

`test/unit/timeZoneDisplay.test.tsx` covers Tokyo/UTC, east/west calendar dates, DST, fallbacks,
greetings and hydration. `test/unit/requestTimeZone.test.ts` covers request preference resolution.
`test/e2e/profile-timezone.spec.ts` exercises EN/JA desktop/phone with a Tokyo profile and a
Los Angeles browser, then changes the profile to UTC and verifies the due day stays fixed.
