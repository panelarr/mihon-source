// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import com.ibm.icu.util.ULocale
import java.text.DateFormatSymbols
import java.text.FieldPosition
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A `java.text.SimpleDateFormat` that formats and parses like Android's, which is ICU's.
 * The two differ in ways extensions rely on: ICU knows more pattern letters (`c`, `e`,
 * `B`, `Q`, …), reads `ZZZZZ` as an ISO offset (`+02:00`), and reads `SSSSSS` as a
 * fraction of a second. Extension bytecode is rewritten to create this class wherever it
 * creates a SimpleDateFormat; it is still a SimpleDateFormat, so everything else links.
 *
 * Built with DateFormatSymbols it behaves like the JDK class, as there's no ICU mapping.
 */
@Suppress("TooManyFunctions")
class IcuSimpleDateFormat : SimpleDateFormat {
    private var icu: com.ibm.icu.text.SimpleDateFormat?

    constructor() : this(DEFAULT_PATTERN, Locale.getDefault())

    constructor(pattern: String) : this(pattern, Locale.getDefault())

    constructor(pattern: String, locale: Locale) : super("", locale) {
        icu =
            com.ibm.icu.text
                .SimpleDateFormat(pattern, ULocale.forLocale(locale))
                .apply { timeZone = icuZone(TimeZone.getDefault()) }
    }

    constructor(pattern: String, symbols: DateFormatSymbols) : super(pattern, symbols) {
        icu = null
    }

    override fun format(
        date: Date,
        toAppendTo: StringBuffer,
        pos: FieldPosition,
    ): StringBuffer = icu?.format(date, toAppendTo, pos) ?: super.format(date, toAppendTo, pos)

    override fun parse(
        text: String,
        pos: ParsePosition,
    ): Date? = icu?.parse(text, pos) ?: super.parse(text, pos)

    override fun setTimeZone(zone: TimeZone) {
        super.setTimeZone(zone)
        icu?.timeZone = icuZone(zone)
    }

    override fun setLenient(lenient: Boolean) {
        super.setLenient(lenient)
        icu?.isLenient = lenient
    }

    override fun isLenient(): Boolean = icu?.isLenient ?: super.isLenient()

    override fun applyPattern(pattern: String) {
        val current = icu
        if (current == null) super.applyPattern(pattern) else current.applyPattern(pattern)
    }

    override fun applyLocalizedPattern(pattern: String) {
        val current = icu
        if (current == null) super.applyLocalizedPattern(pattern) else current.applyLocalizedPattern(pattern)
    }

    override fun toPattern(): String = icu?.toPattern() ?: super.toPattern()

    override fun toLocalizedPattern(): String = icu?.toLocalizedPattern() ?: super.toLocalizedPattern()

    override fun set2DigitYearStart(startDate: Date) {
        super.set2DigitYearStart(startDate)
        icu?.set2DigitYearStart(startDate)
    }

    override fun get2DigitYearStart(): Date = icu?.get2DigitYearStart() ?: super.get2DigitYearStart()

    override fun clone(): Any =
        (super.clone() as IcuSimpleDateFormat).also { copy ->
            copy.icu =
                icu?.clone() as com.ibm.icu.text.SimpleDateFormat?
        }

    override fun equals(other: Any?): Boolean = other is IcuSimpleDateFormat && other.toPattern() == toPattern() && super.equals(other)

    override fun hashCode(): Int = toPattern().hashCode()

    private companion object {
        const val DEFAULT_PATTERN = "M/d/yy, h:mm a"

        fun icuZone(zone: TimeZone): com.ibm.icu.util.TimeZone =
            com.ibm.icu.util.TimeZone
                .getTimeZone(zone.id)
    }
}
