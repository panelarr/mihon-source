// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import android.net.Uri;
import java.util.Map;

/** A resource request made by a page. */
public interface WebResourceRequest {
    Uri getUrl();

    boolean isForMainFrame();

    boolean isRedirect();

    boolean hasGesture();

    String getMethod();

    Map<String, String> getRequestHeaders();
}
