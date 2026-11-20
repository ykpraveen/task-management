/**
 * Deadlines are UTC instants with the IANA zone they were entered in. Forms work with the
 * wall-clock date and time in that zone; these helpers convert between the two with Intl
 * only, so no time-zone database ships with the app. The server stays authoritative for
 * DST gaps and overlaps (it rejects nonexistent and ambiguous times).
 */

/** Time used when a deadline is entered as a date only (FR: 23:59 in the chosen zone). */
export const DATE_ONLY_TIME = '23:59';

const DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const TIME = /^(\d{2}):(\d{2})$/;

export function isDateInput(value: string): boolean {
  return DATE.test(value);
}

export function isTimeInput(value: string): boolean {
  return TIME.test(value);
}

interface WallClock {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
}

function wallClockAt(instant: number, zone: string): WallClock {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).formatToParts(new Date(instant));
  const get = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find((p) => p.type === type)?.value);
  return { year: get('year'), month: get('month'), day: get('day'), hour: get('hour'), minute: get('minute') };
}

/** Zone offset in milliseconds at an instant (wall clock minus UTC). */
function offsetAt(instant: number, zone: string): number {
  const w = wallClockAt(instant, zone);
  const minute = Math.floor(instant / 60_000) * 60_000;
  return Date.UTC(w.year, w.month - 1, w.day, w.hour, w.minute) - minute;
}

/**
 * UTC instant (RFC 3339) for a wall-clock date and `HH:MM` time in `zone`. An empty time
 * means a date-only deadline, which ends at 23:59.
 */
export function zonedToInstant(date: string, time: string, zone: string): string {
  const d = DATE.exec(date);
  const t = TIME.exec(time || DATE_ONLY_TIME);
  if (!d || !t) throw new RangeError('Invalid date or time');
  const local = Date.UTC(Number(d[1]), Number(d[2]) - 1, Number(d[3]), Number(t[1]), Number(t[2]));
  // Two passes settle the offset on either side of a DST change.
  const guess = local - offsetAt(local, zone);
  const instant = local - offsetAt(guess, zone);
  return new Date(instant).toISOString().replace('.000Z', 'Z');
}

const pad = (n: number) => String(n).padStart(2, '0');

/** Wall-clock `YYYY-MM-DD` and `HH:MM` of an instant in `zone`, for prefilling inputs. */
export function instantToZoned(instant: string, zone: string): { date: string; time: string } {
  const w = wallClockAt(Date.parse(instant), zone);
  return { date: `${w.year}-${pad(w.month)}-${pad(w.day)}`, time: `${pad(w.hour)}:${pad(w.minute)}` };
}

export function zoneLabel(zone: string): string {
  return zone.replaceAll('_', ' ');
}

/** A deadline as text in the zone it was set in, e.g. "Dec 11, 2026, 11:59 PM (Europe/Berlin)". */
export function formatDeadline(instant: string, zone: string): string {
  const text = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: zone }).format(
    new Date(instant),
  );
  return `${text} (${zoneLabel(zone)})`;
}

/** An event time as text in the viewer's zone (falls back to the browser's). */
export function formatTimestamp(instant: string, zone: string | null | undefined): string {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
    ...(zone ? { timeZone: zone } : {}),
  }).format(new Date(instant));
}
