// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import type { Filter } from "../api/types";
import { mockFilters } from "../mocks/data";
import { defaultFilterState, defaultValue, filterKind, nextTriState, toFilterValues } from "./filters";

describe("filterKind", () => {
  it("finds the oneof member even when proto3 JSON left its fields out", () => {
    expect(filterKind({ name: "x", header: {} })).toBe("header");
    expect(filterKind({ name: "x", checkbox: {} })).toBe("checkbox");
    expect(filterKind({ name: "x", triState: {} })).toBe("triState");
    expect(filterKind({ name: "x", group: { filters: [] } })).toBe("group");
    expect(filterKind({ name: "x" })).toBeNull();
  });
});

describe("defaultValue", () => {
  it("fills in proto3 defaults", () => {
    expect(defaultValue({ text: {} })).toEqual({ kind: "text", value: "" });
    expect(defaultValue({ checkbox: {} })).toEqual({ kind: "checkbox", value: false });
    expect(defaultValue({ triState: {} })).toEqual({ kind: "triState", value: "TRI_STATE_IGNORE" });
    expect(defaultValue({ triState: { value: "TRI_STATE_UNSPECIFIED" } })).toEqual({
      kind: "triState",
      value: "TRI_STATE_IGNORE",
    });
    expect(defaultValue({ select: { options: ["a", "b"] } })).toEqual({ kind: "select", value: 0 });
    expect(defaultValue({ sort: { options: ["a"] } })).toEqual({ kind: "sort", value: null });
    expect(defaultValue({ sort: { options: ["a", "b"], selection: { index: 1 } } })).toEqual({
      kind: "sort",
      value: { index: 1, ascending: false },
    });
  });

  it("has no value for headers, separators and groups", () => {
    expect(defaultValue({ header: {} })).toBeNull();
    expect(defaultValue({ separator: {} })).toBeNull();
    expect(defaultValue({ group: { filters: [] } })).toBeNull();
  });
});

describe("toFilterValues", () => {
  it("sends nothing when nothing changed", () => {
    expect(toFilterValues(mockFilters, {})).toEqual([]);
    expect(toFilterValues(mockFilters, defaultFilterState(mockFilters))).toEqual([]);
  });

  it("sends only changed values, with their paths", () => {
    // mockFilters: 0 header, 1 text, 2 checkbox, 3 separator, 4 select, 5 sort, 6 group of tri-states, 7 group of checkboxes
    const state = {
      ...defaultFilterState(mockFilters),
      "1": { kind: "text", value: "someone" },
      "2": { kind: "checkbox", value: true },
      "4": { kind: "select", value: 2 },
      "5": { kind: "sort", value: { index: 2, ascending: true } },
      "6.0": { kind: "triState", value: "TRI_STATE_EXCLUDE" },
      "6.2": { kind: "triState", value: "TRI_STATE_INCLUDE" }, // the default: not sent
      "7.1": { kind: "checkbox", value: false },
    } as const;
    expect(toFilterValues(mockFilters, state)).toEqual([
      { path: [1], text: "someone" },
      { path: [2], checkbox: true },
      { path: [4], select: 2 },
      { path: [5], sort: { index: 2, ascending: true } },
      { path: [6, 0], triState: "TRI_STATE_EXCLUDE" },
      { path: [7, 1], checkbox: false },
    ]);
  });

  it("drops a value changed back to its default", () => {
    const state = { "2": { kind: "checkbox", value: false } } as const;
    expect(toFilterValues(mockFilters, state)).toEqual([]);
  });

  it("can't send a sort back to none, so leaves it out", () => {
    const filters: Filter[] = [
      { name: "Sort", sort: { options: ["a", "b"], selection: { index: 1, ascending: true } } },
    ];
    expect(toFilterValues(filters, { "0": { kind: "sort", value: null } })).toEqual([]);
    expect(toFilterValues(filters, { "0": { kind: "sort", value: { index: 1, ascending: false } } })).toEqual([
      { path: [0], sort: { index: 1, ascending: false } },
    ]);
  });

  it("sends a sort picked when the source had none", () => {
    const filters: Filter[] = [{ name: "Sort", sort: { options: ["a", "b"] } }];
    expect(toFilterValues(filters, { "0": { kind: "sort", value: { index: 0, ascending: false } } })).toEqual([
      { path: [0], sort: { index: 0, ascending: false } },
    ]);
  });

  it("ignores state of a different kind than the filter", () => {
    expect(toFilterValues(mockFilters, { "1": { kind: "checkbox", value: true } })).toEqual([]);
  });
});

describe("nextTriState", () => {
  it("cycles ignore, include, exclude", () => {
    expect(nextTriState("TRI_STATE_IGNORE")).toBe("TRI_STATE_INCLUDE");
    expect(nextTriState("TRI_STATE_INCLUDE")).toBe("TRI_STATE_EXCLUDE");
    expect(nextTriState("TRI_STATE_EXCLUDE")).toBe("TRI_STATE_IGNORE");
  });
});
