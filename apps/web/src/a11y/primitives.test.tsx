import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import tokensCss from '../styles/tokens.css?raw';
import { Combobox, NO_MATCHES_MESSAGE, SEARCH_FAILED_MESSAGE, type ComboboxOption } from './Combobox';
import { Dialog } from './Dialog';
import { ErrorSummary } from './ErrorSummary';
import { LiveRegionProvider, NOTIFICATION_COUNT_INTERVAL_MS, useAnnouncer, type Announcer } from './LiveRegion';
import { OverdueText, PriorityText, StatusText, UnreadText } from './StatusText';

afterEach(() => {
  vi.useRealTimers();
});

// ---------------------------------------------------------------- Dialog

function DialogHarness({ withInitialFocus = false }: { withInitialFocus?: boolean }) {
  const [open, setOpen] = useState(false);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Mark blocked
      </button>
      <a href="#behind">Behind the dialog</a>
      <Dialog
        open={open}
        title="Why is this task blocked?"
        onClose={() => setOpen(false)}
        {...(withInitialFocus ? { initialFocusRef: reasonRef } : {})}
      >
        <button type="button" onClick={() => setOpen(false)}>
          Cancel
        </button>
        <label htmlFor="reason">Reason (required)</label>
        <textarea id="reason" ref={reasonRef} required />
        <button type="button">Save</button>
      </Dialog>
    </>
  );
}

describe('Dialog', () => {
  it('moves focus in, contains Tab, closes on Escape and returns focus', async () => {
    const user = userEvent.setup();
    render(<DialogHarness />);
    const invoker = screen.getByRole('button', { name: 'Mark blocked' });

    await user.click(invoker);
    const dialog = screen.getByRole('dialog', { name: 'Why is this task blocked?' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(cancel).toHaveFocus();

    await user.tab();
    expect(screen.getByLabelText('Reason (required)')).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    await user.tab();
    expect(cancel).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();

    // The page behind is inert while the dialog is open.
    expect(invoker.closest('[inert]')).not.toBeNull();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(invoker).toHaveFocus();
    expect(invoker.closest('[inert]')).toBeNull();
  });

  it('honors an initial focus target', async () => {
    const user = userEvent.setup();
    render(<DialogHarness withInitialFocus />);
    await user.click(screen.getByRole('button', { name: 'Mark blocked' }));
    expect(screen.getByLabelText('Reason (required)')).toHaveFocus();
  });

  it('has no axe violations when open', async () => {
    const user = userEvent.setup();
    render(<DialogHarness />);
    await user.click(screen.getByRole('button', { name: 'Mark blocked' }));
    expect(await axe(document.body)).toHaveNoViolations();
  });
});

// ---------------------------------------------------------------- Combobox

const PEOPLE: ComboboxOption[] = [
  { id: 'a', label: 'Ada Lovelace', detail: 'ada@demo.invalid' },
  { id: 'b', label: 'Alan Turing', detail: 'alan@demo.invalid' },
];

function ComboboxHarness({ search }: { search: (q: string, s: AbortSignal) => Promise<ComboboxOption[]> }) {
  const [selected, setSelected] = useState<ComboboxOption | null>(null);
  return (
    <form aria-label="Assign">
      <Combobox label="Assignee" required search={search} selected={selected} onSelect={setSelected} debounceMs={0} />
      <output data-testid="selected">{selected?.id ?? 'none'}</output>
    </form>
  );
}

describe('Combobox', () => {
  it('announces the result count and selects with the keyboard', async () => {
    const user = userEvent.setup();
    const search = vi.fn(async (q: string) => PEOPLE.filter((p) => p.label.toLowerCase().includes(q.toLowerCase())));
    const { container } = render(<ComboboxHarness search={search} />);

    const input = screen.getByRole('combobox', { name: 'Assignee (required)' });
    await user.type(input, 'a');
    expect(await screen.findByText('2 results available.')).toHaveAttribute('role', 'status');
    expect(input).toHaveAttribute('aria-expanded', 'true');
    expect(await axe(container)).toHaveNoViolations();

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(input).toHaveAttribute('aria-activedescendant', expect.stringContaining('-b'));
    expect(screen.getByRole('option', { name: /Alan Turing/ })).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{Enter}');
    expect(screen.getByTestId('selected')).toHaveTextContent('b');
    expect(input).toHaveValue('Alan Turing');
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(input).toHaveFocus();
  });

  it('distinguishes no matches from a failed search', async () => {
    const user = userEvent.setup();
    let fail = false;
    const search = vi.fn(async () => {
      if (fail) throw new Error('503');
      return [];
    });
    render(<ComboboxHarness search={search} />);
    const input = screen.getByRole('combobox', { name: 'Assignee (required)' });

    await user.type(input, 'zz');
    expect(await screen.findByText(NO_MATCHES_MESSAGE)).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-expanded', 'false');

    fail = true;
    await user.type(input, 'z');
    expect(await screen.findByText(SEARCH_FAILED_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByText(NO_MATCHES_MESSAGE)).not.toBeInTheDocument();
  });

  it('aborts stale searches and closes on Escape', async () => {
    const user = userEvent.setup();
    const signals: AbortSignal[] = [];
    const search = vi.fn(async (_q: string, signal: AbortSignal) => {
      signals.push(signal);
      return PEOPLE;
    });
    render(<ComboboxHarness search={search} />);
    const input = screen.getByRole('combobox', { name: 'Assignee (required)' });

    await user.type(input, 'ad');
    await screen.findByText('2 results available.');
    expect(signals.slice(0, -1).every((s) => s.aborted)).toBe(true);

    await user.keyboard('{Escape}');
    expect(input).toHaveAttribute('aria-expanded', 'false');
    await user.keyboard('{Escape}');
    expect(input).toHaveValue('');
  });
});

// ---------------------------------------------------------------- LiveRegion

function AnnouncerProbe({ onReady }: { onReady: (a: Announcer) => void }) {
  onReady(useAnnouncer());
  return null;
}

describe('LiveRegion', () => {
  it('renders one polite region and announces outcomes', async () => {
    let announcer!: Announcer;
    render(
      <LiveRegionProvider>
        <AnnouncerProbe onReady={(a) => (announcer = a)} />
      </LiveRegionProvider>,
    );
    const region = screen.getByTestId('live-region');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(screen.getAllByRole('status')).toHaveLength(1);

    act(() => announcer.announce('Task saved.'));
    await waitFor(() => expect(region).toHaveTextContent('Task saved.'));
  });

  it('rate-limits notification-count messages to one per 10 seconds, keeping the latest', () => {
    vi.useFakeTimers();
    let announcer!: Announcer;
    render(
      <LiveRegionProvider>
        <AnnouncerProbe onReady={(a) => (announcer = a)} />
      </LiveRegionProvider>,
    );
    const region = screen.getByTestId('live-region');

    act(() => announcer.announce('1 unread notification', 'notification-count'));
    act(() => vi.advanceTimersByTime(100));
    expect(region).toHaveTextContent('1 unread notification');

    act(() => {
      announcer.announce('2 unread notifications', 'notification-count');
      announcer.announce('3 unread notifications', 'notification-count');
    });
    act(() => vi.advanceTimersByTime(5_000));
    expect(region).toHaveTextContent('1 unread notification');

    act(() => vi.advanceTimersByTime(NOTIFICATION_COUNT_INTERVAL_MS));
    expect(region).toHaveTextContent('3 unread notifications');
  });
});

// ---------------------------------------------------------------- ErrorSummary

describe('ErrorSummary', () => {
  it('takes focus and links to each field', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <form aria-label="Task">
        <ErrorSummary errors={[{ fieldId: 'title', message: 'Enter a title' }]} />
        <label htmlFor="title">Title (required)</label>
        <input id="title" aria-invalid="true" aria-describedby="title-error" />
        <p id="title-error" className="field-error">
          Enter a title
        </p>
      </form>,
    );

    const summary = screen.getByRole('group', { name: 'There is a problem' });
    expect(summary).toHaveFocus();
    await user.click(screen.getByRole('link', { name: 'Enter a title' }));
    expect(screen.getByLabelText('Title (required)')).toHaveFocus();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('renders nothing without errors', () => {
    const { container } = render(<ErrorSummary errors={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});

// ---------------------------------------------------------------- StatusText

describe('StatusText', () => {
  it('exposes state as text', async () => {
    const { container } = render(
      <p>
        <StatusText status="in_progress" /> <PriorityText priority="high" /> <OverdueText overdue />{' '}
        <OverdueText overdue={false} /> <UnreadText unread /> <UnreadText unread={false} />
      </p>,
    );
    expect(container).toHaveTextContent('In progress High priority Overdue Unread Read');
    expect(await axe(container)).toHaveNoViolations();
  });
});

// ---------------------------------------------------------------- Tokens

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const [r, g, b] = channels.map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)) as [
    number,
    number,
    number,
  ];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

function tokens(block: string): Record<string, string> {
  return Object.fromEntries(Array.from(block.matchAll(/--color-([a-z-]+):\s*(#[0-9a-f]{6})/g), (m) => [m[1], m[2]]));
}

describe('design tokens', () => {
  const [light = '', dark = ''] = tokensCss.split('@media (prefers-color-scheme: dark)');

  it.each([
    ['light', light],
    ['dark', dark],
  ])('%s scheme meets 4.5:1 text and 3:1 non-text contrast', (_scheme, block) => {
    const t = tokens(block);
    for (const bg of ['bg', 'surface']) {
      for (const fg of ['text', 'text-muted', 'link', 'danger', 'success', 'warning']) {
        expect(contrast(t[fg]!, t[bg]!), `${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5);
      }
      for (const fg of ['border', 'focus']) {
        expect(contrast(t[fg]!, t[bg]!), `${fg} on ${bg}`).toBeGreaterThanOrEqual(3);
      }
    }
    expect(contrast(t['on-accent']!, t.accent!), 'on-accent').toBeGreaterThanOrEqual(4.5);
  });
});
