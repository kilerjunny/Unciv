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
import grandstrategy.formations.FormationManager
import java.util.UUID

/** Grand Strategy's organization-only tab: never moves, destroys or buffs game units.
 *  Reads live MapUnit.id references and persists the hierarchy on Civilization.
 */
class FormationOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    private val civ: Civilization = viewingPlayer.getCiv()
    private val formations = FormationManager(civ.grandStrategyFormations)
    private var selectedId: String? = null
    private var deleteConfirmationId: String? = null
    private var movingId: String? = null

    init {
        top()
        defaults().pad(7f)
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

    private fun line(label: String, actionName: String? = null, action: (() -> Unit)? = null) {
        add(label.toLabel(hideIcons = true)).left().growX()
        if (actionName == null || action == null) add()
        else add(button(actionName, action))
        row()
    }

    /** Display selected folder and its direct children, then list the units inside. */
    private fun render() {
        clearChildren()
        val units = liveUnits()
        // This reconciles losses, ownership changes and disbanded units on open/refresh.
        formations.reconcileLiveUnits(units.map { it.id.toString() }.toSet())
        civ.grandStrategyFormations = formations.snapshot()

        heading("⚓ 편제 관리")
        line("전체 편제", "이동") { selectedId = null; render() }
        for (ancestor in formations.ancestors(selectedId).asReversed()) {
            line(" › ${ancestor.name}", "열기") { selectedId = ancestor.id; render() }
        }
        val selected = selectedId?.let(formations::get)
        heading("현재 위치: ${selected?.name ?: "전체 편제"}")

        if (selected != null && editable()) {
            val renameField = TextField(selected.name, BaseScreen.skin)
            add(renameField).growX()
            add(button("이름 변경") {
                if (renameField.text.isNotBlank()) {
                    formations.rename(selected.id, renameField.text)
                    commit()
                }
            }).row()
        }

        heading("하위 편제")
        for (child in formations.children(selectedId)) {
            line("📁 ${child.name}", "열기") {
                selectedId = child.id
                deleteConfirmationId = null
                movingId = null
                render()
            }
            if (editable()) {
                line("  └ 편제 관리", "삭제") { deleteConfirmationId = child.id; render() }
                line("  └ 상위 편제 변경", "이동") { movingId = child.id; render() }
            }
        }
        if (editable()) {
            val newName = TextField("", BaseScreen.skin)
            newName.messageText = "새 편제 이름"
            add(newName).growX()
            add(button("하위 편제 추가") {
                if (newName.text.isNotBlank()) {
                    formations.create(UUID.randomUUID().toString(), newName.text, selectedId)
                    commit()
                }
            }).row()
        }

        if (deleteConfirmationId != null && editable()) {
            val target = formations.get(deleteConfirmationId!!)
            line("'${target?.name}' 및 하위 편제 삭제? 유닛은 유지됨", "삭제 확인") {
                formations.deleteSubtree(deleteConfirmationId!!)
                deleteConfirmationId = null
                commit()
            }
            line("삭제 취소", "취소") { deleteConfirmationId = null; render() }
        }
        if (movingId != null && editable()) {
            val moveId = movingId!!
            heading("'${formations.get(moveId)?.name}' 이동할 상위 편제 선택")
            val prohibited = formations.all().filter {
                it.id == moveId || formations.ancestors(it.id).any { a -> a.id == moveId }
            }.map { it.id }.toSet()
            line("최상위", "여기로 이동") {
                formations.move(moveId, null); movingId = null; commit()
            }
            for (candidate in formations.all().filter { it.id !in prohibited }) {
                line(candidate.name, "여기로 이동") {
                    formations.move(moveId, candidate.id); movingId = null; commit()
                }
            }
            line("이동 취소", "취소") { movingId = null; render() }
        }

        if (selected == null && editable()) {
            heading("기본 편제 템플릿")
            line("영국 해군: 해군성 → 함대 → 전대", "생성") {
                formations.addNavyTemplate("navy-${UUID.randomUUID()}")
                commit()
            }
            line("육군: 총사령부 → 야전군 → 군단 → 사단", "생성") {
                formations.addArmyTemplate("army-${UUID.randomUUID()}")
                commit()
            }
        }

        heading("현재 편제의 직속 유닛")
        val direct = units.filter { formations.assignedFormation(it.id.toString()) == selectedId && selectedId != null }
        if (direct.isEmpty()) line("직속 유닛 없음")
        for (unit in direct) {
            line("⚑ ${unit.displayName()} (ID ${unit.id})", if (editable()) "배속 해제" else null) {
                formations.unassignUnit(unit.id.toString()); commit()
            }
        }

        if (selected != null) {
            val descendantIds = formations.all()
                .filter { formation -> formations.ancestors(formation.id).any { it.id == selected.id } }
                .map { it.id }.toSet()
            val nested = units.filter { formations.assignedFormation(it.id.toString()) in descendantIds }
            heading("하위 편제 소속 유닛: ${nested.size}")
            for (unit in nested) {
                val parentName = formations.get(formations.assignedFormation(unit.id.toString()) ?: "")?.name ?: "?"
                line("$parentName › ${unit.displayName()}")
            }
        }

        if (selected != null && editable()) {
            heading("유닛 배속 / 다른 편제에서 전속")
            for (unit in units) {
                val unitId = unit.id.toString()
                val oldFormation = formations.assignedFormation(unitId)
                if (oldFormation == selected.id) continue
                val oldLabel = formations.get(oldFormation ?: "")?.name ?: "미배속"
                line("${unit.displayName()} · $oldLabel", "이 편제에 배속") {
                    formations.assignUnit(unitId, selected.id); commit()
                }
            }
        }
        pack()
    }
}
