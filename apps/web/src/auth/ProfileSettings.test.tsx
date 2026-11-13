import { createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { axe } from 'vitest-axe';
import { App } from '../app';
import { CREATOR, createTestApp, type FakeAccount } from '../test/fakeAuth';
import { browserTimeZone, supportedTimeZones, validateProfile } from './ProfileSettings';
import { PROFILE_SAVE_FAILED_MESSAGE } from './session';

async function renderAt(path: string, account: FakeAccount) {
  const app = await createTestApp({ signedInAs: account, history: createMemoryHistory({ initialEntries: [path] }) });
  const view = render(<App queryClient={app.queryClient} router={app.router} />);
  await screen.findByRole('heading', { level: 1 });
  return { ...app, ...view };
}

describe('ProfileSettings', () => {
  it('shows labeled fields with the stored values and no axe violations', async () => {
    const { container } = await renderAt('/profile', CREATOR);

    expect(screen.getByRole('heading', { level: 1, name: 'Profile settings' })).toBeInTheDocument();
    expect(document.title).toBe('Profile settings – Tasks');
    expect(screen.getByLabelText('Display name (required)')).toHaveValue('Casey Creator');
    const zone = screen.getByLabelText('Time zone (required)');
    expect(zone.tagName).toBe('SELECT');
    expect(zone).toHaveValue('Europe/Berlin');
    expect(screen.getByRole('link', { name: 'Profile' })).toHaveAttribute('aria-current', 'page');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('saves through the Auth user-update API and announces the outcome', async () => {
    const user = userEvent.setup();
    const { fake } = await renderAt('/profile', CREATOR);

    await user.clear(screen.getByLabelText('Display name (required)'));
    await user.type(screen.getByLabelText('Display name (required)'), '  Casey C.  ');
    await user.selectOptions(screen.getByLabelText('Time zone (required)'), 'Asia/Tokyo');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));

    expect(fake.auth.updateUser).toHaveBeenCalledWith({ data: { display_name: 'Casey C.', time_zone: 'Asia/Tokyo' } });
    await waitFor(() => expect(screen.getByTestId('live-region')).toHaveTextContent('Profile saved.'));
    expect(screen.getByText('Signed in as Casey C.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save profile' })).toHaveFocus();
  });

  it('reports an empty name through the error summary', async () => {
    const user = userEvent.setup();
    const { fake, container } = await renderAt('/profile', CREATOR);

    await user.clear(screen.getByLabelText('Display name (required)'));
    await user.click(screen.getByRole('button', { name: 'Save profile' }));

    expect(screen.getByRole('group', { name: 'There is a problem' })).toHaveFocus();
    const name = screen.getByLabelText('Display name (required)');
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription(expect.stringContaining('Enter a display name'));
    expect(fake.auth.updateUser).not.toHaveBeenCalled();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('reports a rejected stored zone visibly and requires a valid choice', async () => {
    const user = userEvent.setup();
    const account = { ...CREATOR, metadata: { display_name: 'Casey Creator', time_zone: 'Mars/Olympus_Mons' } };
    const { fake, container } = await renderAt('/profile', account);

    const zone = screen.getByLabelText('Time zone (required)');
    expect(zone).toHaveValue('Mars/Olympus_Mons');
    expect(zone).toHaveAttribute('aria-invalid', 'true');
    expect(zone).toHaveAccessibleDescription(expect.stringContaining('is not a recognised time zone'));
    expect(await axe(container)).toHaveNoViolations();

    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(fake.auth.updateUser).not.toHaveBeenCalled();

    await user.selectOptions(zone, 'Europe/Lisbon');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));
    expect(fake.auth.updateUser).toHaveBeenCalledWith({
      data: { display_name: 'Casey Creator', time_zone: 'Europe/Lisbon' },
    });
    expect(zone).not.toHaveAttribute('aria-invalid');
  });

  it('keeps the form and shows an error when Auth rejects the update', async () => {
    const user = userEvent.setup();
    const { fake } = await renderAt('/profile', CREATOR);
    fake.state.updateError = 'rejected';

    await user.selectOptions(screen.getByLabelText('Time zone (required)'), 'Asia/Tokyo');
    await user.click(screen.getByRole('button', { name: 'Save profile' }));

    const summary = await screen.findByRole('group', { name: 'There is a problem' });
    expect(summary).toHaveTextContent(PROFILE_SAVE_FAILED_MESSAGE);
    expect(summary).toHaveFocus();
    expect(screen.getByLabelText('Time zone (required)')).toHaveValue('Asia/Tokyo');
  });

  it('validates names longer than 200 characters', () => {
    expect(validateProfile('x'.repeat(201), 'UTC', ['UTC'])).toEqual([
      { fieldId: 'profile-display-name', message: 'Display name must be 200 characters or fewer' },
    ]);
    expect(validateProfile('x'.repeat(200), 'UTC', ['UTC'])).toEqual([]);
    expect(supportedTimeZones()).toContain('UTC');
  });
});

describe('first sign-in time-zone offer', () => {
  const detected = browserTimeZone() ?? 'UTC';
  const withoutZone: FakeAccount = { ...CREATOR, metadata: { display_name: 'Casey Creator' } };

  it('offers the browser zone and saves it only on confirmation', async () => {
    const user = userEvent.setup();
    const { fake, container } = await renderAt('/', withoutZone);

    const offer = screen.getByRole('region', { name: 'Confirm your time zone' });
    expect(offer).toHaveTextContent(detected);
    expect(fake.auth.updateUser).not.toHaveBeenCalled();
    expect(await axe(container)).toHaveNoViolations();

    await user.click(within(offer).getByRole('button', { name: `Use ${detected}` }));

    expect(fake.auth.updateUser).toHaveBeenCalledWith({
      data: { display_name: 'Casey Creator', time_zone: detected },
    });
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Confirm your time zone' })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('live-region')).toHaveTextContent(`Time zone set to ${detected}.`));
  });

  it('can be dismissed or replaced by a manual choice', async () => {
    const user = userEvent.setup();
    await renderAt('/', withoutZone);

    await user.click(screen.getByRole('link', { name: 'Choose another time zone' }));
    await screen.findByRole('heading', { level: 1, name: 'Profile settings' });
    expect(screen.getByLabelText('Time zone (required)')).toHaveValue(detected);
    expect(screen.getByLabelText('Time zone (required)')).toHaveAccessibleDescription(
      'Detected from your browser. Save to confirm it.',
    );

    await user.click(screen.getByRole('button', { name: 'Not now' }));
    expect(screen.queryByRole('region', { name: 'Confirm your time zone' })).not.toBeInTheDocument();
  });

  it('is not shown once a zone is chosen', async () => {
    await renderAt('/', CREATOR);
    expect(screen.queryByRole('region', { name: 'Confirm your time zone' })).not.toBeInTheDocument();
  });
});
