import { describe, expect, it } from 'vitest';
import {
  InvalidIdError,
  MAX_ID,
  compareIds,
  ifMatch,
  isCanonicalId,
  parseId,
  tryParseId,
  versionFromEtag,
} from './ids';

describe('canonical decimal-string IDs', () => {
  it.each(['1', '42', '9007199254740993', MAX_ID.toString()])('accepts %s', (value) => {
    expect(isCanonicalId(value)).toBe(true);
    expect(parseId(value)).toBe(value);
  });

  it('preserves IDs above the JavaScript safe-integer limit exactly', () => {
    const id = '9007199254740993';
    expect(Number(id).toString()).not.toBe(id);
    expect(parseId(JSON.parse(`{"id":"${id}"}`).id)).toBe(id);
  });

  it.each([
    ['a JSON number', 42],
    ['an unsafe JSON number', JSON.parse('{"id":9007199254740993}').id],
    ['a bigint', 42n],
    ['zero', '0'],
    ['a negative value', '-1'],
    ['a leading plus sign', '+1'],
    ['leading zeros', '007'],
    ['whitespace', ' 1'],
    ['a trailing newline', '1\n'],
    ['an exponent', '1e3'],
    ['a decimal point', '1.0'],
    ['hex', '0x10'],
    ['an empty string', ''],
    ['one above the bigint maximum', (MAX_ID + 1n).toString()],
    ['twenty digits', '10000000000000000000'],
    ['null', null],
    ['undefined', undefined],
  ])('rejects %s', (_label, value) => {
    expect(isCanonicalId(value)).toBe(false);
    expect(tryParseId(value)).toBeUndefined();
    expect(() => parseId(value, 'taskId')).toThrow(InvalidIdError);
  });

  it('names the field in the error', () => {
    expect(() => parseId('0', 'taskId')).toThrow('taskId is not a valid ID');
  });

  it('compares numerically without precision loss', () => {
    expect(compareIds('9007199254740993', '9007199254740992')).toBe(1);
    expect(compareIds('2', '10')).toBe(-1);
    expect(compareIds('5', '5')).toBe(0);
  });

  it('formats and reads ETag versions', () => {
    expect(ifMatch('9007199254740993')).toBe('"9007199254740993"');
    expect(versionFromEtag('"17"')).toBe('17');
    expect(versionFromEtag('W/"17"')).toBeUndefined();
    expect(versionFromEtag('"0"')).toBeUndefined();
    expect(versionFromEtag(null)).toBeUndefined();
    expect(() => ifMatch('0')).toThrow(InvalidIdError);
  });
});
