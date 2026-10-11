// SPDX-License-Identifier: GPL-3.0-or-later

package android.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Tells lint the code targets an API level. */
@Retention(RetentionPolicy.CLASS)
public @interface TargetApi {
    int value();
}
