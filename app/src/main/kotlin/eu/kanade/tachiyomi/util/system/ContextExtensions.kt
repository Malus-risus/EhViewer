package eu.kanade.tachiyomi.util.system

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.view.ContextThemeWrapper
import com.hippo.ehviewer.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlin.math.roundToInt

@ColorInt
fun Context.getResourceColor(@AttrRes resource: Int, alphaFactor: Float = 1f): Int {
    val typedArray = obtainStyledAttributes(intArrayOf(resource))
    val color = typedArray.getColor(0, 0)
    typedArray.recycle()

    return if (alphaFactor != 1f) {
        val alpha = (color.alpha * alphaFactor).roundToInt()
        Color.argb(alpha, color.red, color.green, color.blue)
    } else color
}

val Int.dpToPx: Int
    get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()

val Int.pxToDp: Float
    get() = this / Resources.getSystem().displayMetrics.density

val Resources.isLTR: Boolean
    get() = configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR

val Context.displayCompat: Display?
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }

val Context.animatorDurationScale: Float
    get() = Settings.Global.getFloat(
        contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )

private const val TABLET_UI_REQUIRED_SCREEN_WIDTH_DP = 720
private const val TABLET_UI_MIN_SCREEN_WIDTH_PORTRAIT_DP = 700
private const val TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP = 600

fun Context.isTabletUi(): Boolean = resources.configuration.isTabletUi()

fun Configuration.isTabletUi(): Boolean = 
    smallestScreenWidthDp >= TABLET_UI_REQUIRED_SCREEN_WIDTH_DP

fun Configuration.isAutoTabletUiAvailable(): Boolean = 
    smallestScreenWidthDp >= TABLET_UI_MIN_SCREEN_WIDTH_LANDSCAPE_DP

fun Context.isNightMode(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

fun Context.isNavigationBarNeedsScrim(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            resources.getBoolean(R.bool.config_navBarNeedsScrim)

fun Context.createReaderThemeContext(): Context {
    val readerTheme = ReaderPreferences.readerTheme().get()
    val isDarkBackground = when (readerTheme) {
        1, 2 -> true
        3 -> isNightMode()
        else -> false
    }
    val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val expected = if (isDarkBackground) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    if (currentNightMode != expected) {
        val overrideConf = Configuration().apply { setTo(resources.configuration) }
        overrideConf.uiMode = overrideConf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or expected
        val wrappedContext = ContextThemeWrapper(this, R.style.AppTheme)
        wrappedContext.applyOverrideConfiguration(overrideConf)
        return wrappedContext
    }
    return this
}
