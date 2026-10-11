// SPDX-License-Identifier: GPL-3.0-or-later

package android.icu.text;

import java.util.Locale;

/**
 * ICU's RuleBasedCollator over java.text.RuleBasedCollator. QUATERNARY behaves as TERTIARY.
 * With case level on, strings equal at the current strength are also compared by case.
 */
public final class RuleBasedCollator extends Collator {
    private java.text.RuleBasedCollator delegate;
    private int strength = TERTIARY;
    private int decomposition = NO_DECOMPOSITION;
    private boolean caseLevel;

    RuleBasedCollator(java.text.RuleBasedCollator delegate) {
        this.delegate = delegate;
        delegate.setStrength(java.text.Collator.TERTIARY);
        delegate.setDecomposition(java.text.Collator.NO_DECOMPOSITION);
    }

    public RuleBasedCollator(String rules) throws Exception {
        this(new java.text.RuleBasedCollator(rules));
    }

    public String getRules() {
        return delegate.getRules();
    }

    @Override
    public int getStrength() {
        return strength;
    }

    @Override
    public void setStrength(int newStrength) {
        strength = newStrength;
        delegate.setStrength(switch (newStrength) {
            case PRIMARY -> java.text.Collator.PRIMARY;
            case SECONDARY -> java.text.Collator.SECONDARY;
            case IDENTICAL -> java.text.Collator.IDENTICAL;
            default -> java.text.Collator.TERTIARY;
        });
    }

    @Override
    public int getDecomposition() {
        return decomposition;
    }

    @Override
    public void setDecomposition(int decomposition) {
        this.decomposition = decomposition;
        delegate.setDecomposition(decomposition == CANONICAL_DECOMPOSITION
            ? java.text.Collator.CANONICAL_DECOMPOSITION : java.text.Collator.NO_DECOMPOSITION);
    }

    public boolean isCaseLevel() {
        return caseLevel;
    }

    public void setCaseLevel(boolean flag) {
        caseLevel = flag;
    }

    public void setAlternateHandlingShifted(boolean shifted) {}

    public void setNumericCollation(boolean flag) {}

    @Override
    public int compare(String source, String target) {
        int result = delegate.compare(source, target);
        if (result != 0 || !caseLevel || strength >= TERTIARY) return result;
        // Case level: equal letters that differ only in case still compare unequal.
        String a = java.text.Normalizer.normalize(source, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String b = java.text.Normalizer.normalize(target, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (a.length() != b.length()) return 0;
        for (int i = 0; i < a.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (Character.toLowerCase(x) == Character.toLowerCase(y) && x != y) return Character.isLowerCase(x) ? -1 : 1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RuleBasedCollator other && other.delegate.equals(delegate) && other.caseLevel == caseLevel;
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        RuleBasedCollator copy = (RuleBasedCollator) super.clone();
        copy.delegate = (java.text.RuleBasedCollator) delegate.clone();
        return copy;
    }

    static RuleBasedCollator forLocale(Locale locale) {
        return (RuleBasedCollator) Collator.getInstance(locale);
    }
}
