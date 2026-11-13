import { expect, test } from '@playwright/test';
import { expectNoAxeViolations } from './support/a11y';
import { DEMO_ACCOUNTS, signIn, signOut, type DemoRole } from './support/accounts';

// Foundational checkpoint: every demo account signs in and out, and the sign-in states pass axe.

test('sign-in page states have no WCAG 2.1 A/AA violations', async ({ page }) => {
  await page.goto('/sign-in');
  await expect(page.getByRole('heading', { level: 1, name: 'Sign in' })).toBeVisible();
  await expectNoAxeViolations(page, { label: 'sign-in – empty' });

  await page.getByRole('button', { name: 'Sign in' }).press('Enter');
  await expect(page.getByRole('group', { name: 'There is a problem' })).toBeFocused();
  await expectNoAxeViolations(page, { label: 'sign-in – validation errors' });
});

test('a wrong password does not reveal whether the account exists', async ({ page }) => {
  const messages: string[] = [];
  for (const email of [DEMO_ACCOUNTS.creator.email, 'nobody@demo.invalid']) {
    await page.goto('/sign-in');
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password').fill('definitely-wrong-password');
    await page.getByLabel('Password').press('Enter');
    const summary = page.getByRole('group', { name: 'There is a problem' });
    await expect(summary).toBeFocused();
    messages.push((await summary.textContent()) ?? '');
  }
  expect(messages[0]).toBe(messages[1]);
  await expectNoAxeViolations(page, { label: 'sign-in – rejected' });
});

for (const role of Object.keys(DEMO_ACCOUNTS) as DemoRole[]) {
  test(`${role} signs in and out with the keyboard`, async ({ page }) => {
    await signIn(page, role);
    await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
    await expectNoAxeViolations(page, { label: `home – ${role}` });

    // The session is tab-scoped: nothing account-related lands in localStorage.
    expect(await page.evaluate(() => Object.keys(window.localStorage))).toEqual([]);

    await signOut(page);
    expect(new URL(page.url()).search).toBe('');
  });
}
