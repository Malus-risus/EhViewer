/*
 * Copyright (C) 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.client.data

import android.os.Parcelable
import androidx.annotation.IntDef
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.addQueryParameter
import com.hippo.ehviewer.client.addQueryParameterIfNotBlank
import com.hippo.ehviewer.client.ehUrl
import com.hippo.ehviewer.dao.QuickSearch
import com.hippo.ehviewer.ui.main.AdvanceTable
import com.hippo.ehviewer.util.toIntOrDefault
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import kotlinx.parcelize.Parcelize

@Parcelize
data class ListUrlBuilder(
    @get:Mode @Mode
    var mode: Int = MODE_NORMAL,
    private var mPrev: String? = null,
    var mNext: String? = null,
    // Reset to null after initial loading
    var mJumpTo: String? = null,
    private var mRange: Int = 0,
    var category: Int = EhUtils.NONE,
    private var mKeyword: String? = null,
    var hash: String? = null,
    var advanceSearch: Int = -1,
    var minRating: Int = -1,
    var pageFrom: Int = -1,
    var pageTo: Int = -1,
    var imagePath: String? = null,
) : Parcelable {

    fun setIndex(index: String, isNext: Boolean = true) {
        mNext = index.takeIf { isNext }
        mPrev = index.takeUnless { isNext }
        mRange = 0
    }

    fun setJumpTo(jumpTo: Int) {
        mJumpTo = jumpTo.takeUnless { it == 0 }?.toString()
    }

    fun setRange(range: Int) {
        mRange = range
        mPrev = null
        mNext = null
        mJumpTo = null
    }

    var keyword: String?
        get() = if (MODE_UPLOADER == mode) "uploader:$mKeyword" else mKeyword
        set(keyword) {
            mKeyword = keyword
        }

    constructor(q: QuickSearch) : this() {
        mode = q.mode
        this.category = q.category
        mKeyword = q.keyword
        advanceSearch = q.advanceSearch
        minRating = q.minRating
        pageFrom = q.pageFrom
        pageTo = q.pageTo
        mNext = q.name.substringAfterLast('@', "").takeIf { it.isNotEmpty() }
    }

    fun toQuickSearch(name: String): QuickSearch {
        return QuickSearch(
            name = name,
            mode = mode,
            category = category,
            keyword = mKeyword,
            advanceSearch = advanceSearch,
            minRating = minRating,
            pageFrom = pageFrom,
            pageTo = pageTo,
        )
    }

    fun equalsQuickSearch(q: QuickSearch?): Boolean {
        if (null == q) {
            return false
        }
        if (q.mode != mode) {
            return false
        }
        if (q.category != this.category) {
            return false
        }
        if (q.keyword != mKeyword) {
            return false
        }
        if (q.advanceSearch != advanceSearch) {
            return false
        }
        if (q.minRating != minRating) {
            return false
        }
        return if (q.pageFrom != pageFrom) {
            false
        } else {
            q.pageTo == pageTo
        }
    }

    /**
     * @param query xxx=yyy&mmm=nnn
     */
    constructor(query: String?) : this() {
        // TODO page
        if (query.isNullOrEmpty()) {
            return
        }
        val queries = query.split('&')
        var category = 0
        var keyword: String? = null
        var enableAdvanceSearch = false
        var advanceSearch = 0
        var enableMinRating = false
        var minRating = -1
        var enablePage = false
        var pageFrom = -1
        var pageTo = -1
        for (str in queries) {
            val index = str.indexOf('=')
            if (index < 0) {
                continue
            }
            val key = str.substring(0, index)
            val value = str.substring(index + 1)
            when (key) {
                "f_cats" -> {
                    val cats = value.toIntOrDefault(EhUtils.ALL_CATEGORY)
                    category = category or (cats.inv() and EhUtils.ALL_CATEGORY)
                }

                "f_doujinshi" -> if ("1" == value) {
                    category = category or EhUtils.DOUJINSHI
                }

                "f_manga" -> if ("1" == value) {
                    category = category or EhUtils.MANGA
                }

                "f_artistcg" -> if ("1" == value) {
                    category = category or EhUtils.ARTIST_CG
                }

                "f_gamecg" -> if ("1" == value) {
                    category = category or EhUtils.GAME_CG
                }

                "f_western" -> if ("1" == value) {
                    category = category or EhUtils.WESTERN
                }

                "f_non-h" -> if ("1" == value) {
                    category = category or EhUtils.NON_H
                }

                "f_imageset" -> if ("1" == value) {
                    category = category or EhUtils.IMAGE_SET
                }

                "f_cosplay" -> if ("1" == value) {
                    category = category or EhUtils.COSPLAY
                }

                "f_asianporn" -> if ("1" == value) {
                    category = category or EhUtils.ASIAN_PORN
                }

                "f_misc" -> if ("1" == value) {
                    category = category or EhUtils.MISC
                }

                "f_search" -> try {
                    keyword = URLDecoder.decode(value, "utf-8")
                } catch (e: UnsupportedEncodingException) {
                    // Ignore
                } catch (_: IllegalArgumentException) {
                }

                "advsearch" -> if ("1" == value) {
                    enableAdvanceSearch = true
                }

                "f_sh" -> if ("on" == value) {
                    advanceSearch = advanceSearch or AdvanceTable.SH
                }

                "f_sto" -> if ("on" == value) {
                    advanceSearch = advanceSearch or AdvanceTable.STO
                }

                "f_sfl" -> if ("on" == value) {
                    advanceSearch = advanceSearch or AdvanceTable.SFL
                }

                "f_sfu" -> if ("on" == value) {
                    advanceSearch = advanceSearch or AdvanceTable.SFU
                }

                "f_sft" -> if ("on" == value) {
                    advanceSearch = advanceSearch or AdvanceTable.SFT
                }

                "f_sr" -> if ("on" == value) {
                    enableMinRating = true
                }

                "f_srdd" -> minRating = value.toIntOrDefault(-1)
                "f_sp" -> if ("on" == value) {
                    enablePage = true
                }

                "f_spf" -> pageFrom = value.toIntOrDefault(-1)
                "f_spt" -> pageTo = value.toIntOrDefault(-1)
                "f_shash" -> hash = value
            }
        }
        this.category = category
        mKeyword = keyword
        if (enableAdvanceSearch) {
            this.advanceSearch = advanceSearch
            if (enableMinRating) {
                this.minRating = minRating
            } else {
                this.minRating = -1
            }
            if (enablePage) {
                this.pageFrom = pageFrom
                this.pageTo = pageTo
            } else {
                this.pageFrom = -1
                this.pageTo = -1
            }
        } else {
            this.advanceSearch = -1
        }
    }

    fun build(): String {
        return when (mode) {
            MODE_NORMAL, MODE_SUBSCRIPTION -> ehUrl(EhUrl.WATCHED_PATH.takeIf { mode == MODE_SUBSCRIPTION }) {
                if (category > 0) {
                    addQueryParameter("f_cats", (category.inv() and EhUtils.ALL_CATEGORY).toString())
                }
                val query = mKeyword?.let {
                    val index = Settings.languageFilter.value
                    GalleryInfo.S_LANG_TAGS.getOrNull(index)?.let { lang ->
                        "$it $lang"
                    } ?: it
                }
                addQueryParameterIfNotBlank("f_search", query)
                addQueryParameterIfNotBlank("prev", mPrev)
                addQueryParameterIfNotBlank("next", mNext)
                addQueryParameterIfNotBlank("seek", mJumpTo)
                addQueryParameterIfNotBlank("range", mRange.takeIf { it > 0 }?.toString())
                // Advance search
                if (advanceSearch > 0 || minRating > 0 || pageFrom > 0 || pageTo > 0) {
                    addQueryParameter("advsearch", "1")
                    if (advanceSearch and AdvanceTable.SH != 0) {
                        addQueryParameter("f_sh", "on")
                    }
                    if (advanceSearch and AdvanceTable.STO != 0) {
                        addQueryParameter("f_sto", "on")
                    }
                    if (advanceSearch and AdvanceTable.SFL != 0) {
                        addQueryParameter("f_sfl", "on")
                    }
                    if (advanceSearch and AdvanceTable.SFU != 0) {
                        addQueryParameter("f_sfu", "on")
                    }
                    if (advanceSearch and AdvanceTable.SFT != 0) {
                        addQueryParameter("f_sft", "on")
                    }
                    // Set min star
                    if (minRating > 0) {
                        addQueryParameter("f_sr", "on")
                        addQueryParameter("f_srdd", "$minRating")
                    }
                    // Pages
                    if (pageFrom > 0 || pageTo > 0) {
                        addQueryParameter("f_sp", "on")
                        addQueryParameterIfNotBlank(
                            "f_spf",
                            pageFrom.takeIf { it > 0 }?.toString(),
                        )
                        addQueryParameterIfNotBlank(
                            "f_spt",
                            pageTo.takeIf { it > 0 }?.toString(),
                        )
                    }
                }
            }.buildString()

            MODE_UPLOADER, MODE_TAG -> {
                val path = if (mode == MODE_UPLOADER) "uploader" else "tag"
                ehUrl(listOf(path, requireNotNull(mKeyword))) {
                    addQueryParameterIfNotBlank("prev", mPrev)
                    addQueryParameterIfNotBlank("next", mNext)
                    addQueryParameterIfNotBlank("seek", mJumpTo)
                    addQueryParameterIfNotBlank("range", mRange.takeIf { it > 0 }?.toString())
                }.buildString()
            }

            MODE_WHATS_HOT -> EhUrl.popularUrl
            MODE_IMAGE_SEARCH -> ehUrl {
                addQueryParameter("f_shash", requireNotNull(hash))
            }.buildString()
            MODE_TOPLIST -> {
                ehUrl("toplist.php", EhUrl.DOMAIN_E) {
                    addQueryParameter("tl", requireNotNull(mKeyword))
                    addQueryParameterIfNotBlank("p", mJumpTo)
                }.buildString()
            }

            else -> throw IllegalStateException("Unexpected value: $mode")
        }
    }

    @IntDef(
        MODE_NORMAL,
        MODE_UPLOADER,
        MODE_TAG,
        MODE_WHATS_HOT,
        MODE_IMAGE_SEARCH,
        MODE_SUBSCRIPTION,
        MODE_TOPLIST,
    )
    @Retention(AnnotationRetention.SOURCE)
    private annotation class Mode
    companion object {
        const val MODE_NORMAL = 0x0
        const val MODE_UPLOADER = 0x1
        const val MODE_TAG = 0x2
        const val MODE_WHATS_HOT = 0x3
        const val MODE_IMAGE_SEARCH = 0x4
        const val MODE_SUBSCRIPTION = 0x5
        const val MODE_TOPLIST = 0x6
    }
}
