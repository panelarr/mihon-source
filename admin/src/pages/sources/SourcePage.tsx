// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { api, unwrap } from "../../api/client";
import type { Source } from "../../api/types";
import { TabList, tabProps } from "../../components/Tabs";
import { ErrorBox, Loading, Mono, PageHeader } from "../../components/ui";
import { languageLabel } from "../../lib/format";
import { sourceQuery } from "./queries";
import { SearchPanel } from "./SearchPanel";
import { BrowseList } from "./SeriesGrid";
import { SettingsPanel } from "./SettingsPanel";
import { CapabilityBadges, ContentRatingBadge, hasCapability } from "./SourceBadges";

export type SourceTab = "popular" | "latest" | "search" | "settings";

const route = getRouteApi("/_app/sources/$sourceId");

function availableTabs(source: Source): { tab: SourceTab; label: string }[] {
  return [
    { tab: "popular", label: "Popular" },
    ...(hasCapability(source, "SOURCE_CAPABILITY_LATEST") ? [{ tab: "latest" as const, label: "Latest" }] : []),
    { tab: "search", label: "Search" },
    ...(hasCapability(source, "SOURCE_CAPABILITY_SETTINGS") ? [{ tab: "settings" as const, label: "Settings" }] : []),
  ];
}

export function SourcePage() {
  const { sourceId } = route.useParams();
  const { tab: requested } = route.useSearch();
  const source = useQuery(sourceQuery(sourceId));

  if (source.isPending) return <Loading />;
  if (source.isError) {
    return (
      <>
        <PageHeader title="Source" />
        <ErrorBox error={source.error} onRetry={() => source.refetch()} />
      </>
    );
  }

  const tabs = availableTabs(source.data);
  const tab = tabs.find((item) => item.tab === requested)?.tab ?? "popular";

  return (
    <>
      <p className="crumbs">
        <Link to="/sources">Sources</Link> /
      </p>
      <PageHeader title={source.data.name || sourceId}>
        {source.data.homeUrl ? (
          <a href={source.data.homeUrl} target="_blank" rel="noreferrer noopener">
            Website ↗
          </a>
        ) : null}
      </PageHeader>
      <p className="meta">
        <span>{languageLabel(source.data.lang)}</span>
        <Mono>{sourceId}</Mono>
        <ContentRatingBadge rating={source.data.contentRating} />
        <CapabilityBadges source={source.data} />
      </p>
      {hasCapability(source.data, "SOURCE_CAPABILITY_NEEDS_WEBVIEW") ? (
        <p className="notice">This source needs a browser engine the service doesn't have, so most calls will fail.</p>
      ) : null}

      <TabList label="Source views">
        {tabs.map((item) => (
          <Link
            key={item.tab}
            to="/sources/$sourceId"
            params={{ sourceId }}
            search={{ tab: item.tab === "popular" ? undefined : item.tab }}
            {...tabProps(item.tab === tab)}
          >
            {item.label}
          </Link>
        ))}
      </TabList>

      <section className="tab-panel">
        {tab === "popular" ? (
          <BrowseList
            sourceId={sourceId}
            queryKey={["sources", sourceId, "popular"]}
            fetchPage={(page) =>
              unwrap(api.GET("/v1/sources/{sourceId}/popular", { params: { path: { sourceId }, query: { page } } }))
            }
          />
        ) : null}
        {tab === "latest" ? (
          <BrowseList
            sourceId={sourceId}
            queryKey={["sources", sourceId, "latest"]}
            fetchPage={(page) =>
              unwrap(api.GET("/v1/sources/{sourceId}/latest", { params: { path: { sourceId }, query: { page } } }))
            }
          />
        ) : null}
        {tab === "search" ? <SearchPanel sourceId={sourceId} /> : null}
        {tab === "settings" ? <SettingsPanel sourceId={sourceId} /> : null}
      </section>
    </>
  );
}
