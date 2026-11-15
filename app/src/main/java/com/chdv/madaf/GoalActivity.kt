package com.chdv.madaf

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import java.time.LocalDate

/** All the units of a list (e.g. the שיחות to finish), to tick off one by one. */
class GoalActivity : BaseActivity() {
    private lateinit var item: Item
    private lateinit var day: LocalDate
    private var showDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        day = LocalDate.ofEpochDay(intent.getLongExtra("day", Store.today().toEpochDay()))
    }

    override fun onResume() {
        super.onResume()
        item = Store.find(intent.getStringExtra("id") ?: "") ?: run { finish(); return }
        render()
    }

    private fun render() {
        val c = page(item.title, back = true)
        val total = item.unitsTotal
        val done = item.unitsDone.size

        val head = card(filled = true)
        head.addView(text("$done/$total", 40f, bold = true, color = Ink.WHITE).apply { textDirection = View.TEXT_DIRECTION_LTR })
        val pct = if (total == 0) 0 else done * 100 / total
        head.addView(text(if (done == total && total > 0) "סיימת את כל הרשימה. מזל טוב!" else "$pct% · נשארו ${total - done}", 17f, bold = true, color = Ink.WHITE))
        Store.daysLeft(item, Store.today())?.let { left ->
            if (left > 0 && total - done > 0) {
                val perDay = Math.ceil((total - done).toDouble() / left).toInt()
                head.addView(text("נשארו $left ימים עד היעד · כ-$perDay ליום", 14f, color = Ink.WHITE))
            }
        }
        if (total > 0) head.addView(progressBar(done.toFloat() / total, 12, inverted = true).apply { lp(top = 12) })
        c.addView(head)

        if (item.note.isNotBlank()) c.addView(text("איפה אני עומד: ${item.note}", 15f).apply { lp(top = 12) })

        val filter = FlowLayout(this, dp(8)).apply { setPadding(0, dp(16), 0, dp(8)) }
        filter.addView(chip("מה שנשאר", !showDone) { showDone = false; render() })
        filter.addView(chip("הכול", showDone) { showDone = true; render() })
        c.addView(filter)

        val list = card()
        var shown = 0
        item.parts.forEachIndexed { i, name ->
            val isDone = i in item.unitsDone
            if (isDone && !showDone) return@forEachIndexed
            if (shown > 0) list.addView(divider())
            shown++
            val row = hbox()
            row.addView(checkBox(isDone) { Store.toggleUnit(item, i, day); render() })
            val t = text(name, 18f, bold = !isDone, color = if (isDone) Ink.GRAY else Ink.BLACK).apply {
                setPadding(dp(14), 0, 0, 0)
                if (isDone) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener { Store.toggleUnit(item, i, day); render() }
            }
            row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            list.addView(row)
        }
        if (shown == 0) list.addView(text(if (total == 0) "הרשימה ריקה" else "הכול הושלם ✓", 16f, color = Ink.GRAY))
        c.addView(list)

        val actions = hbox().apply { lp(top = 20) }
        if (item.linkType.isNotEmpty()) {
            actions.addView(button("פתיחת הספר") { openLink(item) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            actions.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        }
        actions.addView(button("עריכה", primary = false) {
            startActivity(Intent(this, EditActivity::class.java).putExtra("id", item.id))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(actions)
    }
}
