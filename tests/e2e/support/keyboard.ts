import { expect, type Locator, type Page } from '@playwright/test';

export interface TabToOptions {
  /** Press Shift+Tab instead of Tab, e.g. to reach the header navigation from inside `main`. */
  backwards?: boolean;
  /** Upper bound on key presses before the helper fails (no keyboard trap goes unnoticed). */
  maxPresses?: number;
}

async function isFocused(target: Locator): Promise<boolean> {
  return target.evaluate((element) => element === element.ownerDocument.activeElement).catch(() => false);
}

/**
 * Moves focus to `target` using only Tab (or Shift+Tab), the way a keyboard user would, and fails
 * if it is not reached within `maxPresses`. It never calls `focus()` or `click()`, so an element
 * that is unreachable, hidden from the tab order or behind a trap fails the test.
 */
export async function tabTo(page: Page, target: Locator, options: TabToOptions = {}): Promise<void> {
  const { backwards = false, maxPresses = 120 } = options;
  await expect(target).toBeVisible();
  for (let presses = 0; presses <= maxPresses; presses += 1) {
    if (await isFocused(target)) {
      await expect(target).toBeFocused();
      return;
    }
    await page.keyboard.press(backwards ? 'Shift+Tab' : 'Tab');
  }
  throw new Error(`Could not reach ${target.toString()} with ${maxPresses} ${backwards ? 'Shift+Tab' : 'Tab'} presses`);
}

/** Tabs to `target` and activates it with `key` (Enter by default; Space for buttons is also fine). */
export async function tabAndPress(
  page: Page,
  target: Locator,
  key: 'Enter' | 'Space' = 'Enter',
  options: TabToOptions = {},
): Promise<void> {
  await tabTo(page, target, options);
  await page.keyboard.press(key);
}

/**
 * Follows a link in the "Main" navigation by keyboard: Shift+Tab back from the page content into
 * the header, press Enter, and expect focus on the new page's `h1` (contracts/ui-accessibility.md).
 */
export async function navigateByKeyboard(page: Page, linkName: string, expectedHeading: string = linkName): Promise<void> {
  const link = page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: linkName, exact: true });
  await tabTo(page, link, { backwards: true });
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { level: 1, name: expectedHeading })).toBeFocused();
}

/**
 * Replaces the value of the focused text field with the keyboard: select all, then type.
 * Typing into an empty selection is the same as deleting it.
 */
export async function replaceFocusedText(page: Page, text: string): Promise<void> {
  await page.keyboard.press('ControlOrMeta+a');
  if (text === '') {
    await page.keyboard.press('Delete');
  } else {
    await page.keyboard.type(text);
  }
}
