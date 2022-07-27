/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "RsCodeInsightSettings", storages = [Storage("rust.xml")])
class RsCodeInsightSettings : PersistentStateComponent<RsCodeInsightSettings> {

    var showImportPopup: Boolean = false
    var importOutOfScopeItems: Boolean = true
    var suggestOutOfScopeItems: Boolean = true
    var addUnambiguousImportsOnTheFly: Boolean = false
    var importOnPaste: Boolean = false
    var excludedPaths: Array<ExcludedPath> = DEFAULT_EXCLUDED_PATHS

    override fun getState(): RsCodeInsightSettings = this

    override fun loadState(state: RsCodeInsightSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): RsCodeInsightSettings = service()

        private val DEFAULT_EXCLUDED_PATHS: Array<ExcludedPath> = arrayOf(
            ExcludedPath("std::borrow::Borrow", ExclusionType.Methods),
            ExcludedPath("core::panicking"),
            ExcludedPath("std::intrinsics::unreachable"),
        )
    }
}

// must have default constructor and mutable fields for deserialization
class ExcludedPath(var path: String = "", var type: ExclusionType = ExclusionType.Always)
enum class ExclusionType { Always, Methods }
