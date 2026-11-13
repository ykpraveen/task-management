/**
 * Application IDs, versions and policy epochs are PostgreSQL bigints carried as canonical
 * positive decimal strings (1..9223372036854775807). They are never JSON numbers: values
 * above Number.MAX_SAFE_INTEGER would silently lose precision. Router params, Query keys,
 * cursors and Broadcast payloads keep them as strings.
 */

export const MAX_ID = 9223372036854775807n;

const CANONICAL = /^[1-9][0-9]{0,18}$/;

export class InvalidIdError extends Error {
  constructor(readonly field: string) {
    super(`${field} is not a valid ID`);
    this.name = 'InvalidIdError';
  }
}

/** True only for a canonical decimal string in 1..MAX_ID. Numbers are never IDs. */
export function isCanonicalId(value: unknown): value is string {
  return typeof value === 'string' && CANONICAL.test(value) && BigInt(value) <= MAX_ID;
}

/** Returns the ID unchanged, or throws for numbers, zero, negatives, noncanonical text or overflow. */
export function parseId(value: unknown, field = 'id'): string {
  if (!isCanonicalId(value)) {
    throw new InvalidIdError(field);
  }
  return value;
}

/** Like parseId, but returns undefined instead of throwing (for Router param validation). */
export function tryParseId(value: unknown): string | undefined {
  return isCanonicalId(value) ? value : undefined;
}

/** Numeric ordering of two valid IDs without converting to Number. */
export function compareIds(a: string, b: string): number {
  const left = BigInt(parseId(a));
  const right = BigInt(parseId(b));
  return left < right ? -1 : left > right ? 1 : 0;
}

/** If-Match value for a task version: the quoted decimal string. */
export function ifMatch(version: string): string {
  return `"${parseId(version, 'version')}"`;
}

/** Version carried by an ETag header, or undefined when the header is absent or malformed. */
export function versionFromEtag(etag: string | null | undefined): string | undefined {
  const match = etag ? /^"([^"]*)"$/.exec(etag) : null;
  return match ? tryParseId(match[1]) : undefined;
}
