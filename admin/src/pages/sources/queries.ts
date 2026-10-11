// SPDX-License-Identifier: GPL-3.0-or-later

import { queryOptions } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { JsonObject } from "../../api/types";

const path = (sourceId: string) => ({ path: { sourceId } });

export const sourcesQuery = () =>
  queryOptions({
    queryKey: ["sources"],
    queryFn: async () => (await unwrap(api.GET("/v1/sources"))).sources ?? [],
  });

export const sourceQuery = (sourceId: string) =>
  queryOptions({
    queryKey: ["sources", sourceId],
    queryFn: async () => {
      const response = await unwrap(api.GET("/v1/sources/{sourceId}", { params: path(sourceId) }));
      return response.source ?? {};
    },
  });

export const filtersQuery = (sourceId: string) =>
  queryOptions({
    queryKey: ["sources", sourceId, "filters"],
    queryFn: async () =>
      (await unwrap(api.GET("/v1/sources/{sourceId}/filters", { params: path(sourceId) }))).filters ?? [],
    staleTime: 5 * 60_000,
  });

export const seriesQuery = (sourceId: string, key: string) =>
  queryOptions({
    queryKey: ["sources", sourceId, "series", key],
    queryFn: async () => {
      const response = await unwrap(
        api.GET("/v1/sources/{sourceId}/series", { params: { path: { sourceId }, query: { key } } }),
      );
      return response.series ?? {};
    },
  });

export const chaptersQuery = (sourceId: string, seriesKey: string) =>
  queryOptions({
    queryKey: ["sources", sourceId, "chapters", seriesKey],
    queryFn: async () =>
      (
        await unwrap(
          api.GET("/v1/sources/{sourceId}/chapters", { params: { path: { sourceId }, query: { seriesKey } } }),
        )
      ).chapters ?? [],
  });

export const pagesQuery = (sourceId: string, chapterKey: string) =>
  queryOptions({
    queryKey: ["sources", sourceId, "pages", chapterKey],
    queryFn: async () =>
      (await unwrap(api.GET("/v1/sources/{sourceId}/pages", { params: { path: { sourceId }, query: { chapterKey } } })))
        .pages ?? [],
  });

export const settingsSchemaQuery = (sourceId: string) =>
  queryOptions({
    queryKey: ["sources", sourceId, "settings", "schema"],
    queryFn: async () =>
      ((await unwrap(api.GET("/v1/sources/{sourceId}/settings/schema", { params: path(sourceId) }))).schema ??
        {}) as JsonObject,
  });

export const settingsQuery = (sourceId: string) =>
  queryOptions({
    queryKey: ["sources", sourceId, "settings", "values"],
    queryFn: async () =>
      ((await unwrap(api.GET("/v1/sources/{sourceId}/settings", { params: path(sourceId) }))).values ??
        {}) as JsonObject,
  });
