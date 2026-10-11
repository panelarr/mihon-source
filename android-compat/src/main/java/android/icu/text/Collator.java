// SPDX-License-Identifier: GPL-3.0-or-later

package android.icu.text;

import java.util.Comparator;
import java.util.Locale;

/** ICU's Collator, backed by java.text.Collator. ICU strength and decomposition values are used. */
public abstract class Collator implements Comparator<Object>, Cloneable {
    public static final int PRIMARY = 0;
    public static final int SECONDARY = 1;
    public static final int TERTIARY = 2;
    public static final int QUATERNARY = 3;
    public static final int IDENTICAL = 15;
    public static final int FULL_DECOMPOSITION = IDENTICAL;
    public static final int NO_DECOMPOSITION = 16;
    public static final int CANONICAL_DECOMPOSITION = 17;

    protected Collator() {}

    public static final Collator getInstance() {
        return getInstance(Locale.getDefault());
    }

    public static final Collator getInstance(Locale locale) {
        return new RuleBasedCollator((java.text.RuleBasedCollator) java.text.Collator.getInstance(locale));
    }

    public abstract int getStrength();

    public abstract void setStrength(int newStrength);

    public abstract int getDecomposition();

    public abstract void setDecomposition(int decomposition);

    public abstract int compare(String source, String target);

    public boolean equals(String source, String target) {
        return compare(source, target) == 0;
    }

    @Override
    public int compare(Object source, Object target) {
        return compare((String) source, (String) target);
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
