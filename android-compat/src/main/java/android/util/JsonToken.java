// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

/** The kinds of token a {@link JsonReader} can be positioned on. */
public enum JsonToken {
    BEGIN_ARRAY,
    END_ARRAY,
    BEGIN_OBJECT,
    END_OBJECT,
    NAME,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    END_DOCUMENT
}
