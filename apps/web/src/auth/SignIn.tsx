import { useState, type FormEvent } from 'react';
import { ErrorSummary, type SummaryError } from '../a11y/ErrorSummary';
import type { SessionController } from './session';

const EMAIL_ID = 'sign-in-email';
const PASSWORD_ID = 'sign-in-password';

export interface SignInProps {
  session: SessionController;
  /** Called after a successful sign-in; the caller navigates to the validated target. */
  onSignedIn: () => void;
}

/**
 * Email/password sign-in. There is no account creation or recovery here (user management
 * owns accounts). Failures use one message that never reveals whether an account exists.
 */
export function SignIn({ session, onSignedIn }: SignInProps) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<SummaryError[]>([]);
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState(false);

  const fieldError = (fieldId: string) => errors.find((e) => e.fieldId === fieldId);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;
    const validation: SummaryError[] = [];
    if (email.trim() === '') validation.push({ fieldId: EMAIL_ID, message: 'Enter your email address' });
    if (password === '') validation.push({ fieldId: PASSWORD_ID, message: 'Enter your password' });
    setAttempt((n) => n + 1);
    if (validation.length > 0) {
      setErrors(validation);
      return;
    }

    setPending(true);
    setErrors([]);
    const result = await session.signIn(email.trim(), password);
    setPending(false);
    if (result.ok) {
      onSignedIn();
      return;
    }
    setPassword('');
    setErrors([{ fieldId: EMAIL_ID, message: result.message }]);
  }

  const emailMessage = fieldError(EMAIL_ID);
  const passwordMessage = fieldError(PASSWORD_ID);

  return (
    <>
      <h1>Sign in</h1>
      <ErrorSummary errors={errors} focusKey={attempt} />
      <form className="stack" noValidate onSubmit={onSubmit} aria-label="Sign in">
        <div className="field">
          <label htmlFor={EMAIL_ID}>Email address</label>
          <input
            id={EMAIL_ID}
            name="email"
            type="email"
            autoComplete="username"
            inputMode="email"
            spellCheck={false}
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            aria-invalid={emailMessage ? true : undefined}
            aria-describedby={emailMessage ? `${EMAIL_ID}-error` : undefined}
          />
          {emailMessage ? (
            <p id={`${EMAIL_ID}-error`} className="field-error">
              {emailMessage.message}
            </p>
          ) : null}
        </div>
        <div className="field">
          <label htmlFor={PASSWORD_ID}>Password</label>
          <input
            id={PASSWORD_ID}
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            aria-invalid={passwordMessage ? true : undefined}
            aria-describedby={passwordMessage ? `${PASSWORD_ID}-error` : undefined}
          />
          {passwordMessage ? (
            <p id={`${PASSWORD_ID}-error`} className="field-error">
              {passwordMessage.message}
            </p>
          ) : null}
        </div>
        <button type="submit" className="primary" aria-disabled={pending || undefined}>
          {pending ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </>
  );
}
