package com.example.minlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

private enum class Kind { APP, SETTINGS, EDIT, DONE }

private class Entry(
    val kind: Kind,
    val label: String,
    val icon: Drawable?,
    val packageName: String?,
    val launchIntent: Intent?
)

class MainActivity : Activity() {

    private lateinit var grid: GridView
    private lateinit var title: TextView
    private var loading = false
    private var editMode = false

    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Белый список. null = пользователь ещё ничего не выбирал, показываем всё.
    private fun allowed(): MutableSet<String>? =
        prefs.getStringSet(KEY_ALLOWED, null)?.toMutableSet()

    private fun saveAllowed(set: Set<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED, HashSet(set)).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0B1220"), Color.parseColor("#16263D"), Color.parseColor("#1B3B4F"))
            )
        }

        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        root.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ).apply { setMargins(dp(40), dp(24), 0, 0) })

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.parseColor("#C9D6E3"))
            textSize = 26f
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        root.addView(clock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { setMargins(0, dp(24), dp(40), 0) })

        grid = GridView(this).apply {
            numColumns = 5
            horizontalSpacing = dp(16)
            verticalSpacing = dp(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            clipToPadding = false
            setPadding(dp(40), dp(90), dp(40), dp(32))
            selector = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#33FFFFFF"))
                setStroke(dp(3), Color.parseColor("#8CC4FF"))
            }
            setDrawSelectorOnTop(false)
            isVerticalScrollBarEnabled = false
            cacheColorHint = Color.TRANSPARENT
        }
        root.addView(grid, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        editMode = false
        reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (editMode) { editMode = false; reload() }
        // Домашний экран кнопкой «Назад» не закрывается
    }

    private fun reload() {
        if (loading) return
        loading = true
        Thread {
            val entries = buildEntries()
            runOnUiThread { loading = false; showEntries(entries) }
        }.start()
    }

    private fun loadAllApps(): List<Entry> {
        val pm = packageManager
        val result = LinkedHashMap<String, Entry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || result.containsKey(pkg)) continue
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                result[pkg] = Entry(Kind.APP, info.loadLabel(pm).toString(), info.loadIcon(pm), pkg, launch)
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    private fun buildEntries(): List<Entry> {
        val all = loadAllApps()
        val pm = packageManager

        if (editMode) {
            return all + Entry(Kind.DONE, "Готово", null, null, null)
        }

        val allow = allowed()
        val visible = if (allow == null) all else all.filter { it.packageName in allow }

        val settings = Entry(
            Kind.SETTINGS, "Настройки",
            runCatching { pm.getApplicationIcon("com.android.settings") }.getOrNull(),
            null, Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val edit = Entry(Kind.EDIT, "Выбрать приложения", null, null, null)
        return visible + settings + edit
    }

    private fun showEntries(entries: List<Entry>) {
        title.text = if (editMode) "Отметьте приложения, которые нужно показывать" else "Мой экран"
        val adapter = AppAdapter(entries)
        grid.adapter = adapter

        grid.setOnItemClickListener { _, _, position, _ ->
            val e = entries[position]
            when (e.kind) {
                Kind.APP -> if (editMode) {
                    // В режиме выбора клик включает/выключает приложение
                    val set = allowed() ?: entries.mapNotNull { it.packageName }.toMutableSet()
                    if (!set.add(e.packageName!!)) set.remove(e.packageName)
                    saveAllowed(set)
                    adapter.notifyDataSetChanged()
                } else {
                    try { startActivity(e.launchIntent) } catch (ex: Exception) {
                        Toast.makeText(this, "Не удалось открыть: ${e.label}", Toast.LENGTH_SHORT).show()
                    }
                }
                Kind.SETTINGS -> try { startActivity(e.launchIntent) } catch (ex: Exception) {
                    Toast.makeText(this, "Настройки недоступны", Toast.LENGTH_SHORT).show()
                }
                Kind.EDIT -> { editMode = true; reload() }
                Kind.DONE -> { editMode = false; reload() }
            }
        }

        // Долгое нажатие OK: сведения о приложении (удалить / отключить)
        grid.setOnItemLongClickListener { _, _, position, _ ->
            val pkg = entries[position].packageName
            if (pkg != null && entries[position].kind == Kind.APP) {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$pkg")))
                } catch (ex: Exception) {
                    Toast.makeText(this, "Недоступно", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        grid.requestFocus()
    }

    private inner class AppAdapter(private val items: List<Entry>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = items[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(12))
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#1AFFFFFF"))
                }
                addView(ImageView(context).apply {
                    id = ICON_ID
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(76), dp(76)))
                addView(TextView(context).apply {
                    id = LABEL_ID
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) })
            }

            val icon = cell.findViewById<ImageView>(ICON_ID)
            val label = cell.findViewById<TextView>(LABEL_ID)
            icon.setImageDrawable(e.icon)
            icon.alpha = 1f
            label.text = e.label

            when (e.kind) {
                Kind.EDIT -> { icon.setImageDrawable(null); label.text = "✎\n${e.label}"; label.textSize = 15f }
                Kind.DONE -> { icon.setImageDrawable(null); label.text = "✔\n${e.label}" }
                else -> {}
            }

            if (editMode && e.kind == Kind.APP) {
                val allow = allowed()
                val on = allow == null || e.packageName in allow
                icon.alpha = if (on) 1f else 0.3f
                label.text = (if (on) "✔ " else "✖ ") + e.label
                label.setTextColor(if (on) Color.parseColor("#9BE3A6") else Color.parseColor("#8896A5"))
            } else {
                label.setTextColor(Color.WHITE)
            }
            return cell
        }
    }

    private companion object {
        const val ICON_ID = 0x7f0a0001
        const val LABEL_ID = 0x7f0a0002
        const val KEY_ALLOWED = "allowed_packages"
    }
}
