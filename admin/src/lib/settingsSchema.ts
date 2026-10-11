// SPDX-License-Identifier: GPL-3.0-or-later

// Source settings: turns the JSON Schema of GET /v1/sources/{id}/settings/schema into
// form fields. Strings, booleans, single choices (enum) and multiple choices (arrays of
// enum) get proper inputs; anything else is edited as JSON.
import type { JsonObject } from "../api/types";

export type Option = { value: unknown; label: string };

type FieldBase = { key: string; title: string; description: string | undefined; readOnly: boolean };

export type SettingsField =
  | (FieldBase & { kind: "string"; multiline: boolean })
  | (FieldBase & { kind: "boolean" })
  | (FieldBase & { kind: "enum"; options: Option[] })
  | (FieldBase & { kind: "multiEnum"; options: Option[] })
  | (FieldBase & { kind: "json" });

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function str(value: unknown): string | undefined {
  return typeof value === "string" && value !== "" ? value : undefined;
}

/** The schema's type, ignoring "null" in a type list. */
function schemaType(schema: JsonObject): string | undefined {
  const type = schema.type;
  if (typeof type === "string") return type;
  if (Array.isArray(type)) return type.find((t): t is string => typeof t === "string" && t !== "null");
  return undefined;
}

/** Choices of an enum schema: `enum` (labels from `x-enumNames`/`enumNames`) or `oneOf`/`anyOf` of `const`s. */
export function enumOptions(schema: JsonObject): Option[] | null {
  if (Array.isArray(schema.enum)) {
    const names = Array.isArray(schema["x-enumNames"])
      ? schema["x-enumNames"]
      : Array.isArray(schema.enumNames)
        ? schema.enumNames
        : [];
    return schema.enum.map((value, i) => ({ value, label: str(names[i]) ?? String(value) }));
  }
  const alternatives = Array.isArray(schema.oneOf) ? schema.oneOf : Array.isArray(schema.anyOf) ? schema.anyOf : null;
  if (alternatives?.length && alternatives.every((alt) => isObject(alt) && "const" in alt)) {
    return (alternatives as JsonObject[]).map((alt) => ({
      value: alt.const,
      label: str(alt.title) ?? String(alt.const),
    }));
  }
  return null;
}

export function settingsField(key: string, schema: unknown): SettingsField {
  const property = isObject(schema) ? schema : {};
  const base: FieldBase = {
    key,
    title: str(property.title) ?? key,
    description: str(property.description),
    readOnly: property.readOnly === true,
  };
  const options = enumOptions(property);
  if (options) return { ...base, kind: "enum", options };
  const type = schemaType(property);
  if (type === "boolean") return { ...base, kind: "boolean" };
  if (type === "string") {
    const multiline = property.format === "textarea" || property["x-multiline"] === true;
    return { ...base, kind: "string", multiline };
  }
  if (type === "array" && isObject(property.items)) {
    const itemOptions = enumOptions(property.items);
    if (itemOptions) return { ...base, kind: "multiEnum", options: itemOptions };
  }
  return { ...base, kind: "json" };
}

/** One field per property of the settings object, in the schema's order. */
export function settingsFields(schema: unknown): SettingsField[] {
  if (!isObject(schema) || !isObject(schema.properties)) return [];
  return Object.entries(schema.properties).map(([key, property]) => settingsField(key, property));
}

/** Structural equality for JSON values. */
export function jsonEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((item, i) => jsonEqual(item, b[i]));
  }
  if (isObject(a) && isObject(b)) {
    const keys = Object.keys(a);
    return keys.length === Object.keys(b).length && keys.every((key) => key in b && jsonEqual(a[key], b[key]));
  }
  return false;
}

/** The entries of `draft` that differ from `saved`: the body of PATCH .../settings. */
export function changedSettings(saved: JsonObject, draft: JsonObject): JsonObject {
  const changes: JsonObject = {};
  for (const [key, value] of Object.entries(draft)) {
    if (!jsonEqual(saved[key], value)) changes[key] = value;
  }
  return changes;
}

/** The value a field starts with when the source returned none. */
export function emptyValue(field: SettingsField): unknown {
  switch (field.kind) {
    case "string":
      return "";
    case "boolean":
      return false;
    case "multiEnum":
      return [];
    default:
      return null;
  }
}
