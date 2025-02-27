/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.viewModels
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.material.shape.MaterialShapeDrawable
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.BaseGalleryInfo
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.databinding.ReaderActivityBinding
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.gallery.ArchivePageLoader
import com.hippo.ehviewer.gallery.EhPageLoader
import com.hippo.ehviewer.gallery.PageLoader2
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.ui.EhActivity
import com.hippo.ehviewer.ui.setMD3Content
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.awaitActivityResult
import com.hippo.ehviewer.util.getParcelableCompat
import com.hippo.ehviewer.util.getParcelableExtraCompat
import com.hippo.ehviewer.util.getValue
import com.hippo.ehviewer.util.isAtLeastO
import com.hippo.ehviewer.util.isAtLeastP
import com.hippo.ehviewer.util.isAtLeastQ
import com.hippo.ehviewer.util.lazyMut
import com.hippo.ehviewer.util.requestPermission
import com.hippo.ehviewer.util.setValue
import com.hippo.unifile.asUniFile
import com.hippo.unifile.displayPath
import dev.chrisbanes.insetter.applyInsetter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.OrientationType
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsSheet
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.logcat
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import moe.tarsin.coroutines.runSuspendCatching
import splitties.systemservices.clipboardManager

class GalleryModel : ViewModel() {
    var galleryProvider: PageLoader2? = null
    override fun onCleared() {
        super.onCleared()
        galleryProvider?.stop()
        galleryProvider = null
    }
}

class ReaderActivity : EhActivity() {
    lateinit var binding: ReaderActivityBinding
    private var mAction: String? = null
    private var mUri: Uri? = null
    private var mGalleryInfo: BaseGalleryInfo? = null
    private var mPage: Int = -1
    private val vm: GalleryModel by viewModels()
    private val dialogState = DialogState()

    /**
     * Whether the menu is currently visible.
     */
    var menuVisible by mutableStateOf(false)
    private var mGalleryProvider by lazyMut { vm::galleryProvider }
    private var mCurrentIndex: Int
        get() = mGalleryProvider!!.startPage
        set(value) {
            mGalleryProvider!!.startPage = value
        }

    private val galleryDetailUrl: String?
        get() = mGalleryInfo?.run { EhUrl.getGalleryDetailUrl(gid, token) }

    private suspend fun buildProvider(replace: Boolean = false): PageLoader2? {
        mGalleryProvider?.let {
            if (replace) it.stop() else return it
        }

        val provider = when (mAction) {
            ACTION_EH -> {
                mGalleryInfo?.let {
                    DownloadManager.getDownloadInfo(it.gid)?.archiveFile
                        ?.let { file -> ArchivePageLoader(file, it.gid, mPage) }
                        ?: EhPageLoader(it, mPage)
                }
            }

            Intent.ACTION_VIEW -> {
                mUri?.run {
                    ArchivePageLoader(asUniFile()) { invalidator ->
                        runCatching {
                            dialogState.awaitInputText(
                                title = getString(R.string.archive_need_passwd),
                                hint = getString(R.string.archive_passwd),
                            ) {
                                if (it.isBlank()) {
                                    getString(R.string.passwd_cannot_be_empty)
                                } else if (invalidator(it)) {
                                    null
                                } else {
                                    getString(R.string.passwd_wrong)
                                }
                            }
                        }.onFailure {
                            finish()
                        }.getOrThrow()
                    }
                }
            }

            else -> null
        }
        if (provider == null) {
            makeToast(R.string.error_reading_failed)
            finish()
        }
        return provider
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        mAction = intent.action
        mUri = intent.data
        mGalleryInfo = intent.getParcelableExtraCompat(KEY_GALLERY_INFO)
        mPage = intent.getIntExtra(KEY_PAGE, -1)
    }

    private fun onInit() {
        handleIntent(intent)
    }

    private fun onRestore(savedInstanceState: Bundle) {
        mAction = savedInstanceState.getString(KEY_ACTION)
        mUri = savedInstanceState.getParcelableCompat(KEY_URI)
        mGalleryInfo = savedInstanceState.getParcelableCompat(KEY_GALLERY_INFO)
        mPage = savedInstanceState.getInt(KEY_PAGE, -1)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
        lifecycleScope.launchIO {
            buildProvider(true)?.let {
                mGalleryProvider = it
                it.start()
                if (it.awaitReady()) {
                    withUIContext {
                        totalPage = it.size
                        viewer?.setGalleryProvider(it)
                        moveToPageIndex(0)
                    }
                } else {
                    makeToast(R.string.error_reading_failed)
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ACTION, mAction)
        outState.putParcelable(KEY_URI, mUri)
        if (mGalleryInfo != null) {
            outState.putParcelable(KEY_GALLERY_INFO, mGalleryInfo)
        }
        outState.putInt(KEY_PAGE, mPage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isAtLeastO) {
            window.colorMode = if (Image.isWideColorGamut && ReaderPreferences.wideColorGamut().get()) {
                ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
        if (savedInstanceState == null) {
            onInit()
        } else {
            onRestore(savedInstanceState)
        }
        binding = ReaderActivityBinding.inflate(layoutInflater)
        binding.dialogStub.setMD3Content {
            val brightness by Settings.customBrightness.collectAsState()
            val brightnessValue by Settings.customBrightnessValue.collectAsState()
            val colorOverlayEnabled by Settings.colorFilter.collectAsState()
            val colorOverlay by Settings.colorFilterValue.collectAsState()
            val colorOverlayMode by Settings.colorFilterMode.collectAsState {
                when (it) {
                    1 -> BlendMode.Multiply
                    2 -> BlendMode.Screen
                    3 -> BlendMode.Overlay
                    4 -> BlendMode.Lighten
                    5 -> BlendMode.Darken
                    else -> BlendMode.SrcOver
                }
            }
            dialogState.Intercept()

            val showSeekbar by Settings.showReaderSeekbar.collectAsState()
            val sliderValueFlow = remember { MutableStateFlow(-1) }
            LaunchedEffect(sliderValueFlow) {
                sliderValueFlow.drop(1).debounce(200).collect {
                    moveToPageIndex(it)
                }
            }
            ReaderAppBars(
                visible = menuVisible,
                isRtl = isRtl,
                showSeekBar = showSeekbar,
                currentPage = currentPage,
                totalPages = totalPage,
                onSliderValueChange = {
                    isScrollingThroughPages = true
                    sliderValueFlow.value = it
                    currentPage = it + 1
                },
                onClickSettings = { readerSettingSheetDialog?.show() },
            )

            ReaderContentOverlay(
                brightness = { brightnessValue }.takeIf { brightness && brightnessValue < 0 },
                color = { colorOverlay }.takeIf { colorOverlayEnabled },
                colorBlendMode = colorOverlayMode,
            )
        }
        setContentView(binding.root)
        lifecycleScope.launchIO {
            buildProvider()?.let {
                mGalleryProvider = it
                it.start()
                if (it.awaitReady()) {
                    withUIContext { setGallery() }
                } else {
                    makeToast(R.string.error_reading_failed)
                    finish()
                }
            }
        }

        config = ReaderConfig()
        initializeMenu()
    }

    fun retryPage(index: Int, orgImg: Boolean = false) {
        mGalleryProvider?.retryPage(index, orgImg)
    }

    fun restartGalleryProvider() {
        mGalleryProvider?.let {
            it.restart()
            viewer?.refreshAdapter()
        }
    }

    private fun setGallery() {
        if (mGalleryProvider?.isReady != true) return

        totalPage = mGalleryProvider!!.size
        viewer?.destroy()
        viewer = ReadingModeType.toViewer(ReaderPreferences.defaultReadingMode().get(), this)
        isRtl = viewer is R2LPagerViewer
        updateViewerInset(ReaderPreferences.fullscreen().get())
        binding.viewerContainer.removeAllViews()
        setOrientation(ReaderPreferences.defaultOrientationType().get())
        binding.viewerContainer.addView(viewer?.getView())
        viewer?.setGalleryProvider(mGalleryProvider!!)
        moveToPageIndex(mCurrentIndex)
    }

    override fun onDestroy() {
        super.onDestroy()
        config = null
        viewer?.destroy()
        viewer = null
        readerSettingSheetDialog?.dismiss()
        readerSettingSheetDialog = null
    }

    private suspend fun makeToast(text: String) = withUIContext {
        Toast.makeText(this@ReaderActivity, text, Toast.LENGTH_SHORT).show()
    }

    private suspend fun makeToast(@StringRes resId: Int) = makeToast(getString(resId))

    private fun provideImage(index: Int): Uri? {
        return AppConfig.externalTempDir?.let { dir ->
            mGalleryProvider?.saveToDir(index, dir.asUniFile())?.name?.let {
                FileProvider.getUriForFile(
                    this,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    File(dir, it),
                )
            }
        }
    }

    fun shareImage(index: Int) {
        lifecycleScope.launchIO {
            val uri = provideImage(index) ?: return@launchIO makeToast(R.string.error_cant_save_image)
            val intent = Intent()
            intent.action = Intent.ACTION_SEND
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            mGalleryInfo?.run {
                intent.putExtra(Intent.EXTRA_TEXT, EhUrl.getGalleryDetailUrl(gid, token))
            }

            val extension = FileUtils.getExtensionFromFilename(uri.path)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
            intent.setDataAndType(uri, mimeType)

            try {
                startActivity(Intent.createChooser(intent, getString(R.string.share_image)))
            } catch (e: Throwable) {
                makeToast(R.string.error_cant_find_activity)
            }
        }
    }

    fun copyImage(index: Int) {
        lifecycleScope.launchIO {
            val uri = provideImage(index) ?: return@launchIO makeToast(R.string.error_cant_save_image)
            val clipData = ClipData.newUri(contentResolver, "ehviewer", uri)
            clipboardManager.setPrimaryClip(clipData)
            makeToast(R.string.copied_to_clipboard)
        }
    }

    fun saveImage(index: Int) {
        mGalleryProvider ?: return

        lifecycleScope.launchIO {
            val granted = isAtLeastQ || requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (granted) {
                val filename = mGalleryProvider!!.getImageFilename(index)
                val extension = FileUtils.getExtensionFromFilename(filename)
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"

                val realPath: String
                val resolver = contentResolver
                val values = ContentValues()
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                values.put(MediaStore.Images.Media.DATE_ADDED, Clock.System.now().toEpochMilliseconds())
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (isAtLeastQ) {
                    realPath = Environment.DIRECTORY_PICTURES + File.separator + AppConfig.APP_DIRNAME
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, realPath)
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val path = File(dir, AppConfig.APP_DIRNAME)
                    realPath = path.toString()
                    if (!FileUtils.ensureDirectory(path)) {
                        return@launchIO makeToast(R.string.error_cant_save_image)
                    }
                    values.put(MediaStore.MediaColumns.DATA, realPath + File.separator + filename)
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@launchIO makeToast(R.string.error_cant_save_image)
                if (!mGalleryProvider!!.save(index, imageUri.asUniFile())) {
                    try {
                        resolver.delete(imageUri, null, null)
                    } catch (e: Exception) {
                        logcat(e)
                    }
                    return@launchIO makeToast(R.string.error_cant_save_image)
                } else if (isAtLeastQ) {
                    val contentValues = ContentValues()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, contentValues, null, null)
                }

                makeToast(getString(R.string.image_saved, realPath + File.separator + filename))
            } else {
                makeToast(R.string.permission_denied)
            }
        }
    }

    fun saveImageTo(index: Int) {
        lifecycleScope.launchIO {
            val filename = mGalleryProvider?.getImageFilename(index) ?: return@launchIO
            val extension = FileUtils.getExtensionFromFilename(filename)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
            runSuspendCatching {
                awaitActivityResult(CreateDocument(mimeType), filename)?.let {
                    mGalleryProvider!!.save(index, it.asUniFile())
                    makeToast(getString(R.string.image_saved, it.displayPath))
                }
            }.onFailure {
                logcat(it)
                makeToast(R.string.error_cant_find_activity)
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)

        val url = galleryDetailUrl
        if (url != null) {
            outContent.webUri = Uri.parse(url)
        }
    }

    companion object {
        const val ACTION_EH = "eh"
        const val KEY_ACTION = "action"
        const val KEY_URI = "uri"
        const val KEY_GALLERY_INFO = "gallery_info"
        const val KEY_PAGE = "page"
    }

    // Tachiyomi funcs

    var isScrollingThroughPages = false
        private set

    /**
     * Viewer used to display the pages (pager, webtoon, ...).
     */
    var viewer: BaseViewer? = null
        private set

    // We don't know if the device has cutout since the insets are not applied yet
    @get:ChecksSdkIntAtLeast(Build.VERSION_CODES.P)
    val hasCutout = isAtLeastP

    private var config: ReaderConfig? = null

    private val windowInsetsController by lazy {
        WindowInsetsControllerCompat(
            window,
            binding.root,
        )
    }

    private var readerSettingSheetDialog: ReaderSettingsSheet? = null

    /**
     * Sets the visibility of the menu according to [visible] and with an optional parameter to
     * [animate] the views.
     */
    fun setMenuVisibility(visible: Boolean) {
        menuVisible = visible
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())

            if (ReaderPreferences.showPageNumber().get()) {
                config?.setPageNumberVisibility(false)
            }
        } else {
            if (ReaderPreferences.fullscreen().get()) {
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
                windowInsetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            if (ReaderPreferences.showPageNumber().get()) {
                config?.setPageNumberVisibility(true)
            }
        }
    }

    private var currentPage by mutableIntStateOf(-1)
    private var totalPage by mutableIntStateOf(-1)
    private var isRtl by mutableStateOf(false)

    /**
     * Initializes the reader menu. It sets up click listeners and the initial visibility.
     */
    @SuppressLint("PrivateResource")
    private fun initializeMenu() {
        binding.dialogStub.applyInsetter {
            type(navigationBars = true) {
                margin(vertical = true, horizontal = true)
            }
        }

        binding.pageNumber.setMD3Content {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodySmall,
            ) {
                PageIndicatorText(
                    currentPage = currentPage,
                    totalPages = totalPage,
                )
            }
        }

        readerSettingSheetDialog = ReaderSettingsSheet(this@ReaderActivity)

        val toolbarBackground = MaterialShapeDrawable.createWithElevationOverlay(this).apply {
            elevation = resources.getDimension(com.google.android.material.R.dimen.m3_sys_elevation_level2)
            alpha = if (isNightMode()) 230 else 242 // 90% dark 95% light
        }

        val toolbarColor = ColorUtils.setAlphaComponent(
            toolbarBackground.resolvedTintColor,
            toolbarBackground.alpha,
        )

        window.statusBarColor = toolbarColor
        window.navigationBarColor = toolbarColor

        // Set initial visibility
        setMenuVisibility(menuVisible)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewer ?: return
        val page = mGalleryProvider?.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage) {
        // Set bottom page number
        currentPage = page.number

        mCurrentIndex = page.index
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        lifecycleScope.launch {
            dialogState.dialog {
                ModalBottomSheet(
                    onDismissRequest = { it.cancel() },
                    windowInsets = WindowInsets(0),
                ) {
                    ReaderPageSheet(page) { it.cancel() }
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!menuVisible)
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = OrientationType.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    fun updateViewerInset(fullscreen: Boolean) {
        viewer?.getView()?.applyInsetter {
            if (!fullscreen) {
                type(navigationBars = true, statusBars = true) {
                    padding()
                }
            }
        }
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        /**
         * Initializes the reader subscriptions.
         */
        init {
            ReaderPreferences.defaultReadingMode().changes()
                .drop(1)
                .onEach { setGallery() }
                .launchIn(lifecycleScope)

            ReaderPreferences.defaultOrientationType().changes()
                .drop(1)
                .onEach { setGallery() }
                .launchIn(lifecycleScope)

            ReaderPreferences.cropBorders().changes()
                .onEach { restartGalleryProvider() }
                .launchIn(lifecycleScope)

            ReaderPreferences.readerTheme().changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundResource(
                        when (theme) {
                            0 -> android.R.color.white
                            2 -> R.color.reader_background_dark
                            3 -> automaticBackgroundColor()
                            else -> android.R.color.black
                        },
                    )
                }
                .launchIn(lifecycleScope)

            ReaderPreferences.showPageNumber().changes()
                .onEach { setPageNumberVisibility(it) }
                .launchIn(lifecycleScope)

            if (hasCutout) {
                setCutoutShort(ReaderPreferences.cutoutShort().get())
                ReaderPreferences.cutoutShort().changes()
                    .drop(1)
                    .onEach {
                        readerSettingSheetDialog?.hide()
                        setCutoutShort(it)
                        recreate()
                    }
                    .launchIn(lifecycleScope)
            }

            ReaderPreferences.keepScreenOn().changes()
                .onEach { setKeepScreenOn(it) }
                .launchIn(lifecycleScope)

            ReaderPreferences.customBrightness().changes()
                .onEach { setCustomBrightness(it) }
                .launchIn(lifecycleScope)

            merge(
                ReaderPreferences.grayscale().changes(),
                ReaderPreferences.invertedColors().changes(),
            )
                .onEach {
                    setLayerPaint(
                        ReaderPreferences.grayscale().get(),
                        ReaderPreferences.invertedColors().get(),
                    )
                }
                .launchIn(lifecycleScope)

            ReaderPreferences.fullscreen().changes()
                .onEach {
                    WindowCompat.setDecorFitsSystemWindows(window, !it)
                    updateViewerInset(it)
                }
                .launchIn(lifecycleScope)
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                R.color.reader_background_dark
            } else {
                android.R.color.white
            }
        }

        /**
         * Sets the visibility of the bottom page indicator according to [visible].
         */
        fun setPageNumberVisibility(visible: Boolean) {
            binding.pageNumber.isVisible = visible
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun setCutoutShort(enabled: Boolean) {
            window.attributes.layoutInDisplayCutoutMode = when (enabled) {
                true -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                false -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        @OptIn(FlowPreview::class)
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                ReaderPreferences.customBrightnessValue().changes()
                    .sample(100)
                    .onEach { setCustomBrightnessValue(it) }
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }

                value < 0 -> {
                    0.01f
                }

                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }
        }

        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) {
                getCombinedPaint(
                    grayscale,
                    invertedColors,
                )
            } else {
                null
            }
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }
}
