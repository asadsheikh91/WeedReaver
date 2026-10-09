package com.example.andriodfypprototype.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Every number and date the operator reads goes through here, so units stay consistent. */
object Fmt {
    private val loc = Locale.UK
    private val time = SimpleDateFormat("HH:mm", loc)
    private val dayMonth = SimpleDateFormat("d MMM", loc)
    private val full = SimpleDateFormat("d MMM yyyy", loc)
    private val weekday = SimpleDateFormat("EEEE, d MMMM", loc)
    private val weekdayShort = SimpleDateFormat("EEE d MMM", loc)

    private fun dayIndex(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun time(ms: Long): String = time.format(ms)
    fun date(ms: Long): String = full.format(ms)
    fun dayMonth(ms: Long): String = dayMonth.format(ms)
    fun weekday(ms: Long): String = weekday.format(ms)
    fun weekdayShort(ms: Long): String = weekdayShort.format(ms)

    fun daysBetween(from: Long, to: Long): Int = ((to - from) / 86_400_000.0).roundToInt()

    /** "Just now", "12 min ago", "Today, 09:42", "Yesterday, 16:10", "12 Dec 2025". */
    fun relative(ms: Long, now: Long = Demo.now()): String {
        val diff = now - ms
        val dNow = dayIndex(now)
        val d = dayIndex(ms)
        return when {
            diff in 0 until 60_000 -> "Just now"
            diff in 60_000 until 3_600_000 -> "${diff / 60_000} min ago"
            d == dNow -> "Today, ${time(ms)}"
            d == dNow - 1 -> "Yesterday, ${time(ms)}"
            d == dNow + 1 -> "Tomorrow, ${time(ms)}"
            d > dNow - 7 && d < dNow -> "${dNow - d} days ago"
            Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR) ==
                Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) -> dayMonth(ms)
            else -> date(ms)
        }
    }

    /** Heading used to group a timeline. */
    fun dayGroup(ms: Long, now: Long = Demo.now()): String {
        val dNow = dayIndex(now)
        val d = dayIndex(ms)
        return when (d) {
            dNow -> "Today"
            dNow - 1 -> "Yesterday"
            else -> if (dNow - d < 7) weekday.format(ms).substringBefore(",") else
                SimpleDateFormat("MMMM yyyy", loc).format(ms)
        }
    }

    fun inDays(target: Long, now: Long = Demo.now()): String {
        val d = dayIndex(target) - dayIndex(now)
        return when {
            d == 0 -> "today"
            d == 1 -> "tomorrow"
            d == -1 -> "yesterday"
            d > 1 -> "in $d days"
            else -> "${-d} days ago"
        }
    }

    fun acres(ac: Float): String = if (ac < 10f) "%.2f".format(ac) else "%.1f".format(ac)

    /** Primary area string in the operator's chosen units. */
    fun area(ac: Float, local: Boolean = AppState.useLocalUnits): String =
        if (local) "${acres(ac)} ac" else "%.2f ha".format(ac * 0.404686f)

    /** Secondary area string: the other unit system. */
    fun areaAlt(ac: Float, local: Boolean = AppState.useLocalUnits): String {
        return if (local) {
            val k = (ac * 8f).toInt()
            val m = ((ac * 8f - k) * 20f).roundToInt()
            if (m == 20) "${k + 1} kanal" else if (m == 0) "$k kanal" else "$k kanal $m marla"
        } else "${acres(ac)} ac"
    }

    fun sqm(m2: Number): String {
        val v = m2.toFloat()
        return if (v >= 10_000f) "%.2f ha".format(v / 10_000f) else "%,d m²".format(v.roundToInt())
    }

    fun meters(m: Float): String = when {
        m >= 1000f -> "%.1f km".format(m / 1000f)
        m >= 10f -> "${m.roundToInt()} m"
        else -> "%.1f m".format(m)
    }

    fun pct(f: Float): String = "${(f * 100).roundToInt()}%"

    fun coord(lat: Double, lon: Double): String =
        "%.5f° N, %.5f° E".format(lat, lon)

    fun duration(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60} min ${if (seconds % 60 > 0) "${seconds % 60}s" else ""}".trim()

    fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

    fun initials(name: String): String =
        name.split(' ').filter { it.isNotBlank() }
            .let { p -> p.firstOrNull()?.take(1).orEmpty() + p.drop(1).lastOrNull()?.take(1).orEmpty() }
            .uppercase().ifBlank { "OP" }
}
