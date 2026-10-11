// SPDX-License-Identifier: GPL-3.0-or-later

package android.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Tells lint to ignore warnings. */
@Retention(RetentionPolicy.CLASS)
public @interface SuppressLint {
    String[] value();
}
