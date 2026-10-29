package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.TextField
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

/** Organization-only tab: no changes to unit movement, combat or unit ownership. */
class FormationOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    private val civ: Civilization = viewingPlayer.getCiv()
    private val formations = FormationManager(civ.grandStrategyFormations)
    private var selectedId: String? = null
    private var deleteConfirmationId: String? = null
    private var movingId: String? = null
    private val expandedIds = mutableSetOf<String>()
    private var showUnitPicker = false

    init {
        top()
        defaults().pad(4f)
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

    private fun heading(title: String) {
        add(title.toLabel(hideIcons = true)).left().colspan(2).growX().row()
    }

    private fun button(label: String, action: () -> Unit): TextButton =
        TextButton(label, BaseScreen.skin).apply {
            isDisabled = !editable()
            onClick { if (editable()) action() }
        }

    private fun navigationButton(label: String, action: () -> Unit): TextButton =
        TextButton(label, BaseScreen.skin).apply {
            onClick { action() }
        }

    private fun line(label: String, actionName: String? = null, action: (() -> Unit)? = null) {
        add(label.toLabel(hideIcons = true)).left().growX()
        if (actionName == null || action == null) add()
        else add(button(actionName, action))
        row()
    }

    private fun viewLine(label: String, actionName: String, action: () -> Unit) {
        add(label.toLabel(hideIcons = true)).left().growX()
        add(navigationButton(actionName, action))
        row()
    }

    private fun subtreeUnitCount(
        formationId: String,
        unitsByFormation: Map<String, List<MapUnit>>
    ): Int = (unitsByFormation[formationId]?.size ?: 0) +
        formations.children(formationId).sumOf { subtreeUnitCount(it.id, unitsByFormation) }

    /** An indented, collapsible, read-only tree; taps select a formation for editing below. */
    private fun renderTreeNode(
        formation: Formation,
        depth: Int,
        unitsByFormation: Map<String, List<MapUnit>>
    ) {
        val children = formations.children(formation.id)
        val directUnits = unitsByFormation[formation.id].orEmpty()
        val canExpand = children.isNotEmpty() || directUnits.isNotEmpty()
        val expanded = formation.id in expandedIds
        val marker = if (!canExpand) "[ ]" else if (expanded) "[-]" else "[+]"
        val count = subtreeUnitCount(formation.id, unitsByFormation)

        val controls = Table(BaseScreen.skin)
        controls.add(navigationButton(marker + " " + formation.name + " (" + count + ")") {
            selectedId = formation.id
            deleteConfirmationId = null
            movingId = null
            showUnitPicker = false
            if (canExpand) {
                if (!expandedIds.add(formation.id)) expandedIds.remove(formation.id)
            }
            render()
        }).left().growX().padLeft(8f + depth * 16f)

        controls.add(navigationButton(if (selectedId == formation.id) "선택됨" else "관리") {
            selectedId = formation.id
            deleteConfirmationId = null
            movingId = null
            showUnitPicker = false
            render()
        }).right().padLeft(3f)
        add(controls).colspan(2).growX().row()

        if (!expanded) return
        for (child in children) renderTreeNode(child, depth + 1, unitsByFormation)
        for (unit in directUnits) {
            val unitRow = Table(BaseScreen.skin)
            unitRow.add(("- " + unit.displayName() + " (ID " + unit.id + ")")
                .toLabel(hideIcons = true)).left().padLeft(24f + depth * 16f)
            add(unitRow).colspan(2).left().row()
        }
    }

    private fun render() {
        clearChildren()
        val units = liveUnits()
        formations.reconcileLiveUnits(units.map { it.id.toString() }.toSet())
        civ.grandStrategyFormations = formations.snapshot()

        val assigned = units.mapNotNull { unit ->
            formations.assignedFormation(unit.id.toString())?.let { it to unit }
        }
        val unitsByFormation = assigned.groupBy({ it.first }, { it.second })

        heading("편제 조직도")
        viewLine("편제 " + formations.all().size + "개 / 배속 유닛 " + assigned.size + "개", "모두 펼치기") {
            expandedIds.addAll(formations.all().map { it.id })
            render()
        }
        viewLine("숫자는 하위 편제 소속 유닛까지 포함", "모두 접기") {
            expandedIds.clear()
            render()
        }
        val roots = formations.children(null)
        if (roots.isEmpty()) line("편제가 없습니다. 아래에서 만들거나 템플릿을 생성하세요.")
        for (root in roots) renderTreeNode(root, 0, unitsByFormation)

        val selected = selectedId?.let(formations::get)
        heading("선택한 편제: " + (selected?.name ?: "최상위"))
        viewLine("최상위 편제 관리", "최상위 선택") {
            selectedId = null
            movingId = null
            deleteConfirmationId = null
            showUnitPicker = false
            render()
        }

        if (selected != null && editable()) {
            val renameField = TextField(selected.name, BaseScreen.skin)
            add(renameField).growX()
            add(button("이름 변경") {
                if (renameField.text.isNotBlank()) {
                    formations.rename(selected.id, renameField.text)
                    commit()
                }
            }).row()

            val parentName = selected.parentId?.let { formations.get(it)?.name } ?: "최상위"
            line("상위 편제: " + parentName, "상위 변경") {
                movingId = selected.id
                deleteConfirmationId = null
                render()
            }
            line("이 편제와 하위 편제 삭제 (유닛은 보존)", "삭제") {
                deleteConfirmationId = selected.id
                movingId = null
                render()
            }
        }

        if (deleteConfirmationId != null && editable()) {
            val targetId = deleteConfirmationId!!
            val target = formations.get(targetId)
            if (target != null) {
                line("'" + target.name + "' 및 하위 편제를 삭제할까요?", "삭제 확인") {
                    val removed = formations.deleteSubtree(targetId)
                    expandedIds.removeAll(removed)
                    selectedId?.let { if (it in removed) selectedId = null }
                    deleteConfirmationId = null
                    showUnitPicker = false
                    commit()
                }
                line("유닛은 삭제되지 않고 미배속 상태가 됩니다.", "취소") {
                    deleteConfirmationId = null
                    render()
                }
            }
        }

        if (movingId != null && editable()) {
            val moveId = movingId!!
            heading("이동할 상위 편제 선택")
            val prohibited = formations.all().filter {
                it.id == moveId || formations.ancestors(it.id).any { ancestor -> ancestor.id == moveId }
            }.map { it.id }.toSet()
            line("최상위", "여기로 이동") {
                formations.move(moveId, null)
                movingId = null
                commit()
            }
            for (candidate in formations.all().filter { it.id !in prohibited }) {
                line(candidate.name, "여기로 이동") {
                    formations.move(moveId, candidate.id)
                    movingId = null
                    expandedIds.add(candidate.id)
                    commit()
                }
            }
            line("상위 변경 취소", "취소") {
                movingId = null
                render()
            }
        }

        if (editable()) {
            heading(if (selected == null) "최상위 편제 추가" else "하위 편제 추가")
            val newName = TextField("", BaseScreen.skin)
            newName.messageText = "새 편제 이름"
            add(newName).growX()
            add(button("생성") {
                if (newName.text.isNotBlank()) {
                    formations.create(UUID.randomUUID().toString(), newName.text, selectedId)
                    selectedId?.let { expandedIds.add(it) }
                    commit()
                }
            }).row()
        }

        if (selected == null && editable()) {
            heading("기본 편제 템플릿")
            line("영국 해군: 해군성 / 함대 / 전대", "생성") {
                formations.addNavyTemplate("navy-" + UUID.randomUUID())
                commit()
            }
            line("육군: 총사령부 / 야전군 / 군단 / 사단", "생성") {
                formations.addArmyTemplate("army-" + UUID.randomUUID())
                commit()
            }
        }

        if (selected != null) {
            val direct = unitsByFormation[selected.id].orEmpty()
            heading("직속 유닛: " + direct.size + "개")
            if (direct.isEmpty()) line("직속 유닛 없음")
            for (unit in direct) {
                line(unit.displayName() + " (ID " + unit.id + ")", if (editable()) "배속 해제" else null) {
                    formations.unassignUnit(unit.id.toString())
                    commit()
                }
            }

            val descendantIds = formations.all().filter { formation ->
                formation.id != selected.id &&
                    formations.ancestors(formation.id).any { it.id == selected.id }
            }.map { it.id }.toSet()
            val nested = units.filter { formations.assignedFormation(it.id.toString()) in descendantIds }
            heading("하위 편제 소속 유닛: " + nested.size + "개")
            for (unit in nested) {
                val formationId = formations.assignedFormation(unit.id.toString())
                val name = formationId?.let { formations.get(it)?.name } ?: "?"
                line(name + " / " + unit.displayName())
            }

            if (editable()) {
                val candidates = units.filter {
                    formations.assignedFormation(it.id.toString()) != selected.id
                }
                viewLine("배속하거나 전속할 수 있는 유닛: " + candidates.size + "개",
                    if (showUnitPicker) "목록 닫기" else "배속 / 전속") {
                    showUnitPicker = !showUnitPicker
                    render()
                }
                if (showUnitPicker) {
                    for (unit in candidates) {
                        val oldId = formations.assignedFormation(unit.id.toString())
                        val oldLabel = oldId?.let { formations.get(it)?.name } ?: "미배속"
                        line(unit.displayName() + " / " + oldLabel, "이 편제에 배속") {
                            formations.assignUnit(unit.id.toString(), selected.id)
                            commit()
                        }
                    }
                }
            }
        }
        pack()
    }
}
