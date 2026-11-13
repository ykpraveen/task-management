import { createMemoryHistory } from '@tanstack/react-router';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { axe } from 'vitest-axe';
import indexHtml from '../../index.html?raw';
import { App } from '../app';
import { CREATOR, createTestApp } from '../test/fakeAuth';
import { documentTitle, focusPageHeading } from './RouteFocus';

async function renderApp(path = '/') {
  const { queryClient, router } = await createTestApp({
    signedInAs: CREATOR,
    history: createMemoryHistory({ initialEntries: [path] }),
  });
  const view = render(<App queryClient={queryClient} router={router} />);
  await screen.findByRole('heading', { level: 1 });
  return { router, ...view };
}

describe('app shell', () => {
  it('declares the document language', () => {
    expect(indexHtml).toMatch(/<html lang="en">/);
    // CSP allows only same-origin script files: no inline scripts.
    expect(indexHtml).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>/);
  });

  it('renders a skip link, landmarks and one h1', async () => {
    const { container } = await renderApp();

    const skip = screen.getByRole('link', { name: 'Skip to main content' });
    expect(skip).toHaveAttribute('href', '#main');
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'My tasks' })).toHaveAttribute('aria-current', 'page');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('sets the title on first load without moving focus', async () => {
    await renderApp();
    expect(document.title).toBe('My tasks – Tasks');
    expect(document.activeElement).toBe(document.body);
  });

  it('moves focus to the new h1 and updates the title after navigation', async () => {
    const { router, container } = await renderApp();

    await act(() => router.navigate({ to: '/missing' as '/' }));

    const heading = await screen.findByRole('heading', { level: 1, name: 'Page not found' });
    expect(heading).toHaveFocus();
    expect(heading).toHaveAttribute('tabindex', '-1');
    expect(document.title).toBe('Tasks');
    expect(await axe(container)).toHaveNoViolations();

    await act(() => router.navigate({ to: '/' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'My tasks' })).toHaveFocus();
    expect(document.title).toBe('My tasks – Tasks');
  });

  it('keeps focus when only the search changes', async () => {
    const { router } = await renderApp();
    const link = screen.getByRole('link', { name: 'My tasks' });
    link.focus();

    await act(() => router.navigate({ to: '/', search: { sort: 'dueDesc' } as never }));

    expect(link).toHaveFocus();
  });
});

describe('focusPageHeading', () => {
  it('reports when there is no heading to focus', () => {
    const root = document.createElement('div');
    expect(focusPageHeading(root)).toBe(false);
  });

  it('formats titles', () => {
    expect(documentTitle('Task 42')).toBe('Task 42 – Tasks');
    expect(documentTitle(undefined)).toBe('Tasks');
  });
});
