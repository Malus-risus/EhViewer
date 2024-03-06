package eu.kanade.tachiyomi.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.annotation.ArrayRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.withStyledAttributes
import androidx.core.view.get
import androidx.core.view.forEach
import com.hippo.ehviewer.R
import com.hippo.ehviewer.databinding.PrefSpinnerBinding
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.util.system.getResourceColor

class MaterialSpinnerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding = PrefSpinnerBinding.inflate(LayoutInflater.from(context), this, true)

    private val emptyIcon by lazy {
        AppCompatResources.getDrawable(context, R.drawable.ic_blank_24dp)
    }
    private val checkmarkIcon by lazy {
        AppCompatResources.getDrawable(context, R.drawable.ic_check_24dp)?.mutate()?.apply {
            setTint(context.getResourceColor(android.R.attr.textColorPrimary))
        }
    }

    private var entries = emptyList<String>()
    private var selectedPosition = 0
    private var popup: PopupMenu? = null

    var onItemSelectedListener: ((Int) -> Unit)? = null
        set(value) {
            field = value
            createPopupMenu()
        }

    init {
        context.withStyledAttributes(attrs, R.styleable.MaterialSpinnerView) {
            binding.title.text = getString(R.styleable.MaterialSpinnerView_title) ?: ""
            val viewEntries = getTextArray(R.styleable.MaterialSpinnerView_android_entries)?.map { it.toString() }
            entries = viewEntries ?: emptyList()
            binding.details.text = entries.firstOrNull() ?: ""
        }
        createPopupMenu()
    }

    private fun createPopupMenu() {
        popup = PopupMenu(context, this, Gravity.END).apply {
            entries.forEachIndexed { index, entry ->
                menu.add(Menu.NONE, index, Menu.NONE, entry).apply {
                    icon = emptyIcon
                }
            }
            (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
            setSelectedPosition(selectedPosition) // Ensure the correct item is checked when popup is created

            setOnMenuItemClickListener {
                setSelectedPosition(it.itemId)
                onItemSelectedListener?.invoke(it.itemId)
                true
            }
        }

        setOnTouchListener(popup?.dragToOpenListener)
        setOnClickListener { popup?.show() }
    }

    private fun setSelectedPosition(position: Int) {
        if (position != selectedPosition && position >= 0 && position < entries.size) {
            popup?.menu?.get(selectedPosition)?.icon = emptyIcon
            selectedPosition = position
            popup?.menu?.get(selectedPosition)?.icon = checkmarkIcon
            binding.details.text = entries[position]
        }
    }

    fun setSelection(selection: Int) {
        setSelectedPosition(selection)
    }

    fun bindToPreference(pref: Preference<Int>, offset: Int = 0) {
        setSelection(pref.get() - offset)
        onItemSelectedListener = {
            pref.set(it + offset)
        }
    }

    fun <T : Enum<T>> bindToPreference(pref: Preference<T>, clazz: Class<T>) {
        val prefValue = pref.get()
        val enumConstants = clazz.enumConstants
        val position = enumConstants?.indexOf(prefValue) ?: -1

        setSelection(position)
        onItemSelectedListener = {
            val selectedEnum = enumConstants?.get(it)
            selectedEnum?.let { enumValue -> pref.set(enumValue) }
        }
    }

    fun bindToIntPreference(pref: Preference<Int>, @ArrayRes intValuesResource: Int) {
        val intValues = resources.getIntArray(intValuesResource).toList()
        val prefValue = pref.get()
        val position = intValues.indexOf(prefValue)

        setSelection(position)
        onItemSelectedListener = {
            pref.set(intValues[it])
        }
    }
}
