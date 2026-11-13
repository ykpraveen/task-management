import { useEffect, useId, useRef } from 'react';

export interface SummaryError {
  /** Id of the invalid field; the summary links to it. */
  fieldId: string;
  message: string;
}

export interface ErrorSummaryProps {
  errors: SummaryError[];
  title?: string;
  /** Change this (e.g. the submit count) to move focus to the summary again on a new attempt. */
  focusKey?: number | string;
}

/**
 * Validation summary that receives focus after a failed submit and links to each field.
 * Fields themselves carry aria-invalid and aria-describedby pointing at their own message.
 */
export function ErrorSummary({ errors, title = 'There is a problem', focusKey }: ErrorSummaryProps) {
  const headingId = useId();
  const ref = useRef<HTMLDivElement>(null);
  const hasErrors = errors.length > 0;

  useEffect(() => {
    if (hasErrors) ref.current?.focus();
  }, [hasErrors, focusKey]);

  if (!hasErrors) return null;

  return (
    <div ref={ref} className="error-summary" tabIndex={-1} role="group" aria-labelledby={headingId}>
      <h2 id={headingId} className="error-summary-title">
        {title}
      </h2>
      <ul>
        {errors.map((error) => (
          <li key={`${error.fieldId}:${error.message}`}>
            <a
              href={`#${error.fieldId}`}
              onClick={(event) => {
                const field = document.getElementById(error.fieldId);
                if (field) {
                  event.preventDefault();
                  field.focus();
                }
              }}
            >
              {error.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
