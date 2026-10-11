// SPDX-License-Identifier: GPL-3.0-or-later

import type { Source, SourceCapability } from "../../api/types";
import { Badge, type Tone } from "../../components/ui";

const capabilityLabels: Partial<Record<SourceCapability, [string, Tone]>> = {
  SOURCE_CAPABILITY_LATEST: ["Latest", "neutral"],
  SOURCE_CAPABILITY_SEARCH: ["Search", "neutral"],
  SOURCE_CAPABILITY_FILTERS: ["Filters", "neutral"],
  SOURCE_CAPABILITY_RESOLVE_URL: ["Resolve URL", "neutral"],
  SOURCE_CAPABILITY_SETTINGS: ["Settings", "neutral"],
  SOURCE_CAPABILITY_NEEDS_WEBVIEW: ["Needs webview", "bad"],
  SOURCE_CAPABILITY_NSFW: ["NSFW", "warn"],
};

export function hasCapability(source: Source | undefined, capability: SourceCapability): boolean {
  return source?.capabilities?.includes(capability) ?? false;
}

export function ContentRatingBadge({ rating }: { rating: Source["contentRating"] }) {
  if (rating === "CONTENT_RATING_NSFW") return <Badge tone="bad">NSFW</Badge>;
  if (rating === "CONTENT_RATING_MIXED") return <Badge tone="warn">Mixed</Badge>;
  if (rating === "CONTENT_RATING_SAFE") return <Badge tone="good">Safe</Badge>;
  return null;
}

export function CapabilityBadges({ source }: { source: Source }) {
  return (
    <span className="badges">
      {(source.capabilities ?? []).map((capability) => {
        const label = capabilityLabels[capability];
        return label ? (
          <Badge key={capability} tone={label[1]}>
            {label[0]}
          </Badge>
        ) : null;
      })}
    </span>
  );
}
