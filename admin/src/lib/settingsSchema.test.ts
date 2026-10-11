// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { mockSettingsSchema } from "../mocks/data";
import { changedSettings, enumOptions, jsonEqual, settingsField, settingsFields } from "./settingsSchema";

describe("settingsFields", () => {
  it("maps each property to a field, in order", () => {
    const fields = settingsFields(mockSettingsSchema);
    expect(fields.map((f) => [f.key, f.kind])).toEqual([
      ["preferredQuality", "enum"],
      ["showAdult", "boolean"],
      ["mirror", "string"],
      ["languages", "multiEnum"],
      ["advanced", "json"],
    ]);
    expect(fields[0]).toMatchObject({ title: "Image quality", description: "Quality of the page images." });
  });

  it("returns nothing for a schema without properties", () => {
    expect(settingsFields({})).toEqual([]);
    expect(settingsFields(null)).toEqual([]);
    expect(settingsFields({ type: "object", properties: {} })).toEqual([]);
  });

  it("falls back to JSON for types it has no input for", () => {
    expect(settingsField("n", { type: "integer" }).kind).toBe("json");
    expect(settingsField("a", { type: "array", items: { type: "string" } }).kind).toBe("json");
    expect(settingsField("x", {}).kind).toBe("json");
    expect(settingsField("x", "nonsense").kind).toBe("json");
  });

  it("uses the key as title and handles nullable types", () => {
    expect(settingsField("token", { type: ["string", "null"] })).toMatchObject({ kind: "string", title: "token" });
  });

  it("marks read-only and multiline strings", () => {
    expect(settingsField("s", { type: "string", readOnly: true, format: "textarea" })).toMatchObject({
      readOnly: true,
      multiline: true,
    });
  });
});

describe("enumOptions", () => {
  it("labels enum values with x-enumNames", () => {
    expect(enumOptions({ enum: ["a", "b"], "x-enumNames": ["A"] })).toEqual([
      { value: "a", label: "A" },
      { value: "b", label: "b" },
    ]);
  });

  it("reads oneOf consts with titles", () => {
    expect(enumOptions({ oneOf: [{ const: 1, title: "One" }, { const: 2 }] })).toEqual([
      { value: 1, label: "One" },
      { value: 2, label: "2" },
    ]);
  });

  it("isn't an enum when an alternative has no const", () => {
    expect(enumOptions({ oneOf: [{ const: 1 }, { type: "string" }] })).toBeNull();
  });
});

describe("changedSettings", () => {
  it("returns only changed keys, comparing structurally", () => {
    const saved = { a: "x", b: ["en", "fr"], c: { deep: [1, 2] }, d: true };
    const draft = { a: "x", b: ["en", "fr"], c: { deep: [1, 3] }, d: false, e: "new" };
    expect(changedSettings(saved, draft)).toEqual({ c: { deep: [1, 3] }, d: false, e: "new" });
  });

  it("treats a reordered array as a change", () => {
    expect(changedSettings({ b: ["en", "fr"] }, { b: ["fr", "en"] })).toEqual({ b: ["fr", "en"] });
  });
});

describe("jsonEqual", () => {
  it("ignores key order", () => {
    expect(jsonEqual({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true);
    expect(jsonEqual({ a: 1 }, { a: 1, b: undefined })).toBe(false);
    expect(jsonEqual(null, {})).toBe(false);
  });
});
