import { useEffect, useId, useRef, useState } from 'react';

export interface ComboboxOption {
  id: string;
  label: string;
  /** Secondary text, e.g. an email next to a display name. */
  detail?: string;
}

export interface ComboboxProps {
  label: string;
  /** Loads matches for a trimmed, non-empty query. Rejecting means the search failed. */
  search: (query: string, signal: AbortSignal) => Promise<ComboboxOption[]>;
  onSelect: (option: ComboboxOption | null) => void;
  selected?: ComboboxOption | null;
  required?: boolean;
  /** Ids of external messages (hint, field error) for aria-describedby. */
  describedBy?: string;
  invalid?: boolean;
  /** Input id, so an ErrorSummary link can target the field. */
  id?: string;
  debounceMs?: number;
}

type SearchState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'results'; options: ComboboxOption[] }
  | { kind: 'failed' };

export function resultCountMessage(count: number): string {
  return count === 1 ? '1 result available.' : `${count} results available.`;
}

export const NO_MATCHES_MESSAGE = 'No matches.';
export const SEARCH_FAILED_MESSAGE = 'Search failed. Try again.';

/**
 * APG editable combobox with list autocomplete. Results, "no matches" and failures are
 * distinct messages in the combobox's own polite status, so a screen reader can tell an
 * empty directory from an outage. Stale searches are aborted.
 */
export function Combobox({
  label,
  search,
  onSelect,
  selected = null,
  required = false,
  describedBy,
  invalid = false,
  id,
  debounceMs = 250,
}: ComboboxProps) {
  const generatedId = useId();
  const inputId = id ?? `${generatedId}-input`;
  const listboxId = `${generatedId}-listbox`;
  const statusId = `${generatedId}-status`;
  const [text, setText] = useState(selected?.label ?? '');
  const [state, setState] = useState<SearchState>({ kind: 'idle' });
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const searchRef = useRef(search);
  searchRef.current = search;

  useEffect(() => {
    setText(selected?.label ?? '');
  }, [selected?.id, selected?.label]);

  const query = text.trim();
  const isSelectedText = selected !== null && text === selected.label;

  useEffect(() => {
    if (!open || query === '' || isSelectedText) {
      setState({ kind: 'idle' });
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      setState({ kind: 'loading' });
      searchRef.current(query, controller.signal).then(
        (options) => {
          if (controller.signal.aborted) return;
          setState({ kind: 'results', options });
          setActive(-1);
        },
        () => {
          if (!controller.signal.aborted) setState({ kind: 'failed' });
        },
      );
    }, debounceMs);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, open, isSelectedText, debounceMs]);

  const options = state.kind === 'results' ? state.options : [];
  const expanded = open && options.length > 0;
  const status =
    state.kind === 'loading'
      ? 'Searching…'
      : state.kind === 'failed'
        ? SEARCH_FAILED_MESSAGE
        : state.kind === 'results'
          ? options.length === 0
            ? NO_MATCHES_MESSAGE
            : resultCountMessage(options.length)
          : '';

  function choose(option: ComboboxOption) {
    onSelect(option);
    setText(option.label);
    setOpen(false);
    setActive(-1);
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        setOpen(true);
        if (options.length > 0) setActive((i) => (i + 1) % options.length);
        break;
      case 'ArrowUp':
        event.preventDefault();
        if (options.length > 0) setActive((i) => (i <= 0 ? options.length - 1 : i - 1));
        break;
      case 'Enter': {
        const option = expanded ? options[active] : undefined;
        if (option) {
          event.preventDefault();
          choose(option);
        }
        break;
      }
      case 'Escape':
        if (expanded) {
          event.preventDefault();
          event.stopPropagation();
          setOpen(false);
          setActive(-1);
        } else if (text !== '') {
          event.preventDefault();
          setText('');
          onSelect(null);
        }
        break;
    }
  }

  const activeOption = expanded && active >= 0 ? options[active] : undefined;
  const describedByIds = [statusId, describedBy].filter(Boolean).join(' ');

  return (
    <div className="combobox">
      <label htmlFor={inputId}>{required ? `${label} (required)` : label}</label>
      <input
        id={inputId}
        type="text"
        role="combobox"
        autoComplete="off"
        aria-autocomplete="list"
        aria-expanded={expanded}
        aria-controls={listboxId}
        aria-activedescendant={activeOption ? `${listboxId}-${activeOption.id}` : undefined}
        aria-describedby={describedByIds}
        aria-invalid={invalid || undefined}
        aria-required={required || undefined}
        value={text}
        onChange={(event) => {
          setText(event.target.value);
          setOpen(true);
          if (selected) onSelect(null);
        }}
        onKeyDown={onKeyDown}
        onBlur={() => setOpen(false)}
      />
      <ul id={listboxId} role="listbox" aria-label={label} className="combobox-listbox" hidden={!expanded}>
        {options.map((option, index) => (
          <li
            key={option.id}
            id={`${listboxId}-${option.id}`}
            role="option"
            aria-selected={index === active}
            className={index === active ? 'combobox-option is-active' : 'combobox-option'}
            // Keep focus in the input when choosing with a pointer.
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => choose(option)}
          >
            {option.label}
            {option.detail ? <span className="combobox-option-detail"> {option.detail}</span> : null}
          </li>
        ))}
      </ul>
      <p id={statusId} role="status" aria-live="polite" className="combobox-status">
        {status}
      </p>
    </div>
  );
}
