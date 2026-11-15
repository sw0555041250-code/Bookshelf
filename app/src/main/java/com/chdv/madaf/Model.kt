package com.chdv.madaf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * CHECK = something to tick off (optionally in parts). UNITS = a list of units to finish (a goal).
 * TRACK = a running sequence (dapim, chapters, pages…) with a daily/weekly quota that continues from where you stopped.
 */
enum class Kind { CHECK, UNITS, TRACK }

/** DAILY / WEEKLY repeat; ONCE = a goal that is done once (optionally with a target date). */
enum class Freq { DAILY, WEEKLY, ONCE }

class Item(
    var id: String = UUID.randomUUID().toString().take(8),
    var title: String = "",
    var category: String = "",
    var kind: Kind = Kind.CHECK,
    var freq: Freq = Freq.DAILY,
    /** CHECK: the parts (e.g. חומש, תהילים, תניא). UNITS: the units (e.g. the שיחות to finish). */
    var parts: MutableList<String> = ArrayList(),
    /** UNITS: indices of finished units. */
    var unitsDone: MutableSet<Int> = HashSet(),
    /** "Where I am" – free text. */
    var note: String = "",
    /** Reminder times, minutes since midnight. */
    var times: MutableList<Int> = ArrayList(),
    /** Days of the week the item is due; bit 0 = Sunday … bit 6 = Shabbat. */
    var days: Int = 0x7F,
    var linkType: String = "",   // "", "file", "app"
    var link: String = "",
    var linkLabel: String = "",
    /** ONCE goals: target date (epoch day) or null. */
    var deadline: Long? = null,
    var archived: Boolean = false,
    /** TRACK items: the sequence and position */
    var track: Track? = null,
    /** "chitas": show today's Tehillim / Chumash next to the parts */
    var special: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("category", category)
        put("kind", kind.name); put("freq", freq.name)
        put("parts", JSONArray(parts)); put("unitsDone", JSONArray(unitsDone.sorted()))
        put("note", note); put("times", JSONArray(times)); put("days", days)
        put("linkType", linkType); put("link", link); put("linkLabel", linkLabel)
        deadline?.let { put("deadline", it) }
        put("archived", archived)
        track?.let { put("track", it.toJson()) }
        put("special", special)
    }

    fun copy(): Item = fromJson(toJson())

    val isUnits get() = kind == Kind.UNITS
    val isTrack get() = kind == Kind.TRACK && track != null
    val unitsTotal get() = parts.size
    val unitsLeft get() = (parts.indices.count { it !in unitsDone })
    fun dueOn(date: LocalDate) = days and (1 shl dowIndex(date)) != 0

    companion object {
        fun fromJson(o: JSONObject) = Item(
            id = o.optString("id"),
            title = o.optString("title"),
            category = o.optString("category"),
            kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.CHECK),
            freq = runCatching { Freq.valueOf(o.optString("freq")) }.getOrDefault(Freq.DAILY),
            parts = o.optJSONArray("parts").strings().toMutableList(),
            unitsDone = o.optJSONArray("unitsDone").ints().toMutableSet(),
            note = o.optString("note"),
            times = o.optJSONArray("times").ints().toMutableList(),
            days = o.optInt("days", 0x7F),
            linkType = o.optString("linkType"),
            link = o.optString("link"),
            linkLabel = o.optString("linkLabel"),
            deadline = if (o.has("deadline")) o.optLong("deadline") else null,
            archived = o.optBoolean("archived"),
            track = Track.fromJson(o.optJSONObject("track")),
            special = o.optString("special"),
        )
    }
}

fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { optInt(it) }

/** Sunday = 0 … Shabbat = 6 */
fun dowIndex(d: LocalDate): Int = if (d.dayOfWeek == DayOfWeek.SUNDAY) 0 else d.dayOfWeek.value

class Settings(
    var name: String = "",
    var generalTimes: MutableList<Int> = mutableListOf(21 * 60),
    var dayStartHour: Int = 3,
    var shabbatQuiet: Boolean = true,
    var fridayCutoff: Int = 15 * 60,
    var saturdayResume: Int = 20 * 60,
    var fullScreen: Boolean = true,
    var snoozeMinutes: Int = 30,
    var onboarded: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("generalTimes", JSONArray(generalTimes)); put("dayStartHour", dayStartHour)
        put("shabbatQuiet", shabbatQuiet); put("fridayCutoff", fridayCutoff); put("saturdayResume", saturdayResume)
        put("fullScreen", fullScreen); put("snoozeMinutes", snoozeMinutes)
        put("onboarded", onboarded)
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) Settings() else Settings(
            name = o.optString("name"),
            generalTimes = o.optJSONArray("generalTimes").ints().toMutableList(),
            dayStartHour = o.optInt("dayStartHour", 3),
            shabbatQuiet = o.optBoolean("shabbatQuiet", true),
            fridayCutoff = o.optInt("fridayCutoff", 15 * 60),
            saturdayResume = o.optInt("saturdayResume", 20 * 60),
            fullScreen = o.optBoolean("fullScreen", true),
            snoozeMinutes = o.optInt("snoozeMinutes", 30),
            onboarded = o.optBoolean("onboarded", false),
        )
    }
}

/**
 * Everything in one JSON file. The log maps a period ("d:2026-11-15" for a day, "w:2026-11-15" for the
 * week starting that Sunday) to the set of marks made in it: "id" = item done, "id#2" = part 2 done.
 */
object Store {
    private var loaded = false
    private lateinit var file: File
    val items = ArrayList<Item>()
    var settings = Settings()
    private val log = HashMap<String, MutableSet<String>>()

    @Synchronized
    fun load(ctx: Context): Store {
        if (loaded) return this
        file = File(ctx.filesDir, "data.json")
        if (file.exists()) runCatching { read(JSONObject(file.readText())) }
        loaded = true
        return this
    }

    private fun read(o: JSONObject) {
        items.clear(); log.clear()
        o.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) items.add(Item.fromJson(a.getJSONObject(i))) }
        settings = Settings.fromJson(o.optJSONObject("settings"))
        o.optJSONObject("log")?.let { l -> l.keys().forEach { k -> log[k] = l.optJSONArray(k).strings().toMutableSet() } }
    }

    @Synchronized
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
        put("settings", settings.toJson())
        put("log", JSONObject().also { l -> log.forEach { (k, v) -> if (v.isNotEmpty()) l.put(k, JSONArray(v.toList())) } })
    }

    @Synchronized
    fun save() {
        if (!::file.isInitialized) return
        trimLog()
        val tmp = File(file.parentFile, "data.json.tmp")
        tmp.writeText(toJson().toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun import(json: String) {
        read(JSONObject(json))
        save()
    }

    private fun trimLog() {
        val oldest = LocalDate.now().minusDays(800).toString()
        log.keys.filter { (it.startsWith("d:") || it.startsWith("w:")) && it.substring(2) < oldest }.forEach { log.remove(it) }
    }

    // ----- time -----

    /** The learning day: before [Settings.dayStartHour] it still counts as yesterday. */
    fun today(now: LocalDateTime = LocalDateTime.now()): LocalDate =
        now.minusHours(settings.dayStartHour.toLong()).toLocalDate()

    fun weekStart(d: LocalDate): LocalDate = d.minusDays(dowIndex(d).toLong())

    fun periodKey(item: Item, d: LocalDate): String =
        if (item.freq == Freq.WEEKLY) "w:${weekStart(d)}" else "d:$d"

    private fun marks(key: String) = log.getOrPut(key) { HashSet() }

    // ----- state -----

    @Synchronized
    fun isPartDone(item: Item, part: Int, d: LocalDate) = "${item.id}#$part" in marks(periodKey(item, d))

    /** Done for the period of [d]. For UNITS: progress was made (or marked) in that period; ONCE goals: finished. */
    @Synchronized
    fun isDone(item: Item, d: LocalDate): Boolean {
        val t = item.track
        if (item.isTrack && t != null) {
            val key = periodKey(item, d)
            return item.id in marks(key) || (key == t.periodKey && t.portionDone)
        }
        if (item.freq == Freq.ONCE) {
            return if (item.isUnits) item.unitsTotal > 0 && item.unitsLeft == 0 else item.id in marks("once")
        }
        val m = marks(periodKey(item, d))
        if (item.id in m) return true
        if (!item.isUnits && item.parts.isNotEmpty()) return item.parts.indices.all { "${item.id}#$it" in m }
        return false
    }

    @Synchronized
    fun partsDoneCount(item: Item, d: LocalDate) = item.parts.indices.count { isPartDone(item, it, d) }

    @Synchronized
    fun setDone(item: Item, d: LocalDate, done: Boolean) {
        val t = item.track
        if (item.isTrack && t != null && periodKey(item, d) == t.periodKey) {
            if (done) learnedUpTo(item, t.periodStart + t.quota - 1) else undoToday(item)
            return
        }
        if (item.freq == Freq.ONCE && !item.isUnits) {
            if (done) marks("once").add(item.id) else marks("once").remove(item.id)
            save(); return
        }
        val m = marks(periodKey(item, d))
        if (done) {
            m.add(item.id)
            if (!item.isUnits) item.parts.indices.forEach { m.add("${item.id}#$it") }
        } else {
            m.remove(item.id)
            if (!item.isUnits) item.parts.indices.forEach { m.remove("${item.id}#$it") }
        }
        save()
    }

    @Synchronized
    fun togglePart(item: Item, part: Int, d: LocalDate) {
        val m = marks(periodKey(item, d))
        val k = "${item.id}#$part"
        if (k in m) { m.remove(k); m.remove(item.id) } else m.add(k)
        if (item.parts.indices.all { "${item.id}#$it" in m }) m.add(item.id)
        save()
    }

    /** UNITS: mark/unmark a unit; marking also counts as progress for the day (or week). */
    @Synchronized
    fun toggleUnit(item: Item, unit: Int, d: LocalDate) {
        if (unit in item.unitsDone) item.unitsDone.remove(unit) else {
            item.unitsDone.add(unit)
            if (item.freq != Freq.ONCE) marks(periodKey(item, d)).add(item.id)
            marks("d:$d").add("${item.id}+")  // activity, for the history strip
        }
        save()
    }

    // ----- tracks -----

    /** Start a new day/week for every track: today's portion begins where you stopped. */
    @Synchronized
    fun rollover(today: LocalDate = today()) {
        var changed = false
        for (item in items) {
            val t = item.track ?: continue
            if (!item.isTrack) continue
            val key = periodKey(item, today)
            if (key != t.periodKey) { t.rollover(key); changed = true }
        }
        if (changed) save()
    }

    /** After editing a track's position: today's mark follows the position. */
    @Synchronized
    fun refreshMark(item: Item) = syncMark(item, progressed = false)

    private fun syncMark(item: Item, progressed: Boolean = true) {
        val t = item.track ?: return
        if (t.periodKey.isEmpty()) return
        val m = marks(t.periodKey)
        if (t.portionDone) m.add(item.id) else m.remove(item.id)
        if (progressed) marks("d:${today()}").add("${item.id}+")
    }

    /** Learned everything up to and including unit [last] (may be beyond today's portion). */
    @Synchronized
    fun learnedUpTo(item: Item, last: Int) {
        val t = item.track ?: return
        t.pos = (last + 1).coerceIn(0, t.size)
        syncMark(item)
        if (t.finished) finishCycle(item)
        save()
    }

    /** Start from unit [unit] (skipped ahead, or went back). Today's portion restarts there. */
    @Synchronized
    fun jumpTo(item: Item, unit: Int) {
        val t = item.track ?: return
        t.pos = unit.coerceIn(0, t.size); t.periodStart = t.pos
        marks(t.periodKey).remove(item.id)
        save()
    }

    @Synchronized
    fun undoToday(item: Item) {
        val t = item.track ?: return
        t.pos = t.periodStart
        marks(t.periodKey).remove(item.id)
        save()
    }

    /** Tap on one unit of today's portion: everything up to it is learned; tapping a learned one un-marks from there. */
    @Synchronized
    fun toggleTrackUnit(item: Item, unit: Int) {
        val t = item.track ?: return
        if (t.pos > unit) { t.pos = unit; syncMark(item, progressed = false); save() } else learnedUpTo(item, unit)
    }

    private fun finishCycle(item: Item) {
        val t = item.track ?: return
        val what = if (t.type == "gemara") "מסכת ${t.masechta}" else item.title
        t.siyum = "סיימת את $what!"
        t.siyumKey = t.periodKey
        marks(t.periodKey).add(item.id)
        val next = t.advanceMasechta()
        if (next != null) t.siyum += " ממשיכים למסכת $next."
    }

    /** UNITS: the next unit that is not done yet, or -1. */
    fun nextUnit(item: Item): Int = item.parts.indices.firstOrNull { it !in item.unitsDone } ?: -1

    @Synchronized
    fun activityOn(item: Item, d: LocalDate): Boolean {
        val m = marks("d:$d")
        return item.id in m || "${item.id}+" in m ||
            (!item.isUnits && item.parts.isNotEmpty() && item.parts.indices.any { "${item.id}#$it" in m })
    }

    /** How many periods in a row (ending with the current one, or the one before if today isn't done yet). */
    @Synchronized
    fun streak(item: Item, today: LocalDate): Int {
        if (item.freq == Freq.ONCE) return 0
        var d = today
        if (!isDone(item, d)) d = if (item.freq == Freq.WEEKLY) d.minusWeeks(1) else d.minusDays(1)
        var n = 0
        var guard = 0
        while (guard++ < 800) {
            if (item.freq == Freq.DAILY && !item.dueOn(d)) { d = d.minusDays(1); continue }
            if (!isDone(item, d)) break
            n++
            d = if (item.freq == Freq.WEEKLY) d.minusWeeks(1) else d.minusDays(1)
        }
        return n
    }

    /** Items that are relevant on [d], in display order. */
    fun visible(d: LocalDate): List<Item> = items.filter { !it.archived && (it.freq != Freq.DAILY || it.dueOn(d)) }

    fun categories(): List<String> = items.map { it.category }.filter { it.isNotBlank() }.distinct()

    fun find(id: String) = items.firstOrNull { it.id == id }

    /** Not done yet in the current period and due today. */
    fun pending(d: LocalDate): List<Item> = items.filter {
        !it.archived && !isDone(it, d) && when (it.freq) {
            Freq.DAILY -> it.dueOn(d)
            Freq.WEEKLY -> true
            Freq.ONCE -> it.times.isNotEmpty()  // goals only nag when they have their own reminder
        }
    }

    fun daysLeft(item: Item, today: LocalDate): Long? = item.deadline?.let { ChronoUnit.DAYS.between(today, LocalDate.ofEpochDay(it)) }
}

/** Hebrew numerals: 1 → א׳, 15 → ט״ו, 134 → קל״ד */
object Gematria {
    private val ones = arrayOf("", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")
    private val tens = arrayOf("", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")
    private val hundreds = arrayOf("", "ק", "ר", "ש", "ת", "תק", "תר", "תש", "תת", "תתק")

    fun of(n: Int): String {
        if (n <= 0) return n.toString()
        val num = n % 1000
        var s = hundreds[num / 100]
        val rest = num % 100
        s += when (rest) {
            15 -> "טו"
            16 -> "טז"
            else -> tens[rest / 10] + ones[rest % 10]
        }
        return if (s.length == 1) "$s׳" else s.dropLast(1) + "״" + s.last()
    }
}

/** Ready-made items. UNITS presets open the editor so the units can be filled in. */
object Presets {
    data class Preset(val label: String, val sub: String, val make: () -> Item)

    val all = listOf(
        Preset("חת\"ת", "חומש, תהילים, תניא – עם תהילים של היום לפי התאריך") {
            Item(title = "חת\"ת", category = "שיעורים יומיים", parts = mutableListOf("חומש", "תהילים", "תניא"), special = "chitas")
        },
        Preset("רמב\"ם – 3 פרקים", "ממשיך מהפרק שבו עצרת") {
            Item(title = "רמב\"ם", category = "שיעורים יומיים", kind = Kind.TRACK, track = Track(type = "rambam", quota = 3))
        },
        Preset("רמב\"ם – פרק אחד", "ממשיך מהפרק שבו עצרת") {
            Item(title = "רמב\"ם", category = "שיעורים יומיים", kind = Kind.TRACK, track = Track(type = "rambam", quota = 1))
        },
        Preset("גמרא – דף ליום", "בוחרים מסכת; ממשיך מהדף שבו עצרת") {
            Item(title = "גמרא", category = "גמרא", kind = Kind.TRACK, track = Track(type = "gemara", masechta = "פסחים"))
        },
        Preset("דרך מצוותיך", "עמודים ליום, ממשיך מהעמוד שבו עצרת") {
            Item(title = "דרך מצוותיך", category = "חסידות", kind = Kind.TRACK, track = Track(type = "pages", quota = 2))
        },
        Preset("ליקוטי שיחות – שיחה ליום", "רשימת השיחות לפי הסדר") {
            Item(title = "ליקוטי שיחות", category = "ליקוטי שיחות", kind = Kind.TRACK, track = Track(type = "list"))
        },
        Preset("ליקוטי שיחות – שיחה לשבוע", "פעם בשבוע") {
            Item(title = "ליקוטי שיחות – שבועי", category = "ליקוטי שיחות", kind = Kind.TRACK, freq = Freq.WEEKLY, track = Track(type = "list"))
        },
        Preset("ספר המצוות", "כל יום") { Item(title = "ספר המצוות", category = "שיעורים יומיים") },
        Preset("היום יום", "כל יום") { Item(title = "היום יום", category = "שיעורים יומיים") },
        Preset("רשימה לסיים עד תאריך", "למשל שיחות שצריך לסיים") {
            Item(title = "", category = "", kind = Kind.UNITS, freq = Freq.ONCE)
        },
    )
}

fun fmtTime(m: Int) = String.format("%02d:%02d", m / 60, m % 60)
