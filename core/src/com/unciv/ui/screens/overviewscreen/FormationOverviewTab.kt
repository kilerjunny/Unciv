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

/** v0.5: compact split-pane formation manager. Only formation assignments are mutated. */
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
    private val expandedIds = mutableSetOf<String>()

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

    /** One tappable row per formation: tap toggles its children and opens its details. */
    private fun addTreeNode(
        target: Table,
        node: Formation,
        depth: Int,
        unitGroups: Map<String, List<MapUnit>>
    ) {
        val children = formations.children(node.id)
        val ownUnits = unitGroups[node.id].orEmpty()
        val hasChildren = children.isNotEmpty() || ownUnits.isNotEmpty()
        val isOpen = node.id in expandedIds
        val symbol = when {
            !hasChildren -> "[ ]"
            isOpen -> "[-]"
            else -> "[+]"
        }
        val selection = if (selectedId == node.id) " *" else ""
        val label = "$symbol ${node.name} (${subtreeCount(node.id, unitGroups)})$selection"
        target.add(textButton(label) {
            selectedId = node.id
            deleteConfirmationId = null
            movingId = null
            showUnitPicker = false
            if (hasChildren) {
                if (!expandedIds.add(node.id)) expandedIds.remove(node.id)
            }
            render()
        }).growX().height(37f).padLeft(depth * 15f).row()
        if (!isOpen) return
        for (child in children) addTreeNode(target, child, depth + 1, unitGroups)
        for (unit in ownUnits) {
            // Directly tap an assigned unit in the tree to center the map on it.
            target.add(textButton("- " + unit.displayName() + " >") {
                goToUnit(unit.id)
            }).growX().height(32f).padLeft((depth + 1) * 15f).row()
        }
    }

    private fun drawTree(target: Table, unitGroups: Map<String, List<MapUnit>>) {
        title(target, "편제 조직도")
        val treeToolbar = Table(BaseScreen.skin)
        treeToolbar.add(textButton("모두 펼치기") {
            expandedIds.addAll(formations.all().map { it.id })
            render()
        }).growX()
        treeToolbar.add(textButton("모두 접기") {
            expandedIds.clear()
            render()
        }).growX().padLeft(3f)
        target.add(treeToolbar).growX().row()

        val count = unitGroups.values.sumOf { it.size }
        title(target, "편제 " + formations.all().size + "개 · 배속 " + count + "개")
        val roots = formations.children(null)
        if (roots.isEmpty()) title(target, "편제가 없습니다. 오른쪽에서 생성하세요.")
        for (root in roots) addTreeNode(target, root, 0, unitGroups)
    }

    private fun drawDetail(target: Table, units: List<MapUnit>, unitGroups: Map<String, List<MapUnit>>) {
        val selected = selectedId?.let(formations::get)
        title(target, "편제 관리 · " + (selected?.name ?: "최상위"))
        if (selected != null) {
            actionRow(target, "상위로 돌아가기", "최상위") {
                selectedId = null
                movingId = null
                deleteConfirmationId = null
                showUnitPicker = false
                render()
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
                    expandedIds.removeAll(deleted)
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
                    expandedIds.add(candidate.id)
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
                    selectedId?.let { expandedIds.add(it) }
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
        title(target, "하위 편제 소속 유닛 " + nested.size + "개")
        for (unit in nested) {
            val name = formations.assignedFormation(unit.id.toString())?.let {
                formations.get(it)?.name
            } ?: "?"
            addUnitRow(target, unit, name + " / ")
        }

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
        drawTree(tree, unitGroups)
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
