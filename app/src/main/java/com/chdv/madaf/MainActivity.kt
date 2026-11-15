package com.chdv.madaf

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.LocalTime

class MainActivity : BaseActivity() {
    private var day: LocalDate = LocalDate.now()
    private var lastToday: LocalDate = LocalDate.now()
    private var onboardingPicks = HashSet<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        day = Store.today()
        lastToday = day
    }

    override fun onResume() {
        super.onResume()
        // A new learning day may have started while we were in the background
        if (Store.today() != lastToday) { lastToday = Store.today(); day = lastToday }
        render()
        Updater.dailyCheck(this)
    }

    private fun greeting(): String {
        val h = LocalTime.now().hour
        val hello = when (h) { in 4..11 -> "בוקר טוב"; in 12..16 -> "צהריים טובים"; in 17..20 -> "ערב טוב"; else -> "לילה טוב" }
        val name = Store.settings.name
        return if (name.isBlank()) hello else "$hello, $name"
    }

    private fun render() {
        val c = page(greeting(), back = false)
        if (!Store.settings.onboarded) { onboarding(c); return }
        val today = Store.today()
        Store.rollover(today)

        // date line + navigation
        val dateRow = hbox().apply { lp(bottom = 14) }
        val dateTexts = vbox()
        dateTexts.addView(text("${HebDate.dayName(day)} · ${HebDate.hebrew(day)}", 16f, bold = true))
        dateTexts.addView(text(if (day == today) "היום · ${HebDate.short(day)}" else "מציג את ${HebDate.short(day)}", 14f, color = Ink.GRAY))
        dateRow.addView(dateTexts, LinearLayout.LayoutParams(0, WRAP, 1f))
        dateRow.addView(button("‹ יום קודם", primary = false, small = true) {
            if (day.isAfter(today.minusDays(14))) { day = day.minusDays(1); render() }
        })
        if (day != today) {
            dateRow.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
            dateRow.addView(button("להיום", small = true) { day = today; render() })
        }
        c.addView(dateRow)

        val visible = Store.visible(day)
        val repeating = visible.filter { it.freq != Freq.ONCE }
        val doneCount = repeating.count { Store.isDone(it, day) }
        c.addView(summaryCard(doneCount, repeating.size))

        val daily = visible.filter { it.freq == Freq.DAILY }
        val weekly = visible.filter { it.freq == Freq.WEEKLY }
        val goals = visible.filter { it.freq == Freq.ONCE }
        if (daily.isNotEmpty()) section(c, if (day == today) "היום" else "באותו יום", daily)
        if (weekly.isNotEmpty()) section(c, "השבוע", weekly)
        if (goals.isNotEmpty()) section(c, "יעדים", goals)
        if (visible.isEmpty()) {
            c.addView(text("אין שיעורים להיום. הוסף שיעור או יעד.", 16f, color = Ink.GRAY).apply { lp(top = 20) })
        }

        val bottom = hbox().apply { lp(top = 26) }
        bottom.addView(button("+ הוספה") { addDialog() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bottom.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        bottom.addView(button("הגדרות", primary = false) { startActivity(Intent(this, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(bottom)
        c.addView(text("לחיצה ארוכה על שיעור – עריכה", 13f, color = Ink.GRAY).apply { lp(top = 10); textAlignment = View.TEXT_ALIGNMENT_CENTER })
    }

    private fun summaryCard(done: Int, total: Int): View {
        val card = card(filled = true)
        val row = hbox()
        val big = text(if (total == 0) "—" else "$done/$total", 40f, bold = true, color = Ink.WHITE).apply { textDirection = View.TEXT_DIRECTION_LTR }
        val texts = vbox().apply { setPadding(dp(16), 0, 0, 0) }
        val msg = when {
            total == 0 -> "עוד לא נקבע אף שיעור"
            done == total -> "סיימת הכול. יישר כח!"
            done == 0 -> "בהצלחה בלימוד היום"
            else -> "נשארו עוד ${total - done}"
        }
        texts.addView(text(msg, 19f, bold = true, color = Ink.WHITE))
        texts.addView(text(if (day == Store.today()) "הושלמו היום" else "הושלמו ביום הזה", 14f, color = Ink.WHITE))
        row.addView(big)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(row)
        if (total > 0) card.addView(progressBar(done.toFloat() / total, 12, inverted = true).apply { lp(top = 14) })
        return card
    }

    private fun section(c: LinearLayout, title: String, items: List<Item>) {
        val byCat = items.groupBy { it.category.ifBlank { title } }
        c.addView(text(title, 22f, bold = true).apply { lp(top = 24, bottom = 8) })
        val card = card()
        var first = true
        for ((cat, list) in byCat) {
            if (byCat.size > 1 || cat != title) {
                if (!first) card.addView(gapView())
                card.addView(text(cat, 13f, bold = true, color = Ink.GRAY))
            }
            list.forEachIndexed { i, item ->
                if (i > 0) card.addView(divider())
                card.addView(itemRow(item, day, changed = { render() },
                    edit = { startActivity(Intent(this, EditActivity::class.java).putExtra("id", item.id)) },
                    openGoal = { startActivity(Intent(this, GoalActivity::class.java).putExtra("id", item.id).putExtra("day", day.toEpochDay())) }))
            }
            first = false
        }
        c.addView(card)
    }

    private fun gapView() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) }

    private fun addDialog() {
        val items = (Presets.all.map { it.label } + "שיעור / יעד משלי…").toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("מה להוסיף לסדרים?")
            .setItems(items) { _, which ->
                val item = if (which < Presets.all.size) Presets.all[which].make() else Item(title = "")
                startActivity(Intent(this, EditActivity::class.java).putExtra("new", item.toJson().toString()))
            }
            .show()
    }

    /** First run: name + choose what to put on the shelf. */
    private fun onboarding(c: LinearLayout) {
        setPageTitle("ברוך הבא ל\"סדרים\"")
        c.addView(text("סדר הלימוד שלך במקום אחד: מה עוד לא למדת היום, איפה אתה עומד בכל ספר, ופתיחת הספר בלחיצה.", 16f, color = Ink.GRAY))
        c.addView(text("איך קוראים לך?", 18f, bold = true).apply { lp(top = 22, bottom = 6) })
        val name = EditText(this).apply { hint = "השם שלך"; textDirection = View.TEXT_DIRECTION_RTL; setSingleLine() }
        c.addView(name)
        c.addView(text("מה הסדרים שלך?", 18f, bold = true).apply { lp(top = 22, bottom = 6) })
        val list = card()
        Presets.all.forEachIndexed { i, p ->
            if (i > 0) list.addView(divider())
            val row = hbox()
            val box = checkBox(i in onboardingPicks) {}
            row.addView(box)
            val t = vbox().apply { setPadding(dp(14), 0, 0, 0) }
            t.addView(text(p.label, 17f, bold = true)); t.addView(text(p.sub, 13f, color = Ink.GRAY))
            row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            val toggle = {
                if (!onboardingPicks.remove(i)) onboardingPicks.add(i)
                val on = i in onboardingPicks
                box.text = if (on) "✓" else ""
                box.setTextColor(if (on) Ink.WHITE else Ink.BLACK)
                box.background = if (on) rounded(Ink.BLACK, radius = dp(10).toFloat()) else rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(10).toFloat())
            }
            row.setOnClickListener { toggle() }
            box.setOnClickListener { toggle() }
            list.addView(row)
        }
        c.addView(list)
        c.addView(text("אפשר לשנות ולהוסיף הכול אחר כך. אחרי ההתחלה – לחיצה ארוכה על כל שיעור כדי לבחור מאיפה לומדים (ספר או תיקייה) ואיפה אתה עומד.", 13f, color = Ink.GRAY).apply { lp(top = 8) })
        c.addView(button("להתחיל") {
            Store.settings.name = name.text.toString().trim()
            Store.settings.onboarded = true
            onboardingPicks.sorted().forEach { Store.items.add(Presets.all[it].make()) }
            Store.save()
            Reminders.schedule(this)
            val firstUnits = Store.items.firstOrNull { (it.isUnits && it.unitsTotal == 0) || (it.isTrack && it.track!!.size == 0) }
            if (firstUnits != null) startActivity(Intent(this, EditActivity::class.java).putExtra("id", firstUnits.id))
            render()
        }.apply { lp(top = 20); gravity = Gravity.CENTER })
    }
}
