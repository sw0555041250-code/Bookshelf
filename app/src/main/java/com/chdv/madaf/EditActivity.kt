package com.chdv.madaf

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import java.time.LocalDate

/** Add or edit one item on the shelf. Works on a copy; "שמירה" writes it back. */
class EditActivity : BaseActivity() {
    private lateinit var item: Item
    private var isNew = false
    private lateinit var titleEdit: EditText
    private lateinit var categoryEdit: EditText
    private lateinit var noteEdit: EditText
    private lateinit var partsEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        val id = intent.getStringExtra("id")
        val existing = id?.let { Store.find(it) }
        item = when {
            savedInstanceState?.getString("draft") != null -> Item.fromJson(JSONObject(savedInstanceState.getString("draft")!!))
            existing != null -> existing.copy()
            else -> Item.fromJson(JSONObject(intent.getStringExtra("new") ?: "{}")).also { if (it.id.isBlank()) it.id = Item().id }
        }
        isNew = existing == null
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        collect()
        outState.putString("draft", item.toJson().toString())
    }

    private fun field(label: String, value: String, hint: String = "", multiLine: Boolean = false): EditText {
        content.addView(text(label, 15f, bold = true).apply { lp(top = 18, bottom = 4) })
        val e = EditText(this).apply {
            setText(value); setHint(hint)
            textDirection = View.TEXT_DIRECTION_RTL
            inputType = if (multiLine) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
            if (multiLine) { minLines = 3; maxLines = 10 }
            textSize = 18f
        }
        content.addView(e)
        return e
    }

    private fun label(s: String, sub: String? = null) {
        content.addView(text(s, 15f, bold = true).apply { lp(top = 20, bottom = if (sub == null) 8 else 0) })
        if (sub != null) content.addView(text(sub, 13f, color = Ink.GRAY).apply { lp(bottom = 8) })
    }

    /** Copy the text fields into [item]. */
    private fun collect() {
        if (!::titleEdit.isInitialized) return
        item.title = titleEdit.text.toString().trim()
        item.category = categoryEdit.text.toString().trim()
        item.note = noteEdit.text.toString().trim()
        val oldParts = item.parts.toList()
        item.parts = if (item.isUnits) partsEdit.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        else partsEdit.text.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (item.isUnits && oldParts != item.parts) {
            // keep progress for units whose name did not change
            val doneNames = item.unitsDone.mapNotNull { oldParts.getOrNull(it) }.toSet()
            item.unitsDone = item.parts.indices.filter { item.parts[it] in doneNames }.toMutableSet()
        }
    }

    /** Callers run collect() first when the fields may hold unsaved typing. */
    private fun render() {
        val c = page(if (isNew) "הוספה למדף" else "עריכה", back = true, key = "edit")

        titleEdit = field("שם", item.title, "למשל: חת\"ת, רמב\"ם, דף יומי")
        categoryEdit = field("קטגוריה", item.category, "למשל: שיעורים יומיים")
        val cats = Store.categories().filter { it != item.category }
        if (cats.isNotEmpty()) {
            val flow = FlowLayout(this, dp(6)).apply { setPadding(0, dp(6), 0, 0) }
            cats.forEach { cat -> flow.addView(chip(cat, false) { categoryEdit.setText(cat) }) }
            c.addView(flow)
        }

        label("סוג")
        val kindRow = FlowLayout(this, dp(8))
        kindRow.addView(chip("משימה לסמן", item.kind == Kind.CHECK) { collect(); item.kind = Kind.CHECK; render() })
        kindRow.addView(chip("רשימה לסיים", item.kind == Kind.UNITS) { collect(); item.kind = Kind.UNITS; item.parts.clear(); item.unitsDone.clear(); render() })
        c.addView(kindRow)

        label("כמה פעמים")
        val freqRow = FlowLayout(this, dp(8))
        freqRow.addView(chip("כל יום", item.freq == Freq.DAILY) { collect(); item.freq = Freq.DAILY; render() })
        freqRow.addView(chip("כל שבוע", item.freq == Freq.WEEKLY) { collect(); item.freq = Freq.WEEKLY; render() })
        freqRow.addView(chip(if (item.isUnits) "עד שאסיים" else "פעם אחת", item.freq == Freq.ONCE) { collect(); item.freq = Freq.ONCE; render() })
        c.addView(freqRow)

        if (item.freq == Freq.DAILY) {
            label("באילו ימים")
            val dayRow = FlowLayout(this, dp(6))
            listOf("א׳", "ב׳", "ג׳", "ד׳", "ה׳", "ו׳", "ש״ק").forEachIndexed { i, n ->
                dayRow.addView(chip(n, item.days and (1 shl i) != 0) { collect(); item.days = item.days xor (1 shl i); render() })
            }
            c.addView(dayRow)
        }

        if (item.isUnits) {
            label("הרשימה", "יחידה בכל שורה: שיחות, פרקים, מצוות, עמודים…")
            partsEdit = EditText(this).apply {
                setText(item.parts.joinToString("\n"))
                textDirection = View.TEXT_DIRECTION_RTL
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4; maxLines = 14; textSize = 17f
            }
            c.addView(partsEdit)
            c.addView(button("יצירת רשימה ממוספרת…", primary = false, small = true) { numberedUnits() }.apply { lp(WRAP, top = 8) })
            if (item.unitsTotal > 0) c.addView(text("הושלמו ${item.unitsDone.size} מתוך ${item.unitsTotal}", 13f, color = Ink.GRAY).apply { lp(top = 6) })
        } else {
            label("חלקים (לא חובה)", "מופרדים בפסיק – כל חלק מסומן בנפרד. למשל: חומש, תהילים, תניא")
            partsEdit = EditText(this).apply {
                setText(item.parts.joinToString(", ")); textDirection = View.TEXT_DIRECTION_RTL; textSize = 17f
            }
            c.addView(partsEdit)
        }

        noteEdit = field("איפה אני עומד (לא חובה)", item.note, "למשל: מסכת ברכות דף י״ב")

        if (item.freq == Freq.ONCE) {
            label("תאריך יעד (לא חובה)")
            val r = hbox()
            val dl = item.deadline?.let { LocalDate.ofEpochDay(it) }
            r.addView(button(dl?.let { "${HebDate.short(it)} · ${HebDate.hebrew(it)}" } ?: "בחירת תאריך", primary = false, small = true) { pickDeadline() })
            if (dl != null) {
                r.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
                r.addView(button("בלי תאריך", primary = false, small = true) { collect(); item.deadline = null; render() })
            }
            c.addView(r)
        }

        label("תזכורות", "בשעות האלה תקבל תזכורת – רק אם עדיין לא סימנת. יש גם תזכורת כללית בהגדרות.")
        val times = FlowLayout(this, dp(8))
        item.times.sorted().forEach { t ->
            times.addView(chip("${fmtTime(t)}  ✕", true) { collect(); item.times.remove(t); render() })
        }
        times.addView(chip("+ שעה", false) {
            collect()
            pickTime(20 * 60) { m -> if (m !in item.times) item.times.add(m); render() }
        })
        c.addView(times)

        label("פתיחה מהירה", "ספר או אפליקציה שייפתחו בלחיצה על \"פתיחה\"")
        val linkRow = FlowLayout(this, dp(8))
        if (item.linkType.isNotEmpty()) {
            linkRow.addView(chip("${if (item.linkType == "file") "📖" else "▦"} ${item.linkLabel}  ✕", true) {
                collect(); item.linkType = ""; item.link = ""; item.linkLabel = ""; render()
            })
        }
        linkRow.addView(chip("בחירת ספר", false) { collect(); pickBook() })
        linkRow.addView(chip("בחירת אפליקציה", false) { collect(); pickApp() })
        c.addView(linkRow)

        if (!isNew) history(c)

        val actions = hbox().apply { lp(top = 30) }
        actions.addView(button("שמירה") { save() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (!isNew) {
            actions.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            actions.addView(button("מחיקה", primary = false) { confirmDelete() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        c.addView(actions)
        if (!isNew) {
            c.addView(button(if (item.archived) "החזרה למדף" else "הסתרה מהמדף (בלי למחוק)", primary = false, small = true) {
                collect(); item.archived = !item.archived; save()
            }.apply { lp(top = 12) })
        }
    }

    /** The last 4 weeks as little squares, newest on the left (end of the RTL line). */
    private fun history(c: LinearLayout) {
        if (item.freq == Freq.ONCE) return
        val today = Store.today()
        label(if (item.freq == Freq.WEEKLY) "8 השבועות האחרונים" else "28 הימים האחרונים")
        val flow = FlowLayout(this, dp(5))
        val count = if (item.freq == Freq.WEEKLY) 8 else 28
        for (i in count - 1 downTo 0) {
            val d = if (item.freq == Freq.WEEKLY) today.minusWeeks(i.toLong()) else today.minusDays(i.toLong())
            val due = item.freq == Freq.WEEKLY || item.dueOn(d)
            val done = Store.isDone(item, d)
            flow.addView(View(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(dp(22), dp(22))
                background = when {
                    done -> rounded(Ink.BLACK, radius = dp(5).toFloat())
                    due -> rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(5).toFloat())
                    else -> rounded(Ink.FILL, radius = dp(5).toFloat())
                }
            })
        }
        c.addView(flow)
        val s = Store.streak(item, today)
        c.addView(text("רצף נוכחי: $s ${if (item.freq == Freq.WEEKLY) "שבועות" else "ימים"}  ·  ■ נלמד  □ לא נלמד", 13f, color = Ink.GRAY).apply { lp(top = 6) })
    }

    private fun numberedUnits() {
        collect()
        val box = vbox(16)
        val prefix = EditText(this).apply { hint = "שם היחידה, למשל: שיחה / פרק / עמוד"; textDirection = View.TEXT_DIRECTION_RTL }
        val count = EditText(this).apply { hint = "כמה?"; inputType = InputType.TYPE_CLASS_NUMBER }
        var heb = true
        val style = chip("מספור בעברית (א׳, ב׳…)", true, null)
        style.setOnClickListener {
            heb = !heb
            style.text = if (heb) "מספור בעברית (א׳, ב׳…)" else "מספור רגיל (1, 2…)"
        }
        box.addView(prefix); box.addView(count); box.addView(style.apply { lp(WRAP, top = 10) })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("רשימה ממוספרת")
            .setView(box)
            .setPositiveButton("יצירה") { _, _ ->
                val n = count.text.toString().toIntOrNull()?.coerceIn(1, 2000) ?: return@setPositiveButton
                val p = prefix.text.toString().trim()
                val start = item.parts.size
                for (i in 1..n) item.parts.add(listOf(p, if (heb) Gematria.of(i) else "$i").filter { it.isNotEmpty() }.joinToString(" "))
                if (start == 0) item.unitsDone.clear()
                render()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun pickDeadline() {
        val d = item.deadline?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now().plusMonths(1)
        DatePickerDialog(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert, { _, y, m, day ->
            item.deadline = LocalDate.of(y, m + 1, day).toEpochDay(); render()
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun pickBook() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        runCatching { startActivityForResult(i, REQ_BOOK) }.onFailure { info("לא נתמך", "אין במכשיר בורר קבצים.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_BOOK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "ספר"
        item.linkType = "file"; item.link = uri.toString(); item.linkLabel = name.substringBeforeLast('.')
        render()
    }

    private fun pickApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("בחירת אפליקציה")
            .setItems(apps.map { it.first }.toTypedArray()) { _, w ->
                item.linkType = "app"; item.link = apps[w].second; item.linkLabel = apps[w].first; render()
            }
            .show()
    }

    private fun save() {
        collect()
        if (item.title.isBlank()) { info("חסר שם", "תן שם לשיעור או ליעד."); return }
        if (item.isUnits && item.parts.isEmpty()) { info("הרשימה ריקה", "הוסף לפחות יחידה אחת (שורה אחת לכל יחידה), או צור רשימה ממוספרת."); return }
        val i = Store.items.indexOfFirst { it.id == item.id }
        if (i >= 0) Store.items[i] = item else Store.items.add(item)
        Store.save()
        Reminders.schedule(this)
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("למחוק את \"${item.title}\"?")
            .setMessage("ההיסטוריה של השיעור תימחק. אפשר במקום זה להסתיר אותו מהמדף.")
            .setPositiveButton("מחיקה") { _, _ ->
                Store.items.removeAll { it.id == item.id }
                Store.save(); Reminders.schedule(this); finish()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    companion object { const val REQ_BOOK = 11 }
}
