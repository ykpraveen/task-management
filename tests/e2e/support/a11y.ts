import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/** WCAG 2.1 Level A and AA rules (contracts/ui-accessibility.md). */
export const WCAG_21_AA_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] as const;

export interface AxeCheckOptions {
  /** Restrict the scan to a selector, e.g. an open dialog: `[role="dialog"]`. */
  include?: string;
  /** Human-readable state name used in the failure message ("task form – validation errors"). */
  label?: string;
}

/**
 * Runs axe-core with the WCAG 2.1 A/AA tags against the current page state and fails on any
 * violation, whatever its impact. Call it on every page and every open dialog of a workflow.
 * Automated results are not proof of conformance; manual reviews are recorded separately.
 */
export async function expectNoAxeViolations(page: Page, options: AxeCheckOptions = {}): Promise<void> {
  let builder = new AxeBuilder({ page }).withTags([...WCAG_21_AA_TAGS]);
  if (options.include) builder = builder.include(options.include);
  const results = await builder.analyze();

  const summary = results.violations.map((violation) => {
    const targets = violation.nodes.map((node) => `    - ${node.target.join(' ')}: ${node.failureSummary ?? ''}`.trim());
    return `[${violation.impact ?? 'unknown'}] ${violation.id}: ${violation.help} (${violation.helpUrl})\n${targets.join('\n')}`;
  });

  expect(
    results.violations,
    `axe found ${results.violations.length} WCAG 2.1 A/AA violation(s) in ${options.label ?? page.url()}:\n${summary.join('\n')}`,
  ).toEqual([]);
}
