// SPDX-License-Identifier: GPL-3.0-or-later

// Short names for the generated API types (src/api/schema.d.ts).
import type { components } from "./schema";

type Schemas = components["schemas"];

// panelarr.source.v1
export type PluginInfo = Schemas["panelarr.source.v1.PluginInfo"];
export type Source = Schemas["panelarr.source.v1.Source"];
export type SourceCapability = NonNullable<Source["capabilities"]>[number];
export type Filter = Schemas["panelarr.source.v1.Filter"];
export type FilterValue = Schemas["panelarr.source.v1.FilterValue"];
export type SortSelection = Schemas["panelarr.source.v1.SortSelection"];
export type TriState = NonNullable<FilterValue["triState"]>;
export type Series = Schemas["panelarr.source.v1.Series"];
export type Chapter = Schemas["panelarr.source.v1.Chapter"];
export type Page = Schemas["panelarr.source.v1.Page"];
export type BrowseResponse = Schemas["panelarr.source.v1.PopularResponse"];
export type SearchRequest = Schemas["panelarr.source.v1.SearchRequest"];

// panelarr.mihon.v1
export type WhoAmI = Schemas["panelarr.mihon.v1.WhoAmIResponse"];
export type Client = Schemas["panelarr.mihon.v1.Client"];
export type Extension = Schemas["panelarr.mihon.v1.Extension"];
export type InstallExtensionResponse = Schemas["panelarr.mihon.v1.InstallExtensionResponse"];
export type Store = Schemas["panelarr.mihon.v1.Store"];
export type AvailableExtension = Schemas["panelarr.mihon.v1.AvailableExtension"];
export type ContentWarning = NonNullable<Extension["contentWarning"]>;
export type Solver = Schemas["panelarr.mihon.v1.Solver"];
export type SolverType = NonNullable<Solver["type"]>;
export type NetworkProfile = Schemas["panelarr.mihon.v1.NetworkProfile"];
export type UserAgentMode = NonNullable<NetworkProfile["userAgentMode"]>;
export type Assignment = Schemas["panelarr.mihon.v1.Assignment"];
export type ProfileOrigin = NonNullable<Assignment["effective"]>;

/** A JSON object as sent in google.protobuf.Struct fields (settings, schemas). */
export type JsonObject = { [key: string]: unknown };

/**
 * google.protobuf.Struct fields are typed as empty objects in the OpenAPI document. This
 * passes a JSON object where the generated types expect one.
 */
export function asStruct(value: JsonObject): { [key: string]: never } {
  return value as { [key: string]: never };
}
