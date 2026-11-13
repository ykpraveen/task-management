import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

export type DemoRole = 'creator' | 'assignee' | 'replacement' | 'outsider';

export interface DemoAccount {
  role: DemoRole;
  email: string;
  displayName: string;
  timeZone: string;
}

/** Synthetic accounts created by `docker compose --profile tools run --rm demo-users`. */
export const DEMO_ACCOUNTS: Record<DemoRole, DemoAccount> = {
  creator: { role: 'creator', email: 'creator@demo.invalid', displayName: 'Casey Creator', timeZone: 'Europe/Berlin' },
  assignee: {
    role: 'assignee',
    email: 'assignee@demo.invalid',
    displayName: 'Avery Assignee',
    timeZone: 'America/New_York',
  },
  replacement: {
    role: 'replacement',
    email: 'replacement@demo.invalid',
    displayName: 'Riley Replacement',
    timeZone: 'Asia/Tokyo',
  },
  outsider: {
    role: 'outsider',
    email: 'outsider@demo.invalid',
    displayName: 'Oakley Outsider',
    timeZone: 'Australia/Sydney',
  },
};

/** The operator-supplied demo password; never logged or written to reports. */
export function demoPassword(): string {
  const password = process.env.DEMO_PASSWORD;
  if (!password) {
    throw new Error('DEMO_PASSWORD is not set. Use the password given to the demo-users tool.');
  }
  return password;
}

/**
 * Signs in through the real sign-in page using only the keyboard, then waits until the
 * signed-in shell shows the account. `target` is the page to open first; sign-in returns to it.
 */
export async function signIn(page: Page, role: DemoRole, target = '/'): Promise<void> {
  const account = DEMO_ACCOUNTS[role];
  await page.goto(target);
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible();

  await page.getByLabel('Email address').focus();
  await page.keyboard.type(account.email);
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Password')).toBeFocused();
  await page.keyboard.type(demoPassword());
  await page.keyboard.press('Enter');

  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByText(`Signed in as ${account.displayName}`)).toBeVisible();
}

export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible();
}

/**
 * An isolated browser context (separate tab-scoped session storage) signed in as `role`,
 * for multi-account scenarios such as creator/assignee/outsider privacy checks.
 */
export async function signedInContext(
  browser: Browser,
  role: DemoRole,
  target = '/',
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signIn(page, role, target);
  return { context, page };
}
