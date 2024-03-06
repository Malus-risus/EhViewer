package eu.kanade.tachiyomi.widget

import android.annotation.SuppressLint
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
import com.hippo.ehviewer.R
import com.hippo.ehviewer.databinding.PrefSpinnerBinding
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.util.system.getResourceColor

class MaterialSpinnerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var entries = emptyList<String>()
    private var selectedPosition = 0
    private lateinit var binding: PrefSpinnerBinding
    private var popup: PopupMenu? = null

    var onItemSelectedListener: ((Int) -> Unit)? = null
        set(value) {
            field = value
            value?.let {
                 initializePopup()
            }
        }

    private val emptyIcon by lazy { AppCompatResources.getDrawable(context, R.drawable.ic_blank_24dp) }
    private val checkmarkIcon by lazy {
        AppCompatResources.getDrawable(context, R.drawable.ic_check_24dp)?.also { drawable ->
            drawable.mutate().setTint(context.getResourceColor(android.R.attr.textColorPrimary))
        }
    }

    init {
        binding = PrefSpinnerBinding.inflate(LayoutInflater.from(context), this, true)
        context.withStyledAttributes(set = attrs, attrs = R.styleable.MaterialSpinnerView) {
            binding.title.text = getString(R.styleable.MaterialSpinnerView_title).orEmpty()
            entries = getTextArray(R.styleable.MaterialSpinnerView_android_entries)?.map { it.toString() } ?: emptyList()
            binding.details.text = entries.firstOrNull().orEmpty()
        }
    }

    private fun initializePopup() {
        if (popup == null) {
            popup = PopupMenu(context, this, Gravity.END, androidx.appcompat.R.attr.actionOverflowMenuStyle, 0).apply {
                menu.apply {
                    entries.forEachIndexed { index, entry -> add(0, index, 0, entry).icon = emptyIcon }
                    (this as? MenuBuilder)?.setOptionalIconsVisible(true)
                }
                setOnMenuItemClickListener {
                    val pos = menuClicked(it)
                    onItemSelectedListener?.invoke(pos)
                    true
                }
                updateMenuIcons()
            }
        }
        setTouchListener()
    }

    private fun setTouchListener() {
        popup?.let {
            setOnTouchListener(it.dragToOpenListener)
            setOnClickListener { it.show() }
        }
    }

    private fun menuClicked(menuItem: MenuItem): Int {
        val pos = menuItem.itemId
        setSelection(pos)
        return pos
    }

    fun setSelection(selection: Int) {
        selectedPosition = selection.coerceIn(entries.indices)
        binding.details.text = entries.getOrNull(selectedPosition).orEmpty()
        updateMenuIcons()
    }

    private fun updateMenuIcons() {
        popup?.menu?.apply {
            forEach { it.icon = emptyIcon }
            get(selectedPosition)?.icon = checkmarkIcon
        }
    }

    fun bindToPreference(pref: Preference<Int>, offset: Int = 0, block: ((Int) -> Unit)? = null) {
        setSelection(pref.get() - offset)
        initializePopup {
            preference.set(it + offset)
            block?.invoke(it)
        }
    }

    fun <T : Enum<T>> bindToPreference(pref: Preference<T>, clazz: Class<T>) {
        val enumConstants = clazz.enumConstants
        enumConstants?.indexOf(pref.get())?.let { setSelection(it) }
        initializePopup {
            val enumValue = enumConstants?.get(it)
            enumValue?.let { value -> pref.set(value) }
        }
    }

    fun bindToIntPreference(pref: Preference<Int>, @ArrayRes intValuesResource: Int, block: ((Int) -> Unit)? = null) {
        val intValues = resources.getStringArray(intValuesResource).mapNotNull { it.toIntOrNull() }
        intValues.indexOf(pref.get()).let { setSelection(it) }
        initializePopup {
            pref.set(intValues[it])
            block?.invoke(it)
        }
    }

    private fun makeSettingsPopup(pref: Preference<Int>, offset: Int = 0, block: ((Int) -> Unit)? = null): PopupMenu {
        return createPopupMenu { pos ->
            pref.set(pos + offset)
            block?.invoke(pos)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun createPopupMenu(onItemClick: (Int) -> Unit): PopupMenu {
        val popup = PopupMenu(context, this, Gravity.END, androidx.appcompat.R.attr.actionOverflowMenuStyle, 0)
        entries.forEachIndexed { index, entry ->
            popup.menu.add(0, index, 0, entry)
        }
        (popup.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
        popup.menu.forEach {
            it.icon = emptyIcon
        }
        popup.menu[selectedPosition].icon = checkmarkIcon
        popup.setOnMenuItemClickListener { menuItem ->
            val pos = menuClicked(menuItem)
            onItemClick(pos)
            true
        }
        return popup
    }
}
