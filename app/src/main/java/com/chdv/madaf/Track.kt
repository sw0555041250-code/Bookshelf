package com.chdv.madaf

import org.json.JSONArray
import org.json.JSONObject

/**
 * A learning track: a fixed sequence of units (dapim, chapters, pages, sichos…), a position,
 * and a quota per day/week. Today's portion always starts where you actually stopped:
 * skip a day and it simply continues; learn extra and it moves on from there.
 */
class Track(
    /** "gemara", "rambam", "pages", "list" */
    var type: String = "list",
    /** gemara: the masechta */
    var masechta: String = "ברכות",
    /** gemara: count by amud instead of by daf */
    var byAmud: Boolean = false,
    /** pages: number of pages in the book */
    var pageCount: Int = 0,
    /** list: the units, one per line */
    var list: MutableList<String> = ArrayList(),
    /** index of the next unit to learn */
    var pos: Int = 0,
    /** units per period */
    var quota: Int = 1,
    /** gemara: when the masechta is finished, go on to the next one */
    var autoNext: Boolean = true,
    var cycles: Int = 0,
    /** the period that [periodStart] belongs to, e.g. "d:2026-11-15" */
    var periodKey: String = "",
    /** position at the start of the current period: today's portion is periodStart until periodStart + quota */
    var periodStart: Int = 0,

    // ---- where the material is ----
    /** "", "file" (one book) or "folder" (a folder of books; the right file is picked per unit) */
    var sourceType: String = "",
    var sourceUri: String = "",
    var sourceName: String = "",
    /** page in the file where unit 0 starts (0 = unknown) */
    var firstPage: Int = 0,
    var pagesPerUnit: Float = 1f,
    /** corrections: "fileName|unit" -> page */
    var anchors: MutableMap<String, Int> = HashMap(),
    /** a finished masechta/book: shown for the rest of that period */
    var siyum: String = "",
    var siyumKey: String = "",
    /** folder sources: the file chosen last time when no file name matched */
    var lastFile: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("type", type); put("masechta", masechta); put("byAmud", byAmud); put("pageCount", pageCount)
        put("list", JSONArray(list)); put("pos", pos); put("quota", quota); put("autoNext", autoNext)
        put("cycles", cycles); put("periodKey", periodKey); put("periodStart", periodStart)
        put("sourceType", sourceType); put("sourceUri", sourceUri); put("sourceName", sourceName)
        put("firstPage", firstPage); put("pagesPerUnit", pagesPerUnit.toDouble())
        put("anchors", JSONObject().also { a -> anchors.forEach { (k, v) -> a.put(k, v) } })
        put("siyum", siyum); put("siyumKey", siyumKey); put("lastFile", lastFile)
    }

    // ---- units ----

    val size: Int get() = when (type) {
        "gemara" -> Shas.find(masechta)?.let { (it.last - it.first + 1) * if (byAmud) 2 else 1 } ?: 0
        "rambam" -> Rambam.chapters.size
        "pages" -> pageCount
        else -> list.size
    }

    fun label(i: Int): String = when (type) {
        "gemara" -> {
            val m = Shas.find(masechta)
            if (m == null) "" else if (byAmud) "${Gematria.of(m.first + i / 2)} ${if (i % 2 == 0) "ע״א" else "ע״ב"}"
            else "דף ${Gematria.of(m.first + i)}"
        }
        "rambam" -> Rambam.chapters.getOrNull(i)?.let { "${it.hilchot} ${Gematria.of(it.chapter)}" } ?: ""
        "pages" -> "עמוד ${i + 1}"
        else -> list.getOrElse(i) { "" }
    }

    /** Short label with context, for reminders: "פסחים דף ד׳" */
    fun fullLabel(i: Int): String = when (type) {
        "gemara" -> "$masechta ${label(i)}"
        else -> label(i)
    }

    /** Words to look for in file names to find the right book for unit [i]. */
    fun fileKeys(i: Int): List<String> = when (type) {
        "gemara" -> listOf(masechta)
        "rambam" -> Rambam.chapters.getOrNull(i)?.let { listOf(it.hilchot.removePrefix("הלכות ").trim(), it.sefer.removePrefix("ספר ").trim()) } ?: emptyList()
        else -> emptyList()
    }

    // ---- today's portion ----

    /** Call once per screen/reminder: when a new period starts, today's portion starts at the current position. */
    fun rollover(key: String) {
        if (key != periodKey) { periodKey = key; periodStart = pos.coerceAtMost(size) }
    }

    val portion: IntRange get() = periodStart until (periodStart + quota).coerceAtMost(size)
    val portionDone: Boolean get() = size > 0 && (pos >= periodStart + quota || pos >= size)
    val finished: Boolean get() = size > 0 && pos >= size

    fun portionLabel(): String {
        val r = portion
        if (r.isEmpty()) return if (finished) "סיימת!" else ""
        if (r.first == r.last) return fullLabel(r.first)
        return when (type) {
            "gemara" -> "$masechta ${label(r.first)} – ${label(r.last).removePrefix("דף ")}"
            "pages" -> "עמודים ${r.first + 1}–${r.last + 1}"
            "rambam" -> {
                val a = Rambam.chapters[r.first]; val b = Rambam.chapters[r.last]
                if (a.hilchot == b.hilchot) "${a.hilchot} ${Gematria.of(a.chapter)}–${Gematria.of(b.chapter)}"
                else r.joinToString(" · ") { label(it) }
            }
            else -> r.joinToString(" · ") { label(it) }
        }
    }

    /** gemara with autoNext: move to the next masechta when this one is done. Returns the new name or null. */
    fun advanceMasechta(): String? {
        if (type != "gemara" || !autoNext || !finished) return null
        val i = Shas.all.indexOfFirst { it.name == masechta }
        val next = Shas.all.getOrNull(i + 1) ?: Shas.all.first().also { cycles++ }
        masechta = next.name
        pos = 0; periodStart = 0; quota = quota.coerceAtLeast(1)
        anchors.clear(); firstPage = 0
        if (sourceType == "file") { sourceType = ""; sourceUri = ""; sourceName = "" }
        return next.name
    }

    // ---- pages ----

    /** The page of unit [i] in [fileName], or 0 when unknown (the reader then opens where you last stopped). */
    fun pageFor(i: Int, fileName: String): Int {
        if (type == "pages") return i + 1
        val own = anchors.mapNotNull { (k, v) ->
            val f = k.substringBeforeLast('|'); val u = k.substringAfterLast('|').toIntOrNull()
            if (f == fileName && u != null) u to v else null
        }.sortedBy { it.first }
        val ppu = if (type == "gemara" && !byAmud && pagesPerUnit <= 1f) 2f else pagesPerUnit
        val base = own.lastOrNull { it.first <= i } ?: own.firstOrNull()
        if (base != null) return (base.second + Math.round((i - base.first) * ppu)).coerceAtLeast(1)
        if (firstPage > 0 && sourceType == "file") return (firstPage + Math.round(i * ppu)).coerceAtLeast(1)
        return 0
    }

    fun setAnchor(fileName: String, i: Int, page: Int) {
        anchors["$fileName|$i"] = page
    }

    companion object {
        fun fromJson(o: JSONObject?) = if (o == null) null else Track(
            type = o.optString("type", "list"),
            masechta = o.optString("masechta", "ברכות"),
            byAmud = o.optBoolean("byAmud"),
            pageCount = o.optInt("pageCount"),
            list = o.optJSONArray("list").strings().toMutableList(),
            pos = o.optInt("pos"),
            quota = o.optInt("quota", 1).coerceAtLeast(1),
            autoNext = o.optBoolean("autoNext", true),
            cycles = o.optInt("cycles"),
            periodKey = o.optString("periodKey"),
            periodStart = o.optInt("periodStart"),
            sourceType = o.optString("sourceType"),
            sourceUri = o.optString("sourceUri"),
            sourceName = o.optString("sourceName"),
            firstPage = o.optInt("firstPage"),
            pagesPerUnit = o.optDouble("pagesPerUnit", 1.0).toFloat(),
            anchors = HashMap<String, Int>().also { m -> o.optJSONObject("anchors")?.let { a -> a.keys().forEach { m[it] = a.optInt(it) } } },
            siyum = o.optString("siyum"),
            siyumKey = o.optString("siyumKey"),
            lastFile = o.optString("lastFile"),
        )

        val TYPES = listOf("gemara" to "גמרא – דף אחרי דף", "rambam" to "רמב\"ם – פרק אחרי פרק", "pages" to "עמודים בספר", "list" to "רשימה משלי (שיחות, מצוות…)")
    }
}

/** The Babylonian Talmud in order: name, first and last daf (Vilna). */
object Shas {
    class Masechta(val name: String, val first: Int, val last: Int)

    val all = listOf(
        Masechta("ברכות", 2, 64), Masechta("שבת", 2, 157), Masechta("עירובין", 2, 105), Masechta("פסחים", 2, 121),
        Masechta("שקלים", 2, 22), Masechta("יומא", 2, 88), Masechta("סוכה", 2, 56), Masechta("ביצה", 2, 40),
        Masechta("ראש השנה", 2, 35), Masechta("תענית", 2, 31), Masechta("מגילה", 2, 32), Masechta("מועד קטן", 2, 29),
        Masechta("חגיגה", 2, 27), Masechta("יבמות", 2, 122), Masechta("כתובות", 2, 112), Masechta("נדרים", 2, 91),
        Masechta("נזיר", 2, 66), Masechta("סוטה", 2, 49), Masechta("גיטין", 2, 90), Masechta("קידושין", 2, 82),
        Masechta("בבא קמא", 2, 119), Masechta("בבא מציעא", 2, 119), Masechta("בבא בתרא", 2, 176), Masechta("סנהדרין", 2, 113),
        Masechta("מכות", 2, 24), Masechta("שבועות", 2, 49), Masechta("עבודה זרה", 2, 76), Masechta("הוריות", 2, 14),
        Masechta("זבחים", 2, 120), Masechta("מנחות", 2, 110), Masechta("חולין", 2, 142), Masechta("בכורות", 2, 61),
        Masechta("ערכין", 2, 34), Masechta("תמורה", 2, 34), Masechta("כריתות", 2, 28), Masechta("מעילה", 2, 22),
        Masechta("תמיד", 25, 33), Masechta("נדה", 2, 73),
    )

    fun find(name: String) = all.firstOrNull { it.name == name }
}

/** Mishneh Torah: every הלכות with its number of chapters (1000 chapters in all). */
object Rambam {
    class Chapter(val sefer: String, val hilchot: String, val chapter: Int)

    private val books = listOf(
        "ספר המדע" to listOf("הלכות יסודי התורה" to 10, "הלכות דעות" to 7, "הלכות תלמוד תורה" to 7, "הלכות עבודה זרה" to 12, "הלכות תשובה" to 10),
        "ספר אהבה" to listOf("הלכות קריאת שמע" to 4, "הלכות תפילה" to 15, "הלכות תפילין ומזוזה וספר תורה" to 10, "הלכות ציצית" to 3, "הלכות ברכות" to 11, "הלכות מילה" to 3),
        "ספר זמנים" to listOf("הלכות שבת" to 30, "הלכות עירובין" to 8, "הלכות שביתת עשור" to 3, "הלכות שביתת יום טוב" to 8, "הלכות חמץ ומצה" to 8,
            "הלכות שופר וסוכה ולולב" to 8, "הלכות שקלים" to 4, "הלכות קידוש החודש" to 19, "הלכות תעניות" to 5, "הלכות מגילה וחנוכה" to 4),
        "ספר נשים" to listOf("הלכות אישות" to 25, "הלכות גירושין" to 13, "הלכות יבום וחליצה" to 8, "הלכות נערה בתולה" to 3, "הלכות סוטה" to 4),
        "ספר קדושה" to listOf("הלכות איסורי ביאה" to 22, "הלכות מאכלות אסורות" to 17, "הלכות שחיטה" to 14),
        "ספר הפלאה" to listOf("הלכות שבועות" to 12, "הלכות נדרים" to 13, "הלכות נזירות" to 10, "הלכות ערכים וחרמין" to 8),
        "ספר זרעים" to listOf("הלכות כלאים" to 10, "הלכות מתנות עניים" to 10, "הלכות תרומות" to 15, "הלכות מעשרות" to 14,
            "הלכות מעשר שני ונטע רבעי" to 11, "הלכות ביכורים" to 12, "הלכות שמיטה ויובל" to 13),
        "ספר עבודה" to listOf("הלכות בית הבחירה" to 8, "הלכות כלי המקדש" to 10, "הלכות ביאת מקדש" to 9, "הלכות איסורי מזבח" to 7,
            "הלכות מעשה הקרבנות" to 19, "הלכות תמידין ומוספין" to 10, "הלכות פסולי המוקדשין" to 19, "הלכות עבודת יום הכיפורים" to 5, "הלכות מעילה" to 8),
        "ספר קרבנות" to listOf("הלכות קרבן פסח" to 10, "הלכות חגיגה" to 3, "הלכות בכורות" to 8, "הלכות שגגות" to 15, "הלכות מחוסרי כפרה" to 5, "הלכות תמורה" to 4),
        "ספר טהרה" to listOf("הלכות טומאת מת" to 25, "הלכות פרה אדומה" to 15, "הלכות טומאת צרעת" to 16, "הלכות מטמאי משכב ומושב" to 13,
            "הלכות שאר אבות הטומאות" to 20, "הלכות טומאת אוכלין" to 16, "הלכות כלים" to 28, "הלכות מקואות" to 11),
        "ספר נזיקין" to listOf("הלכות נזקי ממון" to 14, "הלכות גניבה" to 9, "הלכות גזילה ואבידה" to 18, "הלכות חובל ומזיק" to 8, "הלכות רוצח ושמירת נפש" to 13),
        "ספר קנין" to listOf("הלכות מכירה" to 30, "הלכות זכייה ומתנה" to 12, "הלכות שכנים" to 14, "הלכות שלוחין ושותפין" to 10, "הלכות עבדים" to 9),
        "ספר משפטים" to listOf("הלכות שכירות" to 13, "הלכות שאלה ופיקדון" to 8, "הלכות מלווה ולווה" to 27, "הלכות טוען ונטען" to 16, "הלכות נחלות" to 11),
        "ספר שופטים" to listOf("הלכות סנהדרין" to 26, "הלכות עדות" to 22, "הלכות ממרים" to 7, "הלכות אבל" to 14, "הלכות מלכים ומלחמות" to 12),
    )

    val chapters: List<Chapter> = books.flatMap { (sefer, hs) -> hs.flatMap { (h, n) -> (1..n).map { Chapter(sefer, h, it) } } }
    val hilchotStarts: List<Pair<String, Int>> by lazy {
        val out = ArrayList<Pair<String, Int>>()
        chapters.forEachIndexed { i, c -> if (c.chapter == 1) out += c.hilchot to i }
        out
    }
}

/** Chitas helpers: today's Tehillim (by the day of the Hebrew month) and Chumash (by weekday). */
object Chitas {
    // Tehillim divided for the days of the month
    private val tehillim = listOf(
        "א–ט", "י–יז", "יח–כב", "כג–כח", "כט–לד", "לה–לח", "לט–מג", "מד–מח", "מט–נד", "נה–נט",
        "ס–סה", "סו–סח", "סט–עא", "עב–עו", "עז–עח", "עט–פב", "פג–פז", "פח–פט", "צ–צו", "צז–קג",
        "קד–קה", "קו–קז", "קח–קיב", "קיג–קיח", "קיט (א–צו)", "קיט (צז–קעו)", "קכ–קלד", "קלה–קלט", "קמ–קמד", "קמה–קנ",
    )

    /** [day] = day of the Hebrew month (1–30); in a 29-day month the 29th covers 140–150. */
    fun tehillimFor(day: Int, monthLength: Int): String =
        if (day == 29 && monthLength == 29) "קמ–קנ" else tehillim[(day - 1).coerceIn(0, 29)]

    private val weekday = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שביעי")
    fun chumashFor(dow: Int) = "שיעור יום ${weekday[dow]}"
}
