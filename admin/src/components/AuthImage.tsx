// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useRef, useState } from "react";
import { errorMessage } from "../api/errors";
import { fetchImage } from "../api/extras";

/**
 * An image loaded through GET /v1/sources/{id}/image, which needs the bearer header, so
 * it's fetched with fetch() and shown from an object URL. Loads once it scrolls into view.
 */
export function AuthImage({
  sourceId,
  imageRef,
  alt,
  className,
}: {
  sourceId: string;
  imageRef: string | undefined;
  alt: string;
  className?: string;
}) {
  const holder = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const element = holder.current;
    if (!element || typeof IntersectionObserver === "undefined") {
      setVisible(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: "200px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!visible || !imageRef) return;
    const controller = new AbortController();
    let objectUrl: string | null = null;
    setError(null);
    fetchImage(sourceId, imageRef, controller.signal)
      .then((blob) => {
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(errorMessage(cause));
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      setUrl(null);
    };
  }, [visible, sourceId, imageRef]);

  return (
    <div ref={holder} className={`auth-image ${className ?? ""}`}>
      {url ? (
        <img src={url} alt={alt} />
      ) : (
        <span className="auth-image-placeholder" title={error ?? undefined}>
          {!imageRef ? "No image" : error ? "Image failed" : "…"}
        </span>
      )}
    </div>
  );
}
