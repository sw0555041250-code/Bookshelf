package com.chdv.madaf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.time.LocalDate

class SettingsActivity : BaseActivity() {
    private val s get() = Store.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
    }

    override fun onResume() { super.onResume(); render() }

    private fun changed() { Store.save(); Reminders.schedule(this); render() }

    private fun section(t: String) = text(t, 20f, bold = true).apply { lp(top = 24, bottom = 10) }

    private fun clickRow(title: String, value: String, onClick: () -> Unit): View {
        val row = hbox().apply { setPadding(0, dp(6), 0, dp(6)) }
        val t = vbox()
        t.addView(text(title, 17f, bold = true))
        if (value.isNotEmpty()) t.addView(text(value, 14f, color = Ink.GRAY))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(text("‹", 24f, bold = true))
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    private fun render() {
        val c = page("הגדרות", back = true)

        c.addView(section("כללי"))
        val general = card()
        general.addView(clickRow("השם שלך", s.name.ifBlank { "לא הוגדר" }) {
            askText("השם שלך", s.name) { s.name = it; changed() }
        })
        general.addView(divider())
        general.addView(clickRow("היום מתחלף בשעה", "${fmtTime(s.dayStartHour * 60)} – לימוד אחרי חצות נחשב עוד ליום הקודם") {
            pickNumber("היום מתחלף בשעה", 0, 6, s.dayStartHour) { s.dayStartHour = it; changed() }
        })
        c.addView(general)

        c.addView(section("תזכורות"))
        val rem = card()
        rem.addView(text("תזכורת כללית", 17f, bold = true))
        rem.addView(text("בשעות האלה תקבל רשימה של כל מה שעוד לא סימנת היום", 14f, color = Ink.GRAY))
        val times = FlowLayout(this, dp(8)).apply { setPadding(0, dp(10), 0, 0) }
        s.generalTimes.sorted().forEach { t -> times.addView(chip("${fmtTime(t)}  ✕", true) { s.generalTimes.remove(t); changed() }) }
        times.addView(chip("+ שעה", false) { pickTime(21 * 60) { m -> if (m !in s.generalTimes) s.generalTimes.add(m); changed() } })
        rem.addView(times)
        rem.addView(divider())
        rem.addView(toggleRow("מסך תזכורת מלא", "התזכורת מדליקה את המסך ומציגה את הרשימה לסימון", s.fullScreen) { s.fullScreen = it; Store.save() })
        rem.addView(divider())
        rem.addView(clickRow("\"תזכיר לי אחר כך\"", "בעוד ${s.snoozeMinutes} דקות") {
            pickNumber("כמה דקות?", 5, 180, s.snoozeMinutes) { s.snoozeMinutes = it; changed() }
        })
        rem.addView(divider())
        rem.addView(toggleRow("בלי תזכורות בשבת", "משישי ${fmtTime(s.fridayCutoff)} עד מוצאי שבת ${fmtTime(s.saturdayResume)}", s.shabbatQuiet) {
            s.shabbatQuiet = it; changed()
        })
        if (s.shabbatQuiet) {
            val r = FlowLayout(this, dp(8)).apply { setPadding(0, dp(4), 0, 0) }
            r.addView(chip("שישי: ${fmtTime(s.fridayCutoff)}", false) { pickTime(s.fridayCutoff) { s.fridayCutoff = it; changed() } })
            r.addView(chip("מוצ\"ש: ${fmtTime(s.saturdayResume)}", false) { pickTime(s.saturdayResume) { s.saturdayResume = it; changed() } })
            rem.addView(r)
        }
        rem.addView(divider())
        val next = Reminders.nextFire(java.time.LocalDateTime.now())
        rem.addView(text(if (next == null) "אין תזכורות מתוכננות" else "התזכורת הבאה: ${HebDate.dayName(next.toLocalDate())} ${fmtTime(next.hour * 60 + next.minute)}", 14f, color = Ink.GRAY))
        rem.addView(button("לנסות תזכורת עכשיו", primary = false, small = true) {
            val p = Store.pending(Store.today())
            if (p.isEmpty()) Toast.makeText(this, "הכול כבר סומן היום – אין על מה להזכיר", Toast.LENGTH_SHORT).show()
            else Reminders.notify(this, p)
        }.apply { lp(WRAP, top = 10) })
        rem.addView(clickRow("פעולה ברקע", "כדי שהתזכורות יגיעו בזמן גם כשהמכשיר במנוחה") { askBattery() })
        c.addView(rem)

        c.addView(section("כל השיעורים"))
        val all = card()
        if (Store.items.isEmpty()) all.addView(text("עוד אין שיעורים", 15f, color = Ink.GRAY))
        Store.items.forEachIndexed { i, item ->
            if (i > 0) all.addView(divider())
            val row = hbox()
            val t = vbox()
            t.addView(text(item.title + if (item.archived) "  (מוסתר)" else "", 17f, bold = true, color = if (item.archived) Ink.GRAY else Ink.BLACK))
            val kind = when (item.freq) { Freq.DAILY -> "יומי"; Freq.WEEKLY -> "שבועי"; Freq.ONCE -> "יעד" }
            t.addView(text(listOf(item.category, kind, item.times.sorted().joinToString(" ") { fmtTime(it) }).filter { it.isNotBlank() }.joinToString(" · "), 13f, color = Ink.GRAY))
            t.setOnClickListener { startActivity(Intent(this, EditActivity::class.java).putExtra("id", item.id)) }
            row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (i > 0) row.addView(chip("▲", false) { Store.items.add(i - 1, Store.items.removeAt(i)); changed() })
            if (i < Store.items.size - 1) {
                row.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
                row.addView(chip("▼", false) { Store.items.add(i + 1, Store.items.removeAt(i)); changed() })
            }
            all.addView(row)
        }
        c.addView(all)

        c.addView(section("גיבוי"))
        val backup = card()
        backup.addView(text("שמירת כל הנתונים (השיעורים, ההתקדמות וההגדרות) לקובץ, ושחזור ממנו – למשל אחרי איפוס.", 14f, color = Ink.GRAY))
        val br = hbox().apply { lp(top = 12) }
        br.addView(button("שמירת גיבוי", small = true) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "kviut-backup-${LocalDate.now()}.json")
            runCatching { startActivityForResult(i, REQ_EXPORT) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        br.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        br.addView(button("שחזור", primary = false, small = true) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            runCatching { startActivityForResult(i, REQ_IMPORT) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        backup.addView(br)
        c.addView(backup)

        c.addView(section("עדכונים"))
        val upd = card()
        upd.addView(clickRow("בדיקת עדכונים", "גרסה ${Updater.version(this)}") { Updater.check(this, silent = false) })
        upd.addView(divider())
        upd.addView(toggleRow("עדכון אוטומטי", "בודק פעם ביום ומציע להתקין גרסה חדשה. השיעורים וההתקדמות נשמרים", Updater.autoEnabled(this)) { Updater.setAuto(this, it) })
        c.addView(upd)

        c.addView(text("קביעות · גרסה ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")}", 13f, color = Ink.GRAY).apply {
            lp(top = 24); textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Store.toJson().toString(2).toByteArray()) }
                    Toast.makeText(this, "הגיבוי נשמר", Toast.LENGTH_SHORT).show()
                }
                REQ_IMPORT -> {
                    val json = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                        .setTitle("לשחזר מהגיבוי?")
                        .setMessage("כל מה שנמצא עכשיו באפליקציה יוחלף בתוכן הגיבוי.")
                        .setPositiveButton("שחזור") { _, _ ->
                            try {
                                Store.import(json); Store.settings.onboarded = true; Store.save()
                                Reminders.schedule(this); render()
                                Toast.makeText(this, "שוחזר", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) { info("שגיאה", "הקובץ לא נראה כמו גיבוי של קביעות.") }
                        }
                        .setNegativeButton("ביטול", null).show()
                }
            }
        } catch (e: Exception) {
            info("שגיאה", e.message ?: "הפעולה נכשלה")
        }
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            info("הכול מוכן", "האפליקציה כבר רשאית לפעול ברקע.\n\nב-BOOX כדאי גם לוודא שהיא לא מוקפאת: אפליקציות › ניהול אפליקציות › קביעות › לבטל הקפאה.")
            return
        }
        runCatching {
            @Suppress("BatteryLife")
            startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { runCatching { startActivity(Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    companion object { const val REQ_EXPORT = 21; const val REQ_IMPORT = 22 }
}
