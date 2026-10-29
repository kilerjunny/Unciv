from pathlib import Path
import subprocess
p=Path('core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt')
s=p.read_text()
assert 'v0.6: one-level-at-a-time' in s, 'Expected v0.6 source; refusing to patch'
assert subprocess.check_output(['git','branch','--show-current'],text=True).strip()=='grand-strategy-formations','Wrong branch'
def change(a,b):
 global s
 assert s.count(a)==1, f'Expected one match, got {s.count(a)}: {a[:80]}'
 s=s.replace(a,b)
change('import com.badlogic.gdx.scenes.scene2d.ui.Table','import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane\nimport com.badlogic.gdx.scenes.scene2d.ui.Table')
change('v0.6: one-level-at-a-time formation navigator with optional full hierarchy overview.','v0.7: compact stepwise formation navigator with independently scrolling tablet panels.')
change('target.add(textButton(label) { navigateTo(child.id) })\n                .growX().height(43f).row()', 'target.add(textButton(label) { navigateTo(child.id) })\n                .growX().height(33f).row()')
change('val connector = when {\n            node.parentId == null -> ""\n            isLast -> "\\\\-- "\n            else -> "|-- "\n        }','val connector = when {\n            node.parentId == null -> ""\n            isLast -> "└─ "\n            else -> "├─ "\n        }')
change('isLast -> "    "\n            else -> "|   "','isLast -> "   "\n            else -> "│  "')
change('line.add(textButton(node.name + " (" + count + ")") { navigateTo(node.id) })\n            .growX()', 'line.add(textButton(node.name + " (" + count + ")") { navigateTo(node.id) })\n            .growX().height(32f)')
change('''            // Tablet: fixed independent columns, no more repeated management buttons in the tree.
            add(tree).width(stageWidth * 0.43f).top().padRight(5f)
            add(detail).width(stageWidth * 0.49f).top().row()''','''            // Independent scrolling: a long organization no longer pushes management off-screen.
            val availableHeight = overviewScreen.stage.height * 0.69f
            val treeScroll = ScrollPane(tree, BaseScreen.skin).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            val detailScroll = ScrollPane(detail, BaseScreen.skin).apply {
                setFadeScrollBars(false)
                setScrollingDisabled(true, false)
            }
            add(treeScroll).width(stageWidth * 0.43f).height(availableHeight).top().padRight(5f)
            add(detailScroll).width(stageWidth * 0.49f).height(availableHeight).top().row()''')
p.write_text(s)
print('v0.7 source patched:',p)
subprocess.run(['git','diff','--check'],check=True)
subprocess.run(['git','add',str(p)],check=True)
subprocess.run(['git','commit','-m','v0.7: independent formation panels and compact hierarchy'],check=True)
subprocess.run(['git','push','origin','grand-strategy-formations'],check=True)
print('SUCCESS: v0.7 pushed; check Android build workflow')
