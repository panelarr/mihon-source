// SPDX-License-Identifier: GPL-3.0-or-later

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useMemo, useState } from "react";
import { api, unwrap } from "../../api/client";
import { asStruct, type JsonObject } from "../../api/types";
import { Empty, ErrorBox, Loading } from "../../components/ui";
import { changedSettings, emptyValue, type SettingsField, settingsFields } from "../../lib/settingsSchema";
import { settingsQuery, settingsSchemaQuery } from "./queries";

/** The settings form generated from the source's JSON Schema. */
export function SettingsPanel({ sourceId }: { sourceId: string }) {
  const schema = useQuery(settingsSchemaQuery(sourceId));
  const values = useQuery(settingsQuery(sourceId));

  if (schema.isPending || values.isPending) return <Loading />;
  if (schema.isError) return <ErrorBox error={schema.error} title="Settings schema" onRetry={() => schema.refetch()} />;
  if (values.isError) return <ErrorBox error={values.error} title="Settings" onRetry={() => values.refetch()} />;
  return <SettingsForm sourceId={sourceId} schema={schema.data} saved={values.data} />;
}

function SettingsForm({ sourceId, schema, saved }: { sourceId: string; schema: JsonObject; saved: JsonObject }) {
  const queryClient = useQueryClient();
  const fields = useMemo(() => settingsFields(schema), [schema]);
  const [draft, setDraft] = useState<JsonObject>(saved);
  // JSON fields are edited as text and parsed on save.
  const [jsonText, setJsonText] = useState<Record<string, string>>({});
  const [savedNote, setSavedNote] = useState(false);

  const save = useMutation({
    mutationFn: async () => {
      const next = { ...draft };
      for (const [key, text] of Object.entries(jsonText)) {
        try {
          next[key] = JSON.parse(text);
        } catch {
          throw new Error(`"${key}" isn't valid JSON.`);
        }
      }
      const changes = changedSettings(saved, next);
      if (Object.keys(changes).length === 0) throw new Error("Nothing changed.");
      return unwrap(
        api.PATCH("/v1/sources/{sourceId}/settings", {
          params: { path: { sourceId } },
          body: { values: asStruct(changes) },
        }),
      );
    },
    onSuccess: (response) => {
      const values = (response.values ?? {}) as JsonObject;
      queryClient.setQueryData(settingsQuery(sourceId).queryKey, values);
      setDraft(values);
      setJsonText({});
      setSavedNote(true);
    },
  });

  if (fields.length === 0) return <Empty>This source has no settings.</Empty>;

  const set = (key: string, value: unknown) => {
    setSavedNote(false);
    setDraft((prev) => ({ ...prev, [key]: value }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    save.mutate();
  };

  return (
    <form className="card settings-form" onSubmit={submit}>
      {fields.map((field) => (
        <SettingInput
          key={field.key}
          field={field}
          value={field.key in draft ? draft[field.key] : emptyValue(field)}
          jsonText={jsonText[field.key]}
          onChange={(value) => set(field.key, value)}
          onJsonText={(text) => {
            setSavedNote(false);
            setJsonText((prev) => ({ ...prev, [field.key]: text }));
          }}
        />
      ))}
      <ErrorBox error={save.error} />
      <div className="actions">
        <button type="submit" className="primary" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save"}
        </button>
        {savedNote && !save.isPending ? <span className="success">Saved.</span> : null}
      </div>
    </form>
  );
}

function SettingInput({
  field,
  value,
  jsonText,
  onChange,
  onJsonText,
}: {
  field: SettingsField;
  value: unknown;
  jsonText: string | undefined;
  onChange: (value: unknown) => void;
  onJsonText: (text: string) => void;
}) {
  const id = `setting-${field.key}`;
  const hint = field.description ? <span className="field-hint">{field.description}</span> : null;

  switch (field.kind) {
    case "boolean":
      return (
        <div className="field">
          <label className="filter-check" htmlFor={id}>
            <input
              id={id}
              type="checkbox"
              checked={value === true}
              disabled={field.readOnly}
              onChange={(event) => onChange(event.target.checked)}
            />
            <span>{field.title}</span>
          </label>
          {hint}
        </div>
      );
    case "string":
      return (
        <div className="field">
          <label className="field-label" htmlFor={id}>
            {field.title}
          </label>
          {field.multiline ? (
            <textarea
              id={id}
              value={typeof value === "string" ? value : ""}
              readOnly={field.readOnly}
              onChange={(event) => onChange(event.target.value)}
            />
          ) : (
            <input
              id={id}
              type="text"
              value={typeof value === "string" ? value : ""}
              readOnly={field.readOnly}
              onChange={(event) => onChange(event.target.value)}
            />
          )}
          {hint}
        </div>
      );
    case "enum": {
      const index = field.options.findIndex((option) => option.value === value);
      return (
        <div className="field">
          <label className="field-label" htmlFor={id}>
            {field.title}
          </label>
          <select
            id={id}
            value={index}
            disabled={field.readOnly}
            onChange={(event) => onChange(field.options[Number(event.target.value)]?.value ?? null)}
          >
            {index === -1 ? <option value={-1}>(not set)</option> : null}
            {field.options.map((option, i) => (
              <option key={String(option.value)} value={i}>
                {option.label}
              </option>
            ))}
          </select>
          {hint}
        </div>
      );
    }
    case "multiEnum": {
      const selected = Array.isArray(value) ? value : [];
      const toggle = (optionValue: unknown, on: boolean) =>
        onChange(
          on
            ? // Keep the schema's order.
              field.options.map((o) => o.value).filter((v) => v === optionValue || selected.includes(v))
            : selected.filter((v) => v !== optionValue),
        );
      return (
        <fieldset className="field">
          <legend className="field-label">{field.title}</legend>
          <div className="checks">
            {field.options.map((option) => (
              <label key={String(option.value)} className="filter-check">
                <input
                  type="checkbox"
                  checked={selected.includes(option.value)}
                  disabled={field.readOnly}
                  onChange={(event) => toggle(option.value, event.target.checked)}
                />
                <span>{option.label}</span>
              </label>
            ))}
          </div>
          {hint}
        </fieldset>
      );
    }
    case "json":
      return (
        <div className="field">
          <label className="field-label" htmlFor={id}>
            {field.title} <span className="muted small">(JSON)</span>
          </label>
          <textarea
            id={id}
            className="mono"
            rows={4}
            value={jsonText ?? JSON.stringify(value ?? null, null, 2)}
            readOnly={field.readOnly}
            onChange={(event) => onJsonText(event.target.value)}
          />
          {hint}
        </div>
      );
  }
}
