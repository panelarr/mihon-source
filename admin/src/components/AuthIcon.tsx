// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useState } from "react";
import { fetchBlob } from "../api/extras";

/**
 * A small icon from an authenticated endpoint of the service. Shows the first letter of
 * [label] while loading or when there's no icon.
 */
export function AuthIcon({ path, label }: { path: string | undefined; label: string }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    let objectUrl: string | null = null;
    fetchBlob(path, controller.signal)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        // No icon: the letter stays.
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      setUrl(null);
    };
  }, [path]);

  return (
    <span className="icon" aria-hidden="true">
      {url ? (
        <img src={url} alt="" />
      ) : (
        <span className="icon-letter">{label.trim().charAt(0).toUpperCase() || "?"}</span>
      )}
    </span>
  );
}
