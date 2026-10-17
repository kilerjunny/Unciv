package grandstrategy.formations

/** Organizational-only hierarchy: these operations never change map positions, combat or units. */
data class Formation(
    var id: String = "",
    var name: String = "",
    var parentId: String? = null,
    var kind: String = "custom"
)

data class UnitAssignment(var unitId: String = "", var formationId: String = "")

data class FormationSnapshot(
    var version: Int = 1,
    var formations: List<Formation> = emptyList(),
    var assignments: List<UnitAssignment> = emptyList()
)

/**
 * Each unit belongs to at most one formation. Hierarchies are acyclic.
 * Caller should use persistent Unciv unit identifiers (not display labels).
 * Snapshot must be serialized as part of a game save by the Unciv integration layer.
 */
class FormationManager(snapshot: FormationSnapshot = FormationSnapshot()) {
    private val formations = linkedMapOf<String, Formation>()
    private val assignments = linkedMapOf<String, String>()

    init { restore(snapshot) }

    fun get(id: String): Formation? = formations[id]
    fun all(): List<Formation> = formations.values.toList()
    fun children(parentId: String?): List<Formation> = formations.values.filter { it.parentId == parentId }
    fun assignedUnitIds(formationId: String): List<String> = assignments.filterValues { it == formationId }.keys.toList()
    fun assignedFormation(unitId: String): String? = assignments[unitId]
    fun unassignedUnitIds(allLiveUnitIds: Collection<String>): List<String> = allLiveUnitIds.filterNot { assignments.containsKey(it) }

    fun create(id: String, name: String, parentId: String? = null, kind: String = "custom") {
        require(id.isNotBlank() && id !in formations) { "Duplicate/invalid formation id" }
        require(name.isNotBlank()) { "Formation name cannot be empty" }
        require(parentId == null || parentId in formations) { "Parent does not exist" }
        formations[id] = Formation(id, name.trim(), parentId, kind)
    }

    fun rename(id: String, newName: String) {
        require(newName.isNotBlank()) { "Formation name cannot be empty" }
        val current = formations[id] ?: error("Formation does not exist")
        formations[id] = current.copy(name = newName.trim())
    }

    fun move(id: String, newParentId: String?) {
        val current = formations[id] ?: error("Formation does not exist")
        require(newParentId == null || newParentId in formations) { "Parent does not exist" }
        require(newParentId != id && id !in ancestors(newParentId).map { it.id }) { "Circular hierarchy" }
        formations[id] = current.copy(parentId = newParentId)
    }

    fun ancestors(id: String?): List<Formation> {
        val result = mutableListOf<Formation>()
        val seen = mutableSetOf<String>()
        var parent = id
        while (parent != null) {
            check(seen.add(parent)) { "Circular hierarchy" }
            val node = formations[parent] ?: error("Missing formation")
            result.add(node)
            parent = node.parentId
        }
        return result
    }

    /** Remove the subtree; its attached units become unassigned. Returns deleted formation ids. */
    fun deleteSubtree(id: String): Set<String> {
        require(id in formations) { "Formation does not exist" }
        val removed = linkedSetOf(id)
        var changed: Boolean
        do {
            val before = removed.size
            formations.values.filter { it.parentId in removed }.forEach { removed.add(it.id) }
            changed = removed.size != before
        } while (changed)
        removed.forEach { formations.remove(it) }
        assignments.entries.removeAll { it.value in removed }
        return removed
    }

    fun assignUnit(unitId: String, formationId: String) {
        require(unitId.isNotBlank()) { "Invalid unit id" }
        require(formationId in formations) { "Formation does not exist" }
        assignments[unitId] = formationId
    }

    fun unassignUnit(unitId: String) { assignments.remove(unitId) }
    fun onUnitDestroyed(unitId: String) { assignments.remove(unitId) }

    /** Remove stale assignments after loading or advancing turns. */
    fun reconcileLiveUnits(liveUnitIds: Set<String>) {
        assignments.keys.retainAll(liveUnitIds)
    }

    fun snapshot(): FormationSnapshot = FormationSnapshot(
        formations = formations.values.toList(),
        assignments = assignments.map { UnitAssignment(it.key, it.value) }
    )

    fun restore(snapshot: FormationSnapshot) {
        require(snapshot.version == 1) { "Unsupported formation snapshot version" }
        formations.clear(); assignments.clear()
        // Parent may appear after child in serialized storage: insert in dependency order.
        val remaining = snapshot.formations.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { it.parentId == null || it.parentId in formations }
                ?: error("Invalid hierarchy (missing parent or cycle)")
            create(next.id, next.name, next.parentId, next.kind)
            remaining.remove(next)
        }
        snapshot.assignments.forEach { assignUnit(it.unitId, it.formationId) }
    }

    /** Templates create *empty* formations; never manufacture game units. */
    fun addNavyTemplate(prefix: String = "navy") {
        create("$prefix-hq", "해군성", kind = "navy_hq")
        create("$prefix-east", "동방함대", "$prefix-hq", "fleet")
        create("$prefix-west", "서방함대", "$prefix-hq", "fleet")
        create("$prefix-mobile", "기동함대", "$prefix-hq", "fleet")
        for (s in listOf("전위전대", "중앙전대", "후위전대"))
            create("$prefix-east-${s}", s, "$prefix-east", "squadron")
    }

    fun addArmyTemplate(prefix: String = "army") {
        create("$prefix-hq", "육군 총사령부", kind = "army_hq")
        create("$prefix-field-1", "제1야전군", "$prefix-hq", "field_army")
        create("$prefix-corps-1", "제1군단", "$prefix-field-1", "corps")
        create("$prefix-division-1", "제1사단", "$prefix-corps-1", "division")
    }
}
