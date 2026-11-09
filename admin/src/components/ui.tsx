// SPDX-License-Identifier: GPL-3.0-or-later

import { type ReactNode, useId, useRef } from "react";
import { errorMessage } from "../api/errors";

export function Loading({ label = "Loading…" }: { label?: string }) {
  return (
    <p className="muted" role="status">
      <span className="spinner" aria-hidden="true" /> {label}
    </p>
  );
}

/** Shows an API error's message. Renders nothing without an error. */
export function ErrorBox({ error, onRetry, title }: { error: unknown; onRetry?: () => void; title?: string }) {
  if (!error) return null;
  return (
    <div className="error-box" role="alert">
      <div>
        {title ? <strong>{title}: </strong> : null}
        {errorMessage(error)}
      </div>
      {onRetry ? (
        <button type="button" className="small" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </div>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

export type Tone = "neutral" | "good" | "warn" | "bad" | "info";

export function Badge({ tone = "neutral", children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  );
}

export function Field({ label, hint, children }: { label: ReactNode; hint?: ReactNode; children: ReactNode }) {
  return (
    <div className="field">
      {/* biome-ignore lint/a11y/noLabelWithoutControl: the control is passed as children. */}
      <label className="field-wrap">
        <span className="field-label">{label}</span>
        {children}
      </label>
      {hint ? <span className="field-hint">{hint}</span> : null}
    </div>
  );
}

export function PageHeader({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-header">
      <h1>{title}</h1>
      {children ? <div className="page-actions">{children}</div> : null}
    </header>
  );
}

export function Mono({ children, title }: { children: ReactNode; title?: string }) {
  return (
    <code className="mono" title={title}>
      {children}
    </code>
  );
}

/**
 * A labelled search field with a clear (×) button, shown only while it has a value. Escape clears it too.
 * The label sits beside the field rather than around it, so the button's name stays out of the field's name.
 */
export function SearchInput({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const clear = () => {
    onChange("");
    input.current?.focus();
  };
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      <div className="search-input">
        <input
          ref={input}
          id={id}
          type="search"
          value={value}
          placeholder={placeholder}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape" && value) {
              event.preventDefault();
              clear();
            }
          }}
        />
        {value ? (
          <button type="button" className="search-clear" aria-label="Clear search" title="Clear search" onClick={clear}>
            <span aria-hidden="true">×</span>
          </button>
        ) : null}
      </div>
    </div>
  );
}
