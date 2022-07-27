/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.settings

import com.intellij.execution.util.ListTableWithButtons
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBoxTableRenderer
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.containers.map2Array
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import javax.swing.DefaultCellEditor
import javax.swing.JTextField
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

data class Item(var path: String, var type: ExclusionType, var scope: ExclusionScope)
enum class ExclusionScope { Project, IDE }

class RsPathsExcludeTable(project: Project) : ListTableWithButtons<Item>() {

    private val globalSettings: RsCodeInsightSettings = RsCodeInsightSettings.getInstance()
    private val projectSettings: RsProjectCodeInsightSettings = RsProjectCodeInsightSettings.getInstance(project)

    private fun getSettingsItems(): List<Item> =
        globalSettings.excludedPaths.map { Item(it.path, it.type, ExclusionScope.IDE) } +
            projectSettings.state.excludedPaths.map { Item(it.path, it.type, ExclusionScope.Project) }

    private fun getCurrentItems(): List<Item> =
        tableView.listTableModel.items

    private fun getCurrentItems(scope: ExclusionScope): Array<ExcludedPath> =
        getCurrentItems().filter { it.scope == scope }.map2Array { ExcludedPath(it.path, it.type) }

    fun isModified(): Boolean = getSettingsItems() != getCurrentItems()

    fun apply() {
        globalSettings.excludedPaths = getCurrentItems(ExclusionScope.IDE)
        projectSettings.state.excludedPaths = getCurrentItems(ExclusionScope.Project)
    }

    fun reset() {
        setValues(getSettingsItems())
    }

    override fun createListModel(): ListTableModel<*> = ListTableModel<Item>(NAME_COLUMN, TYPE_COLUMN, SCOPE_COLUMN)

    override fun createElement(): Item = Item("", ExclusionType.Always, ExclusionScope.IDE)

    override fun isEmpty(item: Item): Boolean = item.path.isEmpty()

    override fun canDeleteElement(item: Item): Boolean = true

    override fun cloneElement(item: Item): Item = item.copy()
}

@Suppress("DialogTitleCapitalization")
private val NAME_COLUMN: ColumnInfo<Item, String> = object : ColumnInfo<Item, String>("Item or module") {
    override fun valueOf(item: Item): String = item.path

    override fun isCellEditable(item: Item): Boolean = true

    override fun setValue(item: Item, value: String) {
        item.path = value
    }

    override fun getEditor(item: Item): TableCellEditor {
        val cellEditor = ExtendableTextField()
        cellEditor.putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
        return DefaultCellEditor(cellEditor)
    }

    override fun getRenderer(item: Item?): TableCellRenderer {
        val cellEditor = JTextField()
        cellEditor.putClientProperty(DarculaUIUtil.COMPACT_PROPERTY, true)
        return DefaultTableCellRenderer().also {
            it.preferredSize = cellEditor.preferredSize
        }
    }
}

private val TYPE_COLUMN: ColumnInfo<Item, ExclusionType> = object : ComboboxColumnInfo<ExclusionType>(ExclusionType.values(), "Type") {

    override fun ExclusionType.displayText(): String = when (this) {
        ExclusionType.Always -> "Always"
        ExclusionType.Methods -> "Only methods"
    }

    override fun valueOf(item: Item): ExclusionType = item.type

    override fun setValue(item: Item, value: ExclusionType) {
        item.type = value
    }
}

private val SCOPE_COLUMN: ColumnInfo<Item, ExclusionScope> = object : ComboboxColumnInfo<ExclusionScope>(ExclusionScope.values(), "Scope") {

    override fun valueOf(item: Item): ExclusionScope = item.scope

    override fun setValue(item: Item, value: ExclusionScope) {
        item.scope = value
    }
}

private abstract class ComboboxColumnInfo<T : Any>(
    private val values: Array<T>,
    name: String,
) : ColumnInfo<Item, T>(name) {

    open fun T.displayText(): String = toString()

    override fun isCellEditable(item: Item): Boolean = true

    override fun getRenderer(pair: Item?): TableCellRenderer = renderer

    override fun getEditor(pair: Item?): TableCellEditor = renderer

    private val renderer: ComboBoxTableRenderer<T> =
        object : ComboBoxTableRenderer<T>(values) {
            override fun getTextFor(value: T): String = value.displayText()
        }

    override fun getMaxStringValue(): String =
        values.map { it.displayText() }.maxByOrNull { it.length }!!

    override fun getAdditionalWidth(): Int =
        JBUIScale.scale(12) + AllIcons.General.ArrowDown.iconWidth
}
