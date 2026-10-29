package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView
import grandstrategy.formations.Formation
import grandstrategy.formations.FormationManager
import java.util.UUID

/** v0.6: one-level-at-a-time formation navigator with optional full hierarchy overview. */
class FormationOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    private val civ: Civilization = viewingPlayer.getCiv()
    private val formations = FormationManager(civ.grandStrategyFormations)
    private var selectedId: String? = null
    private var deleteConfirmationId: String? = null
    private var movingId: String? = null
    private var showUnitPicker = false
    private var showWholeTree = false

    init {
        top()
        defaults().pad(3f)
        render()
    }

    private fun editable() = civ.isHuman() && GUI.isAllowedChangeState()

    private fun liveUnits(): List<MapUnit> = civ.units.getCivUnits()
        .filter { it.id != Constants.NO_ID }
        .sortedWith(compareBy<MapUnit> { it.name }.thenBy { it.id })
        .toList()

    private fun commit() {
        civ.grandStrategyFormations = formations.snapshot()
        render()
    }

    private fun textButton(text: String, onPressed: () -> Unit) =
        TextButton(text, BaseScreen.skin).apply {
            label.setAlignment(Align.left)
            labelCell.padLeft(9f)
            onClick { onPressed() }
        }

    private fun editButton(text: String, onPressed: () -> Unit) =
        textButton(text) { if (editable()) onPressed() }.apply {
            isDisabled = !editable()
        }

    private fun title(target: Table, text: String) {
        target.add(text.toLabel(hideIcons = true)).left().growX().row()
    }

    private fun actionRow(target: Table, caption: String, action: String, onPressed: () -> Unit) {
        val row = Table(BaseScreen.skin)
        row.add(caption.toLabel(hideIcons = true)).left().growX()
        row.add(textButton(action, onPressed)).right().padLeft(4f)
        target.add(row).growX().row()
    }

    /** Jump to the same unit selected in Unciv's native Units overview. */
    private fun goToUnit(id: Int) {
        // Resolve again at tap-time: the stored row can outlive a unit that was disbanded.
        val unit = civ.units.getCivUnits().firstOrNull { it.id == id } ?: return
        val world = GUI.getWorldScreen()
        val selectedView = world.selectedGameView.getForeignMapUnitView(unit).tryGetMapUnitView()
        GUI.resetToWorldScreen()
        GUI.getMap().setCenterPosition(unit.currentTile.position, forceSelectUnit = selectedView)
    }

    private fun addUnitRow(target: Table, unit: MapUnit, prefix: String = "", unassign: Boolean = false) {
        val row = Table(BaseScreen.skin)
        row.add(textButton(prefix + unit.displayName() + "  (ID " + unit.id + ")  >") {
            goToUnit(unit.id)
        }).growX()
        if (unassign && editable()) {
            row.add(editButton("해제") {
                formations.unassignUnit(unit.id.toString())
                commit()
            }).padLeft(4f)
        }
        target.add(row).growX().row()
    }

    private fun subtreeCount(
        id: String,
        unitGroups: Map<String, List<MapUnit>>
    ): Int = (unitGroups[id]?.size ?: 0) +
        formations.children(id).sumOf { subtreeCount(it.id, unitGroups) }

    /** Navigate to one formation. The left panel only shows its immediate children. */
    private fun navigateTo(id: String?) {
        selectedId = if (id == null || formations.get(id) != null) id else null
        movingId = null
        deleteConfirmationId = null
        showUnitPicker = false
        showWholeTree = false
        render()
    }

    /** Only the current level is visible here, never grandchildren. */
    private fun drawLevel(target: Table, unitGroups: Map<String, List<MapUnit>>) {
        val selected = selectedId?.let(formations::get)
        title(target, "단계별 편제 탐색")

        // Clickable path makes deep hierarchies navigable without repeatedly backing out.
        val breadcrumbs = Table(BaseScreen.skin)
        breadcrumbs.add(textButton("전체") { navigateTo(null) }).padRight(2f)
        for (ancestor in formations.ancestors(selectedId).asReversed()) {
            breadcrumbs.add(">".toLabel(hideIcons = true)).padLeft(2f).padRight(2f)
            breadcrumbs.add(textButton(ancestor.name) { navigateTo(ancestor.id) }).padRight(2f)
        }
        target.add(breadcrumbs).left().row()

        val toolbar = Table(BaseScreen.skin)
        if (selected != null) {
            toolbar.add(textButton("< 상위 편제") { navigateTo(selected.parentId) }).growX()
        } else {
            toolbar.add(textButton("전체 편제") { navigateTo(null) }).growX()
        }
        toolbar.add(textButton("전체 조직망") {
            showWholeTree = true
            render()
        }).growX().padLeft(3f)
        target.add(toolbar).growX().row()

        val children = formations.children(selectedId)
        title(target, (selected?.name ?: "전체 편제") + " / 직속 하위 " + children.size + "개")
        if (children.isEmpty()) title(target, "하위 편제가 없습니다.")
        for (child in children) {
            val directCount = unitGroups[child.id]?.size ?: 0
            val totalCount = subtreeCount(child.id, unitGroups)
            val label = child.name + "  >  (직속 " + directCount + " / 전체 " + totalCount + ")"
            target.add(textButton(label) { navigateTo(child.id) })
                .growX().height(43f).row()
        }
    }

    /** All-level overview, with ASCII branch links that work even if icon fonts are missing. */
    private fun drawNetworkNode(
        target: Table,
        node: Formation,
        prefix: String,
        isLast: Boolean,
        unitGroups: Map<String, List<MapUnit>>
    ) {
        val connector = when {
            node.parentId == null -> ""
            isLast -> "\\-- "
            else -> "|-- "
        }
        val line = Table(BaseScreen.skin)
        line.add((prefix + connector).toLabel(hideIcons = true)).left()
        val count = subtreeCount(node.id, unitGroups)
        line.add(textButton(node.name + " (" + count + ")") { navigateTo(node.id) })
            .growX()
        target.add(line).growX().row()

        val nextPrefix = prefix + when {
            node.parentId == null -> ""
            isLast -> "    "
            else -> "|   "
        }
        val children = formations.children(node.id)
        for ((index, child) in children.withIndex()) {
            drawNetworkNode(target, child, nextPrefix, index == children.lastIndex, unitGroups)
        }
    }

    private fun drawNetwork(target: Table, unitGroups: Map<String, List<MapUnit>>) {
        title(target, "전체 편제 조직망")
        target.add(textButton("< 단계별 탐색") {
            showWholeTree = false
            render()
        }).growX().row()
        title(target, "연결된 편제를 누르면 해당 단계로 이동합니다.")
        val roots = formations.children(null)
        if (roots.isEmpty()) title(target, "생성된 편제가 없습니다.")
        for (root in roots) drawNetworkNode(target, root, "", true, unitGroups)
    }

    private fun drawDetail(target: Table, units: List<MapUnit>, unitGroups: Map<String, List<MapUnit>>) {
        val selected = selectedId?.let(formations::get)
        title(target, "편제 관리 · " + (selected?.name ?: "최상위"))
        if (selected != null) {
            actionRow(target, "상위 편제로 돌아가기", "상위로") {
                navigateTo(selected.parentId)
            }
        }

        if (selected != null && editable()) {
            val rename = TextField(selected.name, BaseScreen.skin)
            val row = Table(BaseScreen.skin)
            row.add(rename).growX()
            row.add(editButton("이름 변경") {
                if (rename.text.isNotBlank()) {
                    formations.rename(selected.id, rename.text)
                    commit()
                }
            }).padLeft(3f)
            target.add(row).growX().row()

            val parentName = selected.parentId?.let { formations.get(it)?.name } ?: "최상위"
            actionRow(target, "상위: " + parentName, "변경") {
                movingId = selected.id
                deleteConfirmationId = null
                render()
            }
            actionRow(target, "편제와 하위 조직 삭제", "삭제") {
                deleteConfirmationId = selected.id
                movingId = null
                render()
            }
        }

        if (deleteConfirmationId != null && editable()) {
            val targetId = deleteConfirmationId!!
            val toDelete = formations.get(targetId)
            if (toDelete != null) {
                title(target, "'" + toDelete.name + "' 및 하위 편제를 삭제할까요?")
                actionRow(target, "유닛은 유지하며 배속만 해제됩니다.", "삭제 확인") {
                    val deleted = formations.deleteSubtree(targetId)
                    selectedId?.let { if (it in deleted) selectedId = null }
                    deleteConfirmationId = null
                    showUnitPicker = false
                    commit()
                }
                actionRow(target, "취소하고 돌아가기", "취소") {
                    deleteConfirmationId = null
                    render()
                }
            }
        }

        if (movingId != null && editable()) {
            val moveId = movingId!!
            title(target, "이동할 상위 편제를 선택하세요")
            val forbidden = formations.all().filter {
                it.id == moveId || formations.ancestors(it.id).any { a -> a.id == moveId }
            }.map { it.id }.toSet()
            actionRow(target, "최상위", "여기로") {
                formations.move(moveId, null)
                movingId = null
                commit()
            }
            for (candidate in formations.all().filter { it.id !in forbidden }) {
                actionRow(target, candidate.name, "여기로") {
                    formations.move(moveId, candidate.id)
                    movingId = null
                    commit()
                }
            }
            actionRow(target, "상위 변경 취소", "취소") {
                movingId = null
                render()
            }
        }

        if (editable()) {
            title(target, if (selected == null) "최상위 편제 생성" else "하위 편제 생성")
            val newName = TextField("", BaseScreen.skin)
            newName.messageText = "새 편제 이름"
            val row = Table(BaseScreen.skin)
            row.add(newName).growX()
            row.add(editButton("생성") {
                if (newName.text.isNotBlank()) {
                    formations.create(UUID.randomUUID().toString(), newName.text, selectedId)
                    commit()
                }
            }).padLeft(3f)
            target.add(row).growX().row()
        }

        if (selected == null && editable()) {
            title(target, "기본 편제 템플릿")
            actionRow(target, "해군: 해군성 / 함대 / 전대", "생성") {
                formations.addNavyTemplate("navy-" + UUID.randomUUID())
                commit()
            }
            actionRow(target, "육군: 총사령부 / 야전군 / 군단 / 사단", "생성") {
                formations.addArmyTemplate("army-" + UUID.randomUUID())
                commit()
            }
        }

        if (selected == null) return
        val direct = unitGroups[selected.id].orEmpty()
        title(target, "직속 유닛 " + direct.size + "개 · 터치하면 지도 이동")
        if (direct.isEmpty()) title(target, "직속 유닛 없음")
        for (unit in direct) addUnitRow(target, unit, unassign = true)

        val descendants = formations.all().filter { node ->
            node.id != selected.id && formations.ancestors(node.id).any { it.id == selected.id }
        }.map { it.id }.toSet()
        val nested = units.filter { formations.assignedFormation(it.id.toString()) in descendants }
        // Summary only. To see a unit belonging to a deeper level, drill into its folder.
        title(target, "하위 편제에 배속된 유닛 " + nested.size + "개")
        if (nested.isNotEmpty()) title(target, "왼쪽에서 하위 편제를 선택해 유닛을 확인하세요.")

        if (editable()) {
            val candidates = units.filter { formations.assignedFormation(it.id.toString()) != selected.id }
            actionRow(target, "배속 / 전속 가능한 유닛 " + candidates.size + "개",
                if (showUnitPicker) "목록 닫기" else "목록 열기") {
                showUnitPicker = !showUnitPicker
                render()
            }
            if (showUnitPicker) {
                for (unit in candidates) {
                    val oldName = formations.assignedFormation(unit.id.toString())?.let {
                        formations.get(it)?.name
                    } ?: "미배속"
                    actionRow(target, unit.displayName() + " / " + oldName, "배속") {
                        formations.assignUnit(unit.id.toString(), selected.id)
                        commit()
                    }
                }
            }
        }
    }

    private fun render() {
        clearChildren()
        val units = liveUnits()
        formations.reconcileLiveUnits(units.map { it.id.toString() }.toSet())
        civ.grandStrategyFormations = formations.snapshot()
        val unitGroups = units.mapNotNull { unit ->
            formations.assignedFormation(unit.id.toString())?.let { it to unit }
        }.groupBy({ it.first }, { it.second })

        val tree = Table(BaseScreen.skin).apply { top(); defaults().pad(3f) }
        val detail = Table(BaseScreen.skin).apply { top(); defaults().pad(3f) }
        if (showWholeTree) drawNetwork(tree, unitGroups) else drawLevel(tree, unitGroups)
        drawDetail(detail, units, unitGroups)

        val stageWidth = overviewScreen.stage.width
        if (stageWidth >= 780f) {
            // Tablet: fixed independent columns, no more repeated management buttons in the tree.
            add(tree).width(stageWidth * 0.43f).top().padRight(5f)
            add(detail).width(stageWidth * 0.49f).top().row()
        } else {
            // Phones: the same controls stack vertically to stay reachable.
            add(tree).growX().row()
            add(detail).growX().row()
        }
        pack()
    }
}
