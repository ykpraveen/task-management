import { useRouter, useRouterState } from '@tanstack/react-router';
import { useEffect } from 'react';

export const APP_NAME = 'Tasks';

export function documentTitle(pageTitle: string | undefined): string {
  return pageTitle ? `${pageTitle} – ${APP_NAME}` : APP_NAME;
}

/** Sets the document title from a page that knows a more specific title than its route. */
export function useDocumentTitle(pageTitle: string | undefined): void {
  useEffect(() => {
    document.title = documentTitle(pageTitle);
  }, [pageTitle]);
}

/** Moves focus to the page's h1 so screen readers announce the new page. */
export function focusPageHeading(root: ParentNode = document): boolean {
  const heading = root.querySelector<HTMLElement>('main h1');
  if (!heading) {
    return false;
  }
  if (!heading.hasAttribute('tabindex')) {
    heading.setAttribute('tabindex', '-1');
  }
  heading.focus();
  return true;
}

/**
 * Route focus and title manager (contracts/ui-accessibility.md): after each navigation that
 * changes the path, update document.title from the matched route's `staticData.title` and move
 * focus to the page h1. The initial page load and search-only changes (filters, sort) keep
 * focus where it is.
 */
export function RouteFocus() {
  const router = useRouter();
  const routeTitle = useRouterState({
    select: (state) => state.matches.at(-1)?.staticData.title,
  });

  useEffect(() => {
    document.title = documentTitle(routeTitle);
  }, [routeTitle]);

  useEffect(
    () =>
      router.subscribe('onRendered', (event) => {
        if (event.fromLocation && event.pathChanged) {
          focusPageHeading();
        }
      }),
    [router],
  );

  return null;
}
