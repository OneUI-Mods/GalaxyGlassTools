package dev.ryuya.galaxyglass

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import java.io.IOException
import kotlin.math.max

class LicensesActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        val root = licenseScreen(getString(R.string.license_title))
        val components = runCatching { readLicenseCatalog() }.getOrElse {
            root.addView(licenseText(getString(R.string.license_unavailable)))
            return
        }

        val searchBox = TextInputLayout(this).apply {
            hint = getString(R.string.license_search)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        }
        val search = TextInputEditText(searchBox.context).apply { isSingleLine = true }
        searchBox.addView(search, LinearLayout.LayoutParams(-1, -2))
        root.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(licenseDp(20), licenseDp(8), licenseDp(20), licenseDp(12))
        })

        val list = ListView(this).apply {
            divider = null
            dividerHeight = licenseDp(8)
            setPadding(licenseDp(20), 0, licenseDp(20), licenseDp(12))
            clipToPadding = false
        }
        val empty = licenseText(getString(R.string.license_no_results)).apply { gravity = Gravity.CENTER }
        root.addView(FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
            list.emptyView = empty
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val adapter = LicenseAdapter(components)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
            startActivity(Intent(this, LicenseDetailActivity::class.java).putExtra(LICENSE_ID, item.id))
        }
        search.setText(state?.getString(QUERY).orEmpty())
        adapter.search(search.text.toString())
        list.setSelectionFromTop(state?.getInt(FIRST) ?: 0, state?.getInt(TOP) ?: 0)
        search.doAfterTextChanged {
            adapter.search(it.toString())
            list.setSelection(0)
        }
        root.requestFocus()
        queryView = search
        listView = list
    }

    private var queryView: TextInputEditText? = null
    private var listView: ListView? = null

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(QUERY, queryView?.text?.toString())
        listView?.let {
            out.putInt(FIRST, it.firstVisiblePosition)
            out.putInt(TOP, it.getChildAt(0)?.top ?: 0)
        }
    }

    private inner class LicenseAdapter(private val all: List<LicenseEntry>) : BaseAdapter() {
        private var filtered = all

        fun search(query: String) {
            val needle = query.trim()
            filtered = all.filter { item ->
                listOf(item.title, item.id, item.summary).any { it.contains(needle, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }

        override fun getCount() = filtered.size
        override fun getItem(position: Int) = filtered[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val card = recycled as? MaterialCardView ?: newRow()
            card.requireViewById<MaterialTextView>(android.R.id.text1).text = item.title
            card.requireViewById<MaterialTextView>(android.R.id.text2).text = item.summary
            return card
        }

        private fun newRow() = MaterialCardView(this@LicensesActivity).apply {
            radius = licenseDp(18).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(licenseDp(16), licenseDp(12), licenseDp(16), licenseDp(12))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(licenseText("", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply {
                        id = android.R.id.text1
                    })
                    addView(licenseText("").apply {
                        id = android.R.id.text2
                        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_license_details)
                    setColorFilter(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(licenseDp(20), licenseDp(20)).apply { marginStart = licenseDp(12) })
            })
        }
    }

    companion object {
        internal const val LICENSE_ID = "license_id"
        private const val QUERY = "license_query"
        private const val FIRST = "license_first"
        private const val TOP = "license_top"
    }
}

internal data class LicenseEntry(
    val id: String,
    val title: String,
    val summary: String,
    val license: String,
    val asset: String,
    val source: String,
)

internal fun Context.readLicenseCatalog(): List<LicenseEntry> = buildList {
    add(LicenseEntry("app", getString(R.string.glass_title), "MIT", "MIT", "APP_LICENSE.txt",
        "https://github.com/OneUI-Mods/GalaxyGlassTools"))
    assets.open("LICENSE_CATALOG.tsv").bufferedReader().useLines { lines ->
        for (line in lines.filter(String::isNotBlank)) {
            val fields = line.split('\t')
            val coordinates = fields.first().split(':')
            if (coordinates.size != 3 || fields.size != 4) throw IOException("Invalid license catalog")
            val (group, artifact, version) = coordinates
            val license = fields[1]
            val summary = getString(
                if (group == "io.github.libxposed" && artifact == "api") R.string.license_reference
                else R.string.license_artifact_summary, group, version, license,
            )
            add(LicenseEntry(fields[0], artifact, summary, license, "licenses/${fields[2]}", fields[3]))
        }
    }
}

internal fun AppCompatActivity.licenseScreen(title: String): LinearLayout {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
        setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
    }
    root.addView(MaterialToolbar(this).apply {
        this.title = title
        setNavigationIcon(R.drawable.ic_back)
        setNavigationIconTint(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        setNavigationContentDescription(R.string.back)
        setNavigationOnClickListener { finish() }
    }, LinearLayout.LayoutParams(-1, licenseDp(56)))
    setContentView(root)
    val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
    val useDarkIcons = ColorUtils.calculateLuminance(surface) > 0.5
    WindowCompat.getInsetsController(window, root).apply {
        isAppearanceLightStatusBars = useDarkIcons
        isAppearanceLightNavigationBars = useDarkIcons
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(safe.left, safe.top, safe.right, max(safe.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(root)
    return root
}

internal fun Context.licenseDp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.licenseText(
    value: String,
    appearance: Int = com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
) = MaterialTextView(this).apply {
    setTextAppearance(appearance)
    text = value
}
