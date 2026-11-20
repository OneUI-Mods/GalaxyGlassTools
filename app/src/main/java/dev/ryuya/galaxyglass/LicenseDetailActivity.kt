package dev.ryuya.galaxyglass

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.io.IOException

class LicenseDetailActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        val item = runCatching { readLicenseCatalog() }.getOrNull()?.firstOrNull {
            it.id == intent.getStringExtra(LicensesActivity.LICENSE_ID)
        }
        val root = licenseScreen(item?.title ?: getString(R.string.license_title))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(licenseDp(20), licenseDp(12), licenseDp(20), licenseDp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (item == null) {
            content.addView(licenseText(getString(R.string.license_unavailable)))
            return
        }
        content.addView(licenseText(item.summary, com.google.android.material.R.style.TextAppearance_Material3_TitleSmall))
        val source = item.source.toUri()
        if (source.scheme == "https" || source.scheme == "http") {
            content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.license_source)
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, source))
                    } catch (_: ActivityNotFoundException) {
                        Snackbar.make(root, R.string.license_browser_unavailable, Snackbar.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = licenseDp(8) })
        }
        val fullText = try {
            assets.open(item.asset).bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            getString(R.string.license_unavailable)
        }
        content.addView(licenseText(fullText).apply {
            setTextIsSelectable(true)
            setLineSpacing(licenseDp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = licenseDp(16) })
        scroll.post { scroll.scrollTo(0, state?.getInt(SCROLL) ?: 0) }
        scrollView = scroll
    }

    private var scrollView: ScrollView? = null

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(SCROLL, scrollView?.scrollY ?: 0)
    }

    companion object {
        private const val SCROLL = "license_scroll"
    }
}
