package com.chdv.madaf

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout

/** The reminder: what is still open today, with the checkboxes right there. Shown over the lock screen. */
class ReminderActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Store.load(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val today = Store.today()
        Store.rollover(today)
        val pending = Store.pending(today)
        val name = Store.settings.name
        val c = page("תזכורת", back = true)
        if (pending.isEmpty()) {
            Reminders.cancel(this)
            val done = card(filled = true)
            done.addView(text("✓", 48f, bold = true, color = Ink.WHITE))
            done.addView(text(if (name.isBlank()) "הכול נלמד היום!" else "$name, הכול נלמד היום!", 24f, bold = true, color = Ink.WHITE))
            done.addView(text("יישר כח", 17f, color = Ink.WHITE))
            c.addView(done)
            c.addView(button("סגירה") { finish() }.apply { lp(top = 20) })
            return
        }
        c.addView(text(if (name.isBlank()) "עדיין לא למדת היום:" else "$name, עדיין לא למדת היום:", 22f, bold = true).apply { lp(bottom = 12) })
        val list = card()
        pending.forEachIndexed { i, item ->
            if (i > 0) list.addView(divider())
            list.addView(itemRow(item, today, changed = { render() }, edit = null,
                openGoal = { startActivity(Intent(this, GoalActivity::class.java).putExtra("id", item.id)) }))
        }
        c.addView(list)
        val row = hbox().apply { lp(top = 22) }
        row.addView(button("תזכיר לי בעוד ${Store.settings.snoozeMinutes} דק׳") { Reminders.snooze(this); finish() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(button("לכל השיעורים", primary = false) {
            startActivity(Intent(this, MainActivity::class.java)); finish()
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(row)
    }
}
