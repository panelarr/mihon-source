// SPDX-License-Identifier: GPL-3.0-or-later

import icon from "../assets/panelarr-icon.svg";

/** The Panelarr icon; decorative, since the wordmark next to it carries the name. */
export function BrandIcon({ size }: { size: number }) {
  return <img src={icon} width={size} height={size} alt="" className="brand-icon" />;
}

/** "Panelarr Mihon Source", the wordmark in the UI font. */
export function Wordmark() {
  return (
    <span className="wordmark">
      <span className="wordmark-name">Panelarr</span> <span className="wordmark-product">Mihon Source</span>
    </span>
  );
}
