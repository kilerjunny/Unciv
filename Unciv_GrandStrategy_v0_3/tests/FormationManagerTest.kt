package grandstrategy.formations

fun main() {
    val m = FormationManager()
    m.addNavyTemplate()
    m.addArmyTemplate()
    check(m.children(null).map { it.name } == listOf("해군성", "육군 총사령부"))
    m.create("new", "임시전대", "navy-east")
    m.assignUnit("unit-42", "new")
    check(m.assignedFormation("unit-42") == "new")
    m.move("new", "navy-mobile")
    check(m.get("new")?.parentId == "navy-mobile")
    try { m.move("navy-hq", "navy-east"); error("Cycle was accepted") }
    catch (_: IllegalArgumentException) { }
    val saved = m.snapshot()
    val restored = FormationManager(saved)
    check(restored.assignedFormation("unit-42") == "new")
    check("new" in restored.deleteSubtree("navy-mobile"))
    check(restored.assignedFormation("unit-42") == null)
    check("unit-42" in restored.unassignedUnitIds(listOf("unit-42")))
    restored.assignUnit("unit-77", "navy-east")
    restored.reconcileLiveUnits(setOf("unit-42"))
    check(restored.assignedFormation("unit-77") == null)
    println("PASS: templates, nesting, transfers, cycle guard, snapshot, deletion, stale-unit cleanup")
}
