package com.chdv.madaf

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.icu.text.DateFormat
import android.icu.util.ULocale
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Lays children out right-to-left in rows, wrapping when a row is full. */
class FlowLayout(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = paddingTop; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            val right = r - l - paddingRight - x   // RTL: start from the right edge
            c.layout(right - c.measuredWidth, y, right, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

fun Context.chip(label: String, on: Boolean, onClick: (() -> Unit)?): TextView =
    text(label, 15f, bold = true, color = if (on) Ink.WHITE else Ink.BLACK).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = if (on) rounded(Ink.BLACK, radius = dp(18).toFloat()) else rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(18).toFloat())
        setPadding(dp(14), dp(7), dp(14), dp(7))
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

/** A big square checkbox, drawn (no animation). */
fun Context.checkBox(checked: Boolean, partial: Boolean = false, onClick: () -> Unit): TextView =
    text(if (checked) "✓" else if (partial) "–" else "", 22f, bold = true, color = if (checked) Ink.WHITE else Ink.BLACK).apply {
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = if (checked) rounded(Ink.BLACK, radius = dp(10).toFloat()) else rounded(Ink.WHITE, Ink.BLACK, dp(2), dp(10).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.progressBar(fraction: Float, height: Int = 14, inverted: Boolean = false): View {
    val fg = if (inverted) Ink.WHITE else Ink.BLACK
    val bg = if (inverted) Ink.BLACK else Ink.WHITE
    val outer = LinearLayout(this).apply {
        background = rounded(bg, fg, dp(2), dp(height / 2 + 2).toFloat())
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(height))
    }
    val f = (fraction.coerceIn(0f, 1f) * 1000).toInt()
    if (f > 0) outer.addView(View(this).apply { background = rounded(fg, radius = dp(height / 2).toFloat()) }, LinearLayout.LayoutParams(0, MATCH, f.toFloat()))
    if (f < 1000) outer.addView(View(this), LinearLayout.LayoutParams(0, MATCH, (1000 - f).toFloat()))
    return outer
}

object HebDate {
    private val fmt by lazy { DateFormat.getDateInstance(DateFormat.LONG, ULocale("he_IL@calendar=hebrew")) }
    private val dayNames = arrayOf("יום ראשון", "יום שני", "יום שלישי", "יום רביעי", "יום חמישי", "יום שישי", "שבת קודש")

    fun hebrew(d: LocalDate): String = runCatching {
        fmt.format(Date.from(d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()))
    }.getOrDefault("")

    fun dayName(d: LocalDate) = dayNames[dowIndex(d)]
    fun short(d: LocalDate) = "${d.dayOfMonth}/${d.monthValue}"
}

/** Two number wheels (hours, 5-minute steps). */
fun BaseActivity.pickTime(initial: Int, onPick: (Int) -> Unit) {
    val h = NumberPicker(this).apply { minValue = 0; maxValue = 23; value = initial / 60; setFormatter { String.format("%02d", it) } }
    val mins = (0 until 60 step 5).map { String.format("%02d", it) }.toTypedArray()
    val m = NumberPicker(this).apply { minValue = 0; maxValue = mins.size - 1; displayedValues = mins; value = (initial % 60) / 5 }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(h); addView(text(":", 28f, bold = true).apply { setPadding(dp(12), 0, dp(12), 0) }); addView(m)
    }
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setTitle("שעת תזכורת")
        .setView(FrameLayout(this).apply { setPadding(0, dp(12), 0, 0); addView(row, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) })
        .setPositiveButton("אישור") { _, _ -> onPick(h.value * 60 + m.value * 5) }
        .setNegativeButton("ביטול", null)
        .show()
}

fun BaseActivity.pickNumber(title: String, min: Int, max: Int, initial: Int, onPick: (Int) -> Unit) {
    val p = NumberPicker(this).apply { minValue = min; maxValue = max; value = initial.coerceIn(min, max); wrapSelectorWheel = false }
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setTitle(title)
        .setView(FrameLayout(this).apply { addView(p, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) })
        .setPositiveButton("אישור") { _, _ -> onPick(p.value) }
        .setNegativeButton("ביטול", null)
        .show()
}

/** Opens the book or app linked to an item. */
fun Context.openLink(item: Item) {
    try {
        when (item.linkType) {
            "file" -> {
                val uri = Uri.parse(item.link)
                val type = contentResolver.getType(uri) ?: "*/*"
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "app" -> {
                val i = packageManager.getLaunchIntentForPackage(item.link) ?: throw ActivityNotFoundException()
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    } catch (e: Exception) {
        Toast.makeText(this, "לא ניתן לפתוח: ${item.linkLabel}", Toast.LENGTH_LONG).show()
    }
}

/**
 * The row for one item on a given day. Used by the main screen and the reminder screen.
 * [changed] is called after any mark, [edit] on long press, [openGoal] for unit lists.
 */
fun BaseActivity.itemRow(item: Item, d: LocalDate, changed: () -> Unit, edit: (() -> Unit)?, openGoal: (() -> Unit)?): View {
    val done = Store.isDone(item, d)
    val box = vbox().apply { setPadding(0, dp(10), 0, dp(10)) }
    val row = hbox()

    val hasParts = !item.isUnits && item.parts.isNotEmpty()
    val partsDone = if (hasParts) Store.partsDoneCount(item, d) else 0
    val showCheck = !(item.isUnits && item.freq == Freq.ONCE)
    if (showCheck) {
        row.addView(checkBox(done, partial = !done && partsDone > 0) {
            Store.setDone(item, d, !done); changed()
        })
    }

    val texts = vbox().apply { setPadding(if (showCheck) dp(14) else 0, 0, dp(8), 0) }
    val title = text(item.title, 19f, bold = true, color = if (done) Ink.GRAY else Ink.BLACK)
    if (done && !item.isUnits) title.paintFlags = title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    texts.addView(title)

    val meta = ArrayList<String>()
    if (item.note.isNotBlank()) meta += item.note
    if (item.isUnits && item.unitsTotal > 0) meta += "${item.unitsDone.size} מתוך ${item.unitsTotal}"
    val streak = Store.streak(item, Store.today())
    if (streak >= 2) meta += if (item.freq == Freq.WEEKLY) "$streak שבועות ברצף" else "$streak ימים ברצף"
    if (item.freq == Freq.WEEKLY) meta += "שבועי"
    Store.daysLeft(item, Store.today())?.let { left ->
        meta += when {
            left < 0 -> "עבר התאריך"
            left == 0L -> "היום יום אחרון"
            else -> "נשארו $left ימים"
        }
    }
    if (meta.isNotEmpty()) texts.addView(text(meta.joinToString("  ·  "), 14f, color = Ink.GRAY))
    row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))

    if (item.linkType.isNotEmpty()) {
        row.addView(button("פתיחה", primary = false, small = true) { openLink(item) })
    }
    box.addView(row)

    if (hasParts) {
        val flow = FlowLayout(this, dp(8)).apply { setPadding(if (showCheck) dp(54) else 0, dp(10), 0, 0) }
        item.parts.forEachIndexed { i, p ->
            flow.addView(chip(p, Store.isPartDone(item, i, d)) { Store.togglePart(item, i, d); changed() })
        }
        box.addView(flow)
    }

    if (item.isUnits) {
        val inner = vbox().apply { setPadding(if (showCheck) dp(54) else 0, dp(10), 0, 0) }
        if (item.unitsTotal == 0) {
            inner.addView(text("עדיין לא הוגדרו יחידות – לחץ לעריכה", 14f, color = Ink.GRAY))
        } else {
            inner.addView(progressBar(item.unitsDone.size.toFloat() / item.unitsTotal))
            val next = Store.nextUnit(item)
            val r = hbox().apply { lp(top = 10) }
            if (next >= 0) {
                r.addView(button("✓  ${item.parts[next]}", small = true) { Store.toggleUnit(item, next, d); changed() })
                r.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            } else {
                r.addView(text("🎉 סיימת!", 16f, bold = true))
            }
            if (openGoal != null) r.addView(button("כל הרשימה", primary = false, small = true) { openGoal() })
            inner.addView(r)
            val left = Store.daysLeft(item, Store.today())
            if (left != null && left > 0 && item.unitsLeft > 0) {
                val perDay = Math.ceil(item.unitsLeft.toDouble() / left).toInt()
                inner.addView(text("כדי לסיים בזמן: כ-$perDay ליום", 13f, color = Ink.GRAY).apply { lp(top = 6) })
            }
        }
        box.addView(inner)
    }

    if (edit != null) {
        box.isLongClickable = true
        box.setOnLongClickListener { edit(); true }
        texts.setOnClickListener { if (item.isUnits && openGoal != null) openGoal() else if (showCheck) { Store.setDone(item, d, !done); changed() } }
        texts.setOnLongClickListener { edit(); true }
    }
    return box
}
