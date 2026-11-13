import { Link } from '@tanstack/react-router';
import { useMemo, useState, type FormEvent } from 'react';
import { ErrorSummary, type SummaryError } from '../a11y/ErrorSummary';
import { useAnnouncer } from '../a11y/LiveRegion';
import { useCurrentUser, type CurrentUser, type SessionController } from './session';

export const DISPLAY_NAME_MAX = 200;
const NAME_ID = 'profile-display-name';
const ZONE_ID = 'profile-time-zone';

/** IANA zones this browser knows, sorted, always including UTC. */
export function supportedTimeZones(): string[] {
  const zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
  return Array.from(new Set([...zones, 'UTC'])).sort((a, b) => a.localeCompare(b));
}

export function isValidTimeZone(zone: string): boolean {
  if (zone === '') return false;
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

export function browserTimeZone(): string | null {
  const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  return zone && isValidTimeZone(zone) ? zone : null;
}

function defaultDisplayName(user: CurrentUser): string {
  return user.displayName ?? user.email.split('@')[0] ?? '';
}

export function validateProfile(displayName: string, timeZone: string, zones: readonly string[]): SummaryError[] {
  const errors: SummaryError[] = [];
  const name = displayName.trim();
  if (name === '') errors.push({ fieldId: NAME_ID, message: 'Enter a display name' });
  else if (name.length > DISPLAY_NAME_MAX)
    errors.push({ fieldId: NAME_ID, message: `Display name must be ${DISPLAY_NAME_MAX} characters or fewer` });
  if (!zones.includes(timeZone) || !isValidTimeZone(timeZone))
    errors.push({ fieldId: ZONE_ID, message: `“${timeZone || 'No time zone'}” is not a recognised time zone. Choose one from the list` });
  return errors;
}

/**
 * Display name and time zone settings. Changing the zone only affects how later date-only
 * inputs and date filters are read; existing deadlines keep their recorded zone. Account
 * identity, email and activation stay with user management.
 */
export function ProfileSettings({ session }: { session: SessionController }) {
  const user = useCurrentUser(session);
  const zones = useMemo(supportedTimeZones, []);
  const detected = useMemo(browserTimeZone, []);
  const { announce } = useAnnouncer();
  const [displayName, setDisplayName] = useState(() => (user ? defaultDisplayName(user) : ''));
  // A stored zone that this browser cannot use is kept visible so the rejection can be explained.
  const [timeZone, setTimeZone] = useState(() => user?.timeZone ?? detected ?? 'UTC');
  const [errors, setErrors] = useState<SummaryError[]>(() =>
    user?.timeZone && !isValidTimeZone(user.timeZone) ? validateProfile(defaultDisplayName(user), user.timeZone, zones) : [],
  );
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState(false);

  if (!user) return null;

  const zoneOptions = zones.includes(timeZone) ? zones : [timeZone, ...zones];
  const fieldError = (fieldId: string) => errors.find((e) => e.fieldId === fieldId);
  const nameError = fieldError(NAME_ID);
  const zoneError = fieldError(ZONE_ID);
  const zoneHint = user.timeZone === null && detected === timeZone ? 'Detected from your browser. Save to confirm it.' : null;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    setAttempt((n) => n + 1);
    const validation = validateProfile(displayName, timeZone, zones);
    setErrors(validation);
    if (validation.length > 0) return;

    setPending(true);
    const result = await session.updateProfile({ displayName: displayName.trim(), timeZone });
    setPending(false);
    if (result.ok) {
      announce('Profile saved.');
    } else {
      setErrors([{ fieldId: NAME_ID, message: result.message }]);
    }
  }

  return (
    <>
      <h1>Profile settings</h1>
      <p>
        Your time zone decides when a deadline entered as a date only ends (23:59 in that zone) and how your date
        filters are read. Changing it does not move existing deadlines.
      </p>
      <ErrorSummary errors={errors} focusKey={attempt} />
      <form className="stack" noValidate onSubmit={onSubmit} aria-label="Profile">
        <div className="field">
          <label htmlFor={NAME_ID}>Display name (required)</label>
          <input
            id={NAME_ID}
            name="displayName"
            type="text"
            autoComplete="name"
            required
            maxLength={DISPLAY_NAME_MAX}
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            aria-invalid={nameError ? true : undefined}
            aria-describedby={[`${NAME_ID}-hint`, nameError ? `${NAME_ID}-error` : ''].filter(Boolean).join(' ')}
          />
          <p id={`${NAME_ID}-hint`} className="field-hint">
            Up to {DISPLAY_NAME_MAX} characters. Shown to people you share tasks with.
          </p>
          {nameError ? (
            <p id={`${NAME_ID}-error`} className="field-error">
              {nameError.message}
            </p>
          ) : null}
        </div>
        <div className="field">
          <label htmlFor={ZONE_ID}>Time zone (required)</label>
          <select
            id={ZONE_ID}
            name="timeZone"
            required
            value={timeZone}
            onChange={(event) => setTimeZone(event.target.value)}
            aria-invalid={zoneError ? true : undefined}
            aria-describedby={
              [zoneHint ? `${ZONE_ID}-hint` : '', zoneError ? `${ZONE_ID}-error` : ''].filter(Boolean).join(' ') ||
              undefined
            }
          >
            {zoneOptions.map((zone) => (
              <option key={zone} value={zone}>
                {zone.replaceAll('_', ' ')}
              </option>
            ))}
          </select>
          {zoneHint ? (
            <p id={`${ZONE_ID}-hint`} className="field-hint">
              {zoneHint}
            </p>
          ) : null}
          {zoneError ? (
            <p id={`${ZONE_ID}-error`} className="field-error">
              {zoneError.message}
            </p>
          ) : null}
        </div>
        <button type="submit" className="primary" aria-disabled={pending || undefined}>
          {pending ? 'Saving…' : 'Save profile'}
        </button>
      </form>
    </>
  );
}

/**
 * First sign-in offer: until the account has chosen a zone, it uses the deployment default.
 * Offer the browser's zone for explicit confirmation; nothing changes without a click.
 */
export function TimeZoneOffer({ session }: { session: SessionController }) {
  const user = useCurrentUser(session);
  const detected = useMemo(browserTimeZone, []);
  const { announce } = useAnnouncer();
  const [dismissed, setDismissed] = useState(false);
  const [failed, setFailed] = useState(false);
  const [pending, setPending] = useState(false);

  if (!user || user.timeZone !== null || !detected || dismissed) return null;

  async function confirm() {
    if (!user || !detected || pending) return;
    setPending(true);
    const result = await session.updateProfile({ displayName: defaultDisplayName(user), timeZone: detected });
    setPending(false);
    setFailed(!result.ok);
    announce(result.ok ? `Time zone set to ${detected}.` : 'Your time zone was not saved. Try again.');
  }

  return (
    <section className="notice" aria-label="Confirm your time zone">
      <p>
        Your browser reports the time zone <strong>{detected}</strong>. Use it for deadlines you enter as a date
        only?
      </p>
      {failed ? <p className="field-error">Your time zone was not saved. Try again.</p> : null}
      <div className="actions">
        <button type="button" className="primary" onClick={() => void confirm()} aria-disabled={pending || undefined}>
          Use {detected}
        </button>
        <Link to="/profile">Choose another time zone</Link>
        <button type="button" onClick={() => setDismissed(true)}>
          Not now
        </button>
      </div>
    </section>
  );
}
