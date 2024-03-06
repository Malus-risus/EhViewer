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
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.PrefSpinnerBinding
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.util.system.getResourceColor

class MaterialSpinnerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val entries: List<String>
    private var selectedPosition = 0
    private var popup: PopupMenu? = null

    private val emptyIcon by lazy { AppCompatResources.getDrawable(context, R.drawable.ic_blank_24dp) }
    private val checkmarkIcon by lazy { AppCompatResources.getDrawable(context, R.drawable.ic_check_24dp)?.mutate()?.apply { setTint(context.getResourceColor(android.R.attr.textColorPrimary)) } }

    private val binding: PrefSpinnerBinding

    var onItemSelectedListener: ((Int) -> Unit)? = null
        set(value) {
            field = value
            setupPopupMenu()
        }

    init {
        binding = PrefSpinnerBinding.inflate(LayoutInflater.from(context), this, true)
        context.withStyledAttributes(attrs, R.styleable.MaterialSpinnerView) {
            binding.title.text = getString(R.styleable.MaterialSpinnerView_title).orEmpty()
            entries = getTextArray(R.styleable.MaterialSpinnerView_android_entries)?.map(CharSequence::toString) ?: emptyList()
            binding.details.text = entries.firstOrNull().orEmpty()
        }
        setupPopupMenu()
    }

    private fun setupPopupMenu() {
        popup?.dismiss()
        popup = PopupMenu(context, this, Gravity.END, androidx.appcompat.R.attr.actionOverflowMenuStyle, 0)
        popup?.let { popupMenu ->
            entries.forEachIndexed { index, entry ->
                popupMenu.menu.add(0, index, 0, entry).also {
                    it.icon = emptyIcon
                }
            }

            (popupMenu.menu as? MenuBuilder)?.setOptionalIconsVisible(true)

            popupMenu.menu.forEach { it.icon = emptyIcon }
            popupMenu.menu[selectedPosition].icon = checkmarkIcon

            popupMenu.setOnMenuItemClickListener { menuItem ->
                val pos = menuClicked(menuItem)
                onItemSelectedListener?.invoke(pos)
                true
            }

            setOnTouchListener(popupMenu.dragToOpenListener)
            setOnClickListener {
                popupMenu.show()
            }
        }
    }

    fun setSelection(selection: Int) {
        if (selectedPosition in 0 until (popup?.menu?.size() ?: 0)) {
            popup?.menu?.getItem(selectedPosition)?.icon = emptyIcon
        }
        selectedPosition = selection
        popup?.menu?.getItem(selection)?.icon = checkmarkIcon
        binding.details.text = entries.getOrNull(selection).orEmpty()
    }

    fun bindToPreference(pref: Preference<Int>, offset: Int = 0, block: ((Int) -> Unit)? = null) {
        setSelection(pref.get() - offset)
        setupPopupMenu {
            pref.set(it + offset)
            block?.invoke(it)
        }
    }

    fun <T : Enum<T>> bindToPreference(pref: Preference<T>, clazz: Class<T>) {
        val enumConstants = clazz.enumConstants
        enumConstants?.indexOf(pref.get())?.let(this::setSelection)
        setupPopupMenu(pref, clazz)
    }

    fun bindToIntPreference(pref: Preference<Int>, @ArrayRes intValuesResource: Int, block: ((Int) -> Unit)? = null) {
        val intValues = resources.getStringArray(intValuesResource).mapNotNull(String::toIntOrNull)
        intValues.indexOf(pref.get()).also(this::setSelection)
        setupPopupMenu(pref, intValues, block)
    }

    private fun <T : Enum<T>> setupPopupMenu(preference: Preference<T>, clazz: Class<T>) {
        setupPopupMenu {
            val enumConstants = clazz.enumConstants
            enumConstants?.get(it)?.let { enumValue -> preference.set(enumValue) }
        }
    }

    private fun setupPopupMenu(preference: Preference<Int>, intValues: List<Int?>, block: ((Int) -> Unit)? = null) {
        setupPopupMenu {
            preference.set(intValues[it] ?: 0)
            block?.invoke(it)
        }
    }

    private fun setupPopupMenu(onItemClick: (Int) -> Unit) {
        popup?.dismiss()
        popup = PopupMenu(context, this, Gravity.END, androidx.appcompat.R.attr.actionOverflowMenuStyle, 0).apply {
            entries.forEachIndexed { index, entry ->
                menu.add(0, index, 0, entry).also { it.icon = emptyIcon }
            }
            (menu as? MenuBuilder)?.setOptionalIconsVisible(true)
            menu[selectedPosition].icon = checkmarkIcon

            setOnMenuItemClickListener { menuItem ->
                val pos = menuClicked(menuItem)
                onItemClick(pos)
                true
            }
        }

        setOnTouchListener(popup?.dragToOpenListener)
        setOnClickListener { popup?.show() }
    }

    private fun menuClicked(menuItem: MenuItem): Int {
        menuItem.itemId.also(this::setSelection)
    }
}
