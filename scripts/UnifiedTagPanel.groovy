/*MODIFICATIONS & ENHANCEMENTS BY: aaa1386 (Github)
ORIGINAL CODE BY: euu2021 (Github)
 Copyright (C) 2026  aaa1386 (Github) - based on euu2021's original work
SPDX-License-Identifier: GPL-2.0-or-later

Added:
1-Add 🔀 Merge/Assign option, 
2-Add ⚡ Add Child Tag (Fast)option,
3-Add 📍 Locate Tag option
4-Merged Edit Mode and Assign Mode into a single unified interaction mode.
5-Assigning Ctrl+Up/Down for Navigating Search Results

The ⚡ Add Child (Fast) option is especially useful for large maps with many nodes because it creates new tags without the delay experienced by the standard Add Child Tag (Insert) method, which can become noticeably slower on large maps.
*/

// Copyright (c) 2026 euu2021
// SPDX-License-Identifier: GPL-2.0-or-later
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 2 of the License, or
// (at your option) any later version.


// @ExecutionModes({ON_SINGLE_NODE="/menu_bar/euu/tags"})


/***************************************************************************

 Unified Tag Panel — "one tag panel to rule them all".

 Discussion threads: https://github.com/freeplane/freeplane/discussions/2953 (announcement) · https://github.com/freeplane/freeplane/issues/2926 · https://github.com/freeplane/freeplane/discussions/2257

 One overlay panel that unifies Freeplane's three tag interfaces:
   1. the Tags side panel (view + filter + assign),
   2. the "Edit node tags" dialog (keyboard-first assign/create on the selected nodes),
   3. the "Manage tag categories" dialog (rename / move / delete / color the hierarchy).

 Everything goes through the PUBLIC scripting API:
   - reading:   mindMap.tagCategories.read()  (tree, colors, separator, revision)
   - node tags: node.tags.add/remove          (registers unknown tags on the map)
   - structure: mindMap.tagCategories.edit(MapTagCategoryInstructionRequest)
                (each edit = one undo step; node tags are rewritten along)

 The panel is an overlay on the map (like SearchPanel.groovy), anchored to the
 TOP-RIGHT corner of the tab — SearchPanel owns the top-left. By default it attaches to
 the tab it was launched on and stays there; turn on "Show on every tab" in Options… and
 the SAME panel moves to whatever tab becomes active, so it is always on screen.

 Usage:
   - launch the script       -> TOGGLES the panel on the current map: opens it (with the
                                focus already in the filter field), or hides it if it is
                                open. Hiding remembers the expansion, the filter text and
                                the wide/edit modes, so showing it again resumes where you
                                were. Bind it to a shortcut to flick the panel in and out.
   - type in the field       -> live search, accent-insensitive, with the typed text
                                HIGHLIGHTED inside every row that carries it
   - ▼ / ▽ next to the field -> the two search modes of issue #2926: ▼ filters (the tags
                                that do not match are hidden, matches auto-expand) and ▽
                                only highlights (nothing is hidden, the structure and the
                                scroll position stay put, and just the paths leading to a
                                match are opened). ▼ is the default.
   - ENTER in the field      -> assigns the best match to the selected node(s);
                                if nothing matches, CREATES the typed tag (use :: for
                                categories) and assigns it — the Edit-Tags workflow
   - Ctrl+ENTER              -> always creates the typed tag, even if something matches
   - ↑ / ↓ in the field      -> walk the tag-tree selection without leaving the field,
                                stopping ONLY on tags that match what was typed: the
                                ancestor categories drawn above a nested match are skipped.
                                Under a filter the selection starts on the first match, so
                                what ENTER will assign is always visible.
   - click a tag             -> toggles it on the selected node(s)  [✓ = on all,
                                ◐ = on some]. Clicks on the expand handle still fold.
   - favorites strip         -> the row of chips under the filter field: the tags pinned
                                in THIS map. Click a chip to toggle it on the selected
                                node(s); right-click for assign/remove, reorder, show in
                                the tree, or unpin. Pin a tag from its context menu in the
                                tree (★), or by dragging it onto the strip in edit mode.
                                Favorites are stored IN THE MAP FILE (mindMap.storage),
                                the same place and lifecycle as the tags themselves, and
                                they follow the tags through renames and moves made here.
   - ✎ on the title bar      -> EDIT MODE: clicks only select (no toggling), so the
                                hierarchy can be reorganized without side effects; and
                                DRAG & DROP reorganizes the tree — drop ON a tag to nest
                                under it, BETWEEN tags to position among siblings, on the
                                uncategorized bucket to uncategorize a leaf, or drag an
                                uncategorized tag into the tree to categorize it
                                (drag is disabled while a filter is active)
   - Alt+↑/↓                 -> move tag among siblings   (field or tree focused)
   - Alt+←                   -> promote (become sibling of its parent)
   - Alt+→                   -> demote (become child of the previous sibling)
   - F2                      -> rename inline (tree focused)
   - Insert                  -> add child tag (tree focused)
   - Delete pressed twice    -> delete the tag from the map (the key arms on the first
                                press so a stray Delete costs nothing; the context menu's
                                Delete acts at once — picking it is already deliberate) (tree focused)
   - Sort by usage           -> a second ordering, off by default (the normal one is the
                                map's own tag tree). Turned on from the context menu, the
                                category NESTING disappears: every tag becomes one row,
                                labelled with its qualified name, most used first. Handy to
                                see what you actually use; reordering (Alt+arrows, drag) is
                                off while it is on, since the rows are no longer siblings.
   - usage counts            -> each tag shows how many nodes carry it: "urgent (5)".
                                A category with subtags shows "own/whole category" —
                                "work (2/17)". A tag nobody uses shows (0) and is painted
                                faded, so it is safe to prune. The counts update live.
   - right-click             -> context menu: assign/remove, favorites, rename, add child,
                                delete, set/reset color, move ops, filter the MAP
                                by the tag (with folding restored on clear), the
                                usage commands "Hide unused tags" / "Delete all unused
                                tags", and the "Close after insert" option
   - Show on every tab       -> OFF by default. On, the panel moves to the tab you switch
                                to (one panel, one state — not a copy per tab), reloading
                                the tags and the favorites of the map it lands on.
   - Close after insert      -> ON by default: assigning a tag hides the panel and hands
                                the focus back to the map, so the whole gesture is
                                trigger → type → ENTER. Removing a tag never closes it.
                                It is a PROFILE preference (it has to outlive the panel it
                                closes), shared by every map, and it is remembered.
   - « on the title bar      -> pin the panel wide, ignoring hover (it grows leftwards,
                                since the panel hangs on the right edge); » restores it
   - ✕ / ESC                 -> close (also clears the map filter it applied)

 *****************************************************************/
import groovy.transform.Field

import org.freeplane.api.MapTagCategoryInstruction
import org.freeplane.api.MapTagCategoryInstructionRequest
import org.freeplane.api.MapTagCategoryInstructionType
import org.freeplane.api.MapTagTargetLocation
import org.freeplane.core.resources.ResourceController
import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.HtmlUtils
import org.freeplane.features.filter.Filter
import org.freeplane.features.filter.FilterController
import org.freeplane.features.filter.condition.ICondition
import org.freeplane.features.icon.Tag
import org.freeplane.features.icon.TagCategories
import org.freeplane.features.icon.Tags as CoreTags
import org.freeplane.features.map.IMapSelection
import org.freeplane.features.map.IMapChangeListener
import org.freeplane.features.map.INodeChangeListener
import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.MapChangeEvent
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeChangeEvent
import org.freeplane.features.map.NodeDeletionEvent
import org.freeplane.features.map.NodeModel
import org.freeplane.features.icon.IconController
import org.freeplane.features.mode.Controller
import org.freeplane.features.ui.IMapViewChangeListener
import org.freeplane.plugin.script.proxy.ProxyFactory
import org.freeplane.view.swing.map.MapView
import org.freeplane.view.swing.map.MapViewScrollPane

import javax.swing.event.ChangeListener

import javax.swing.*
import javax.swing.event.CellEditorListener
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.*

// after java.awt.*: without this, List becomes java.awt.List, which is not generic
import java.util.List


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ User settings ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// Merge panel variables
@Field JDialog mergePanel = null
@Field JTextField sourceField = null
@Field JTextField targetField = null
@Field JButton mergeButton = null
@Field JComboBox<String> modeCombo = null

@Field boolean isMergePanelOpen = false
@Field int selectionStep = 1
@Field String panelTextFontName = "Dialog"
@Field int panelTextFontSize = 25

@Field int retractedWidthFactor = 20
@Field int expandedWidthFactor = 4
@Field int wideWidthPercent = 40

@Field int retractDelayMs = 400
@Field int refreshCoalesceMs = 150
// Rebuilding the tree costs ~1 ms per row (MEASURED: ~9 ms for 9 rows, ~45 ms for 54),
// so rebuilding on every keystroke makes typing lag on a map with many tags. This waits
// for a short pause instead. ENTER never waits — it flushes the pending rebuild first.
@Field int filterDebounceMs = 120

// expand/retract transition, ease-out; 0 or 1 = no animation. Skipped above the row cap.
@Field int resizeAnimationSteps = 4
@Field int resizeAnimationStepMs = 15
@Field int resizeAnimationMaxRows = 80

//@Field TagRow hoveredRow = null

@Field int titleBarHeight = 35
@Field String titleBarText = "Tags"

// Usage count next to each tag (issue #2948): "urgent (5)". A category that has subtags
// shows "own/whole category" — "work (2/17)" = 2 nodes tagged exactly work, 17 nodes in
// the category counting the subtags. A tag nobody uses shows (0) and is painted faded.
@Field boolean showUsageCounts = true
@Field boolean showCategoryTotals = true
// faded look of an unused tag: how far its chip color is pulled toward the map background
@Field float unusedTagFadeRatio = 0.42f

// Highlight of the typed text inside each row (#2926). Amber with black text: the chip
// underneath can be any color, so the pair has to carry its own contrast.
@Field String matchHighlightHex = "#ffd54f"
// the toggle next to the filter field: filled = hiding what does not match, hollow = only
// highlighting
@Field String filterHidesSymbol = "▼"
@Field String highlightOnlySymbol = "▽"

// favorites strip (below the filter field): gaps between chips and around the rows
@Field int favoritesGapX = 4
@Field int favoritesGapY = 3
// the panel is anchored to the RIGHT edge, so it grows LEFTWARDS: « widens, » restores
// (mirror image of a left-anchored panel like SearchPanel)
@Field String wideOffSymbol = "«"
@Field String wideOnSymbol = "»"
@Field String closeButtonSymbol = "✕"
@Field String clearButtonSymbol = "⌫"
@Field int widthOfTheClearButton = 40

@Field String filterFieldPlaceholder = "Filter or create…"

// opaque background of the bars (see SearchPanel for the forbidden gray band)
@Field Color barColor = new Color(0x50, 0x50, 0x50)
// edit-mode button background while the mode is ON
@Field Color editModeOnColor = new Color(0xc6, 0x8a, 0x00)

@Field int panelBorderThickness = 1
@Field int panelBorderOpacity = 150

// what the map filter keeps visible besides the tagged nodes themselves
@Field boolean showTagFilterDescendants = false

// delete needs a second press within this window
@Field int deleteArmMs = 4000

// "Close after insert": hide the panel as soon as a tag is assigned, so trigger → type →
// ENTER ends back in the map with no extra gesture. Toggled from the context menu.
// Default ON. Removing a tag never closes — the option is about INSERTING.
@Field boolean closeAfterInsertDefault = true

// Colour given to a tag CREATED IN THIS PANEL (issue #2950), chosen in Options…:
//   "default" — Freeplane decides, deriving the colour from the name (what it has always
//               done here; kept as the factory setting so nothing changes unasked)
//   "inherit" — take the colour of the nearest ancestor category that already exists
//   "fixed"   — always the colour picked in the dialog
@Field String newTagColorModeDefault = "default"
// only used by "fixed", and as the last resort of "inherit" when a colour was ever picked
@Field String newTagColorFallback = "#3366cc"

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ User settings ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


@Field JDialog fastChildPanel = null
@Field JTextField fastParentField = null
@Field JButton fastAddButton = null
@Field JLabel fastStatusLabel = null
@Field boolean fastChildPanelOpen = false
@Field boolean fastWaitingForParent = false
@Field int fastRemoveDelayMs = 1
@Field JTextField fastChildNameField = null

@Field final String PANEL_NAME = "UnifiedTagPanel"
@Field final String FILTER_FIELD_NAME = "UnifiedTagPanelField"
@Field final String FAVORITES_STRIP_NAME = "UnifiedTagPanelFavorites"
// Favorites live in the MAP, exactly like the tags themselves: mindMap.storage is
// serialized into the .mm (verified: the attribute lands next to the <tags> element and
// survives save → close → reopen). A favorite names a tag OF THIS MAP, so a
// profile-global list — what the old TagFavoritesPanel used — would be meaningless here.
@Field final String FAVORITES_KEY = "unifiedTagPanel.favorites"
// PROFILE property, not map storage and not the view state: the option closes the panel,
// so it has to outlive it — and it is a preference about how the panel behaves, which has
// no business inside the user's .mm.
@Field final String CLOSE_AFTER_INSERT_KEY = "unifiedTagPanel.closeAfterInsert"
// Colour policy for tags created HERE (issue #2950). It is a policy of THIS panel: a tag
// born in Freeplane's own "Edit node tags", in a drag onto a node or in another script
// keeps Freeplane's behaviour. Hooking the app's tag creation globally is exactly what we
// are NOT doing — it would mean fighting the application on every version.
@Field final String NEW_TAG_COLOR_MODE_KEY = "unifiedTagPanel.newTagColorMode"
@Field final String NEW_TAG_COLOR_KEY = "unifiedTagPanel.newTagColor"
@Field final String SHOW_USAGE_COUNTS_KEY = "unifiedTagPanel.showUsageCounts"
// Second sorting mode (issue #2948 asked for it; it only became safe once the design said
// the hierarchy DISAPPEARS in this mode — with no tree on screen there is no manual order
// for a usage order to contradict). Off by default: the normal, hierarchical sort.
@Field final String SORT_BY_USAGE_KEY = "unifiedTagPanel.sortByUsage"
// "Show on every tab": ONE panel that moves to whatever tab is active, rather than one
// panel per tab. Same result for the stated purpose (it is always on screen), one set of
// state, no duplicated listeners. ⚠️ In a SPLIT view only the focused view carries it.
@Field final String FOLLOW_TABS_KEY = "unifiedTagPanel.followTabs"
// Filter vs highlight-only, the two search modes asked for in issue #2926 (comment
// 5073663796). ON (default) = what typing has always done here: the tags that do not match
// are hidden. OFF = nothing is hidden, the whole structure stays put and the matches are
// merely highlighted — which keeps the surrounding context and the scroll position.
@Field final String FILTER_HIDES_KEY = "unifiedTagPanel.filterHides"
@Field final String OPTIONS_DIALOG_KEY = "UnifiedTagPanelOptionsDialog"
@Field final String CLOSE_HANDLE_KEY = "UnifiedTagPanelCloseHandle"
@Field final String SUPPLIER_KEY = "UnifiedTagPanelSupplier"
// What the panel looked like when it was last hidden. Lives on the scroll pane (per tab),
// which outlives the panel — the @Fields do not.
@Field final String VIEW_STATE_KEY = "UnifiedTagPanelViewState"

@Field MapView boundMapView
@Field MapViewScrollPane boundScrollPane
// Where the overlay actually hangs. NOT the scroll pane: that one reports
// isOptimizedDrawingEnabled() == true, i.e. it promises Swing that its children never
// overlap — a promise we break the moment we put a panel over the viewport, and Swing then
// feels free to repaint the viewport alone, wiping us off the screen for a frame (the
// flicker seen with the Map Overview turned OFF; the overview being visible happened to
// mask it). Its PARENT, Freeplane's own MapViewPane, is the sanctioned overlay host:
// `isOptimizedDrawingEnabled() { return false; } // enable overlap`, and its layout ignores
// children added without constraints — which is exactly how the Map Overview itself hangs.
@Field Container overlayHost

@Field JPanel tagPanel
@Field JPanel favoritesStrip
@Field JTextField filterField
@Field Color filterFieldDefaultBackground
@Field JTree tagTree
@Field DefaultMutableTreeNode treeRootNode
@Field JScrollPane treeScrollPane
@Field JLabel statusLabel
@Field JButton wideButton

@Field JButton filterModeButton
@Field DefaultCellEditor treeCellEditor
@Field JTextField renameEditorField

// Click Locator variables
@Field final String CLICK_LOCATOR_KEY = "UnifiedTagPanelClickLocator"
@Field AWTEventListener tagClickLocatorListener = null

@Field Timer retractTimer
@Field Timer resizeAnimationTimer
@Field Timer refreshTimer
@Field Timer filterDebounceTimer
@Field MouseListener hoverListener
@Field ComponentListener viewportListener
@Field Object reservedAreaSupplier
@Field Object selectionRelay
@Field Object mapChangeRelay
@Field Object nodeChangeRelay
@Field Object viewChangeRelay

@Field boolean wideMode = false
@Field boolean editMode = false
@Field boolean mouseOverPanel = false
@Field boolean popupOpen = false

@Field String filterText = ""
// expansion memory across rebuilds, keyed by qualified name (the tree object is replaced)
@Field final Set<String> expandedQns = new LinkedHashSet<String>()
@Field boolean firstBuildDone = false

// assignment markers for the current node selection (qualified names)
@Field final Set<String> assignedAll = new HashSet<String>()
@Field final Set<String> assignedSome = new HashSet<String>()

// favorite tags of THIS map, in user order (qualified names)
@Field final List<String> favorites = new ArrayList<String>()

// Usage counts (issue #2948). directUsage = nodes carrying EXACTLY this tag;
// categoryUsage = nodes anywhere in the category (the tag itself or any subtag), each
// node counted once. MEASURED at ~50 ms over 22k nodes, so this is cached and only
// recomputed when something can have changed it — never on a filter keystroke.
@Field Map<String, Integer> directUsage = new HashMap<String, Integer>()
@Field Map<String, Integer> categoryUsage = new HashMap<String, Integer>()
@Field boolean usageCountsStale = true
@Field boolean hideUnusedTags = false

@Field boolean locateFromMap = false
@Field String armedDeleteQn = null
@Field long armedDeleteAt = 0L

// row being renamed via F2 (the cell editor commits into it)
@Field Object renamingRow = null
@Field boolean selectionFromMap = false
@Field final String LISTENER_KEY = "UnifiedTagPanelClickLocator"
@Field AWTEventListener clickLocatorListener = null

// row being dragged (edit mode only); the flavor marks our own transfers
@Field Object draggedRow = null
@Field DataFlavor tagDndFlavor

// map filter state (same restore-the-folding contract as SearchPanel)
@Field boolean mapFilterActive = false
@Field final Set<NodeModel> nodesUnfoldedByFilter = new LinkedHashSet<NodeModel>()

@Field Font cachedItemFont
@Field final Map<Character, Character> accentFoldCache = new java.util.concurrent.ConcurrentHashMap<Character, Character>()

@Field String markAll = "✓"
@Field String markSome = "◐"
@Field String favoriteSymbol = "★"


// Row payload for the tree. Top-level class: keep it dumb (no access to script methods).
class TagRow {
    String name            // leaf segment (or header text for synthetic rows)
    String qualifiedName   // full qualified content; null for synthetic rows
    List<String> path      // qualified segments; null for synthetic rows
    String colorHex        // #rrggbbaa from the API; may be null
    boolean uncategorized  // item of the uncategorized bucket
    boolean synthetic      // root / section header: not a tag
    String toString() { name }
}

// FlowLayout reports the preferred size of a SINGLE row, so wrapped rows get clipped in a
// narrow panel. This measures the rows for real. (Same implementation the user's
// TagFavoritesPanel carries; every Dimension read is cast because Groovy resolves
// .width/.height to the double-returning getters.)
class WrapLayout extends FlowLayout {
    WrapLayout(int align, int hgap, int vgap) { 
        super(align, hgap, vgap)
        // remove setComponentOrientation - this method does not exist in FlowLayout
    }

    @Override
    Dimension preferredLayoutSize(Container target) { return layoutSize(target, true) }

    @Override
    Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false)
        return new Dimension((int) minimum.width - (getHgap() + 1), (int) minimum.height)
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // during the first layout the target has no width yet: walk up until some
            // ancestor does, else assume unbounded (a single row) for this pass
            Container container = target
            int available = (int) container.getSize().width
            while (available == 0 && container.getParent() != null) {
                container = container.getParent()
                available = (int) container.getSize().width
            }
            if (available == 0) available = Integer.MAX_VALUE

            Insets insets = target.getInsets()
            int horizontalOverhead = insets.left + insets.right + getHgap() * 2
            int maxWidth = available - horizontalOverhead

            int totalWidth = 0
            int totalHeight = 0
            int rowWidth = 0
            int rowHeight = 0

            for (int i = 0; i < target.getComponentCount(); i++) {
                Component member = target.getComponent(i)
                if (!member.isVisible()) continue
                Dimension size = preferred ? member.getPreferredSize() : member.getMinimumSize()
                int memberWidth = (int) size.width
                int memberHeight = (int) size.height

                if (rowWidth != 0 && rowWidth + getHgap() + memberWidth > maxWidth) {
                    totalWidth = Math.max(totalWidth, rowWidth)
                    totalHeight += rowHeight + getVgap()
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += getHgap()
                rowWidth += memberWidth
                rowHeight = Math.max(rowHeight, memberHeight)
            }
            totalWidth = Math.max(totalWidth, rowWidth)
            totalHeight += rowHeight

            return new Dimension(totalWidth + horizontalOverhead,
                    totalHeight + insets.top + insets.bottom + getVgap() * 2)
        }
    }
}

class PanelSelectionRelay implements INodeSelectionListener {
    Closure handler
    @Override void onSelectionSetChange(IMapSelection selection) { handler.call() }
}

class PanelMapChangeRelay implements IMapChangeListener {
    Closure handler
    Closure structureHandler
    @Override void mapChanged(MapChangeEvent event) { handler.call(event) }
    // nodes coming and going change the usage counts without any tag event firing
    @Override void onNodeDeleted(NodeDeletionEvent event) { structureHandler.call() }
    @Override void onNodeInserted(NodeModel parent, NodeModel child, int newIndex) { structureHandler.call() }
}

class PanelNodeChangeRelay implements INodeChangeListener {
    Closure handler
    @Override void nodeChanged(NodeChangeEvent event) { handler.call(event) }
}

class PanelViewChangeRelay implements IMapViewChangeListener {
    Closure handler
    @Override void afterViewChange(Component oldView, Component newView) { handler.call(newView) }
}


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Main code ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

boundMapView = Controller.currentController.mapViewManager.mapView as MapView
if (boundMapView == null) return
boundScrollPane = SwingUtilities.getAncestorOfClass(MapViewScrollPane, boundMapView) as MapViewScrollPane
if (boundScrollPane == null) return   // view not anchored yet (mid map-switch); relaunch
overlayHost = resolveOverlayHost()

if (hidePanelIfOpen()) return

// closer registered BEFORE any external listener exists — every listener below is
// covered even if this run dies midway (the leak lesson from UtilityPanels)
boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, { -> closePanel() })

pickGlyphs()

retractTimer = new Timer(retractDelayMs, { ActionEvent e -> fitPanelBounds() } as ActionListener)
retractTimer.setRepeats(false)

refreshTimer = new Timer(refreshCoalesceMs, { ActionEvent e -> refreshTree() } as ActionListener)
refreshTimer.setRepeats(false)

filterDebounceTimer = new Timer(filterDebounceMs, { ActionEvent e -> applyFilterText() } as ActionListener)
filterDebounceTimer.setRepeats(false)

loadPanelPreferences()
createTagPanel()
loadFavorites()
restoreViewState()
startListeners()
refreshTree()
updateAssignedMarks()

return

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Main code ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Lifecycle ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// The trigger is a TOGGLE: with the panel open it hides it, so one gesture shows and hides.
// (Opening already puts the focus in the filter field, which is why the earlier "second
// trigger refocuses / widens" behaviour was not worth the ambiguity.)
//
// "Healthy panel" = the component exists by name AND the closer exists in the client property.
// Since the closer is registered BEFORE the listeners, that dual presence guarantees the
// listeners are tracked. A panel by name WITHOUT a closer = leftover from an execution that
// broke; in that case we purge and open a fresh one, instead of toggling a zombie.
boolean hidePanelIfOpen() {
    JPanel existingPanel = overlayHost.components.find { it.name == PANEL_NAME } as JPanel
    Object closer = boundScrollPane.getClientProperty(CLOSE_HANDLE_KEY)

    if (existingPanel != null && closer != null) {
        closer.call()
        return true
    }

    purgePanelArtifacts()
    return false
}

void disposeOptionsDialog() {
    Object dialog = boundScrollPane.getClientProperty(OPTIONS_DIALOG_KEY)
    if (dialog instanceof JDialog) ((JDialog) dialog).dispose()
    boundScrollPane.putClientProperty(OPTIONS_DIALOG_KEY, null)
}

void stashViewState() {
    boundScrollPane.putClientProperty(VIEW_STATE_KEY, [
            expanded  : new LinkedHashSet<String>(expandedQns),
            wide      : wideMode,
            hideUnused: hideUnusedTags,
            filter    : filterField != null ? filterField.getText() : ""
    ])
}

void restoreViewState() {
    Object stashed = boundScrollPane.getClientProperty(VIEW_STATE_KEY)
    if (!(stashed instanceof Map)) return
    Map state = (Map) stashed

    Object expanded = state.expanded
    if (expanded instanceof Collection) expandedQns.addAll((Collection<String>) expanded)
    // a restored state is deliberate — do NOT fall into the "first opening expands
    // everything" branch, even when the user had collapsed the whole tree
    firstBuildDone = true

    hideUnusedTags = state.hideUnused as boolean
    if (state.wide as boolean) toggleWideMode()

    String text = String.valueOf(state.filter ?: "")
    if (!text.isEmpty()) {
        filterField.setText(text)
        // adopt it directly: the refreshTree in main already builds with this filter, and
        // the debounce that setText armed then finds nothing new to apply
        filterText = text.trim()
    }
}

// The MapViewPane, when it is really there. Recognised by name so the script does not have
// to import a package that is not part of the scripting API; falls back to the scroll pane
// (the old, flicker-prone spot) if Freeplane ever changes the hierarchy.
Container resolveOverlayHost() {
    Container parent = boundScrollPane.getParent()
    if (parent != null && parent.getClass().getSimpleName() == "MapViewPane") return parent
    return boundScrollPane
}

// bounds of a component of the host, expressed in the SCROLL PANE's coordinates — which is
// what the viewport's reserved-area logic expects (it subtracts the viewport's own x/y)
Rectangle boundsInScrollPane(Component component) {
    Rectangle bounds = component.getBounds()
    if (overlayHost.is(boundScrollPane)) return bounds
    return SwingUtilities.convertRectangle(overlayHost, bounds, boundScrollPane)
}

// viewport rectangle in the HOST's coordinates, so the panel can be pinned to its edges
Rectangle viewportBoundsInHost() {
    Rectangle bounds = boundScrollPane.getViewport().getBounds()
    if (overlayHost.is(boundScrollPane)) return bounds
    return SwingUtilities.convertRectangle(boundScrollPane, bounds, overlayHost)
}

Component findByName(Container container, String name) {
    for (Component component : container.components) {
        if (name == component.getName()) return component
        if (component instanceof Container) {
            Component found = findByName((Container) component, name)
            if (found != null) return found
        }
    }
    return null
}

void purgePanelArtifacts() {
    Object previousCloser = boundScrollPane.getClientProperty(CLOSE_HANDLE_KEY)
    if (previousCloser != null) previousCloser.call()
    disposeOptionsDialog()

    overlayHost.components
            .findAll { it.name == PANEL_NAME }
            .each { overlayHost.remove(it) }
    boundScrollPane.components
            .findAll { it.name == PANEL_NAME }
            .each { boundScrollPane.remove(it) }

    Object leftoverSupplier = boundScrollPane.getClientProperty(SUPPLIER_KEY)
    if (leftoverSupplier != null) {
        boundScrollPane.removeReservedAreaSupplier(leftoverSupplier)
        boundScrollPane.putClientProperty(SUPPLIER_KEY, null)
    }
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)

    boundScrollPane.revalidate()
    boundScrollPane.repaint()
}

boolean panelAlive() {
    if (tagPanel == null) return false
    try {
        return Controller.currentController.mapViewManager.getMapViews().any { it.is(boundMapView) }
    } catch (Throwable t) {
        return boundScrollPane.isDisplayable()
    }
}

boolean aliveOrDetach() {
    if (panelAlive()) return true
    detachGlobalListeners()
    return false
}

void detachGlobalListeners() {
    def mapController = Controller.currentModeController.mapController
    if (selectionRelay != null) mapController.removeNodeSelectionListener(selectionRelay)
    if (mapChangeRelay != null) mapController.removeMapChangeListener(mapChangeRelay)
    if (nodeChangeRelay != null) mapController.removeNodeChangeListener(nodeChangeRelay)
    if (viewChangeRelay != null) {
        Controller.currentController.mapViewManager.removeMapViewChangeListener(viewChangeRelay)
    }
    selectionRelay = null
    mapChangeRelay = null
    nodeChangeRelay = null
    viewChangeRelay = null
}

void closePanel() {
    stashViewState()

    retractTimer.stop()
    refreshTimer.stop()
    filterDebounceTimer.stop()
    if (resizeAnimationTimer != null) {
        resizeAnimationTimer.stop()
        resizeAnimationTimer = null
    }
    if (tagTree != null && tagTree.isEditing()) tagTree.cancelEditing()
    disposeOptionsDialog()

    clearMapFilter(false)

    detachGlobalListeners()

    if (viewportListener != null) boundScrollPane.viewport.removeComponentListener(viewportListener)
    viewportListener = null

    if (reservedAreaSupplier != null) {
        boundScrollPane.removeReservedAreaSupplier(reservedAreaSupplier)
        reservedAreaSupplier = null
    }

    if (tagPanel != null) {
        overlayHost.remove(tagPanel)
        tagPanel = null
    }
    // Remove Click Locator
    if (tagClickLocatorListener != null) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(tagClickLocatorListener)
        tagClickLocatorListener = null
    }
    JRootPane anchor = findMainRootPane()
    if (anchor != null) {
        anchor.putClientProperty(CLICK_LOCATOR_KEY, null)
    }

    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, null)

    overlayHost.revalidate()
    overlayHost.repaint()
    boundMapView.requestFocusInWindow()
}

void startListeners() {
    viewportListener = new ComponentAdapter() {
        @Override
        void componentResized(ComponentEvent e) { fitPanelBounds() }
    }
    boundScrollPane.viewport.addComponentListener(viewportListener)

    def mapController = Controller.currentModeController.mapController

    // Selection relay definition
    selectionRelay = new PanelSelectionRelay(handler: { ->
        if (aliveOrDetach()) {
            updateAssignedMarks()
        }
    })
    mapController.addNodeSelectionListener(selectionRelay)

    mapChangeRelay = new PanelMapChangeRelay(
            handler: { MapChangeEvent event ->
                if (!aliveOrDetach()) return
                if (!event.map.is(boundMapView.map)) return
                if (event.property == TagCategories || event.property instanceof Tag) {
                    usageCountsStale = true
                    scheduleRefresh()
                }
            },
            structureHandler: { ->
                if (!aliveOrDetach()) return
                usageCountsStale = true
                scheduleRefresh()
            })
    mapController.addMapChangeListener(mapChangeRelay)

    nodeChangeRelay = new PanelNodeChangeRelay(handler: { NodeChangeEvent event ->
        if (!aliveOrDetach()) return
        if (event.property == CoreTags) {
            usageCountsStale = true
            updateAssignedMarks()
            scheduleRefresh()
        }
    })
    mapController.addNodeChangeListener(nodeChangeRelay)

    viewChangeRelay = new PanelViewChangeRelay(handler: { Component newView ->
        if (!aliveOrDetach()) return
        followToView(newView)
    })
    Controller.currentController.mapViewManager.addMapViewChangeListener(viewChangeRelay)
}

void scheduleRefresh() {
    refreshTimer.restart()
}

boolean isFollowTabs() {
    try {
        return ResourceController.getResourceController().getBooleanProperty(FOLLOW_TABS_KEY, false)
    } catch (Throwable t) {
        return false
    }
}

void applyFollowTabs(boolean enabled) {
    try {
        ResourceController.getResourceController().setProperty(FOLLOW_TABS_KEY, enabled)
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
    showStatus(enabled ? "The panel will move to whatever tab you switch to"
                       : "The panel stays on this tab")
}

void followToView(Component newViewComponent) {
    if (tagPanel == null || !isFollowTabs()) return
    if (!(newViewComponent instanceof MapView)) return
    MapView newView = (MapView) newViewComponent
    if (newView.is(boundMapView)) return

    MapViewScrollPane newScrollPane =
            SwingUtilities.getAncestorOfClass(MapViewScrollPane, newView) as MapViewScrollPane
    if (newScrollPane == null) return

    clearMapFilter(false)
    if (tagTree != null && tagTree.isEditing()) tagTree.cancelEditing()
    disposeOptionsDialog()

    Container oldHost = overlayHost
    if (reservedAreaSupplier != null) boundScrollPane.removeReservedAreaSupplier(reservedAreaSupplier)
    if (viewportListener != null) boundScrollPane.viewport.removeComponentListener(viewportListener)
    oldHost.remove(tagPanel)
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, null)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, null)

    boundMapView = newView
    boundScrollPane = newScrollPane
    overlayHost = resolveOverlayHost()

    overlayHost.add(tagPanel)
    overlayHost.setComponentZOrder(tagPanel, 0)
    if (reservedAreaSupplier != null) {
        boundScrollPane.addViewportReservedAreaSupplier(reservedAreaSupplier)
        boundScrollPane.putClientProperty(SUPPLIER_KEY, reservedAreaSupplier)
    }
    if (viewportListener != null) boundScrollPane.viewport.addComponentListener(viewportListener)
    boundScrollPane.putClientProperty(CLOSE_HANDLE_KEY, { -> closePanel() })

    oldHost.revalidate()
    oldHost.repaint()

    loadFavorites()
    usageCountsStale = true
    expandedQns.clear()
    firstBuildDone = false
    refreshTree()
    updateAssignedMarks()
    fitPanelBounds()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Lifecycle ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Panel ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void createTagPanel() {
    tagPanel = transparentPanel(new BorderLayout())
    tagPanel.setName(PANEL_NAME)
    tagPanel.setBorder(BorderFactory.createLineBorder(panelBorderColor(), panelBorderThickness))

    JPanel header = transparentPanel(new BorderLayout())
    header.add(createTitleBar(), BorderLayout.NORTH)
    header.add(createFilterBox(), BorderLayout.CENTER)
    header.add(createFavoritesStrip(), BorderLayout.SOUTH)

    tagPanel.add(header, BorderLayout.NORTH)
    tagPanel.add(createTreeArea(), BorderLayout.CENTER)

    statusLabel = new JLabel(" ")
    statusLabel.setFont(itemFont().deriveFont((float) (panelTextFontSize - 2)))
    statusLabel.setOpaque(true)
    statusLabel.setBackground(barColor)
    statusLabel.setForeground(barTextColor())
    tagPanel.add(statusLabel, BorderLayout.SOUTH)

    // MouseListener for panel - transfer focus to tree
    tagPanel.addMouseListener(new MouseAdapter() {
        @Override
        void mousePressed(MouseEvent e) {
            // When clicking on the panel (empty area of tree)
            Component clicked = e.getComponent()
            if (clicked == tagPanel) {
                // Transfer focus to tree
                if (tagTree != null) {
                    tagTree.requestFocusInWindow()
                }
            }
        }
    })

    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_ESCAPE, 0, "closeUnifiedTagPanel", { closePanel() })

    // Keyboard shortcuts for reordering (always available, no edit mode needed)
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK, "tagMoveUp", { moveSelectedTag('up') })
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK, "tagMoveDown", { moveSelectedTag('down') })
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK, "tagPromote", { moveSelectedTag('promote') })
    bindKey(tagPanel, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
            KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK, "tagDemote", { moveSelectedTag('demote') })

    tagPanel.setBounds(0, 0, retractedWidth(), 10)

    overlayHost.add(tagPanel)
    overlayHost.setComponentZOrder(tagPanel, 0)

    reservedAreaSupplier = { ->
        tagPanel != null && tagPanel.isVisible() ? boundsInScrollPane(tagPanel) : MapViewScrollPane.EMPTY_RECTANGLE
    } as MapViewScrollPane.ViewportReservedAreaSupplier
    boundScrollPane.addViewportReservedAreaSupplier(reservedAreaSupplier)
    boundScrollPane.putClientProperty(SUPPLIER_KEY, reservedAreaSupplier)

    hoverListener = new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            mouseOverPanel = true
            retractTimer.stop()
            fitPanelBounds()
        }

        @Override
        void mouseExited(MouseEvent e) {
            mouseOverPanel = false
            retractTimer.restart()
            if (boundMapView != null && !popupOpen) {
                boundMapView.requestFocusInWindow()
            }
        }
    }
    addHoverListenerRecursively(tagPanel)

    fitPanelBounds()
    overlayHost.revalidate()
    overlayHost.repaint()
    filterField.requestFocusInWindow()
}

JPanel createTitleBar() {
    Color barForeground = barTextColor()

    JPanel titleBar = new JPanel(new BorderLayout())
    titleBar.setOpaque(true)
    titleBar.setBackground(barColor)
    titleBar.setPreferredSize(new Dimension(0, titleBarHeight))

    JLabel title = new JLabel(" " + titleBarText)
    title.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize - 2))
    title.setForeground(barForeground)

    wideButton = createBarButton(wideOffSymbol, barForeground, wideTooltip(), { toggleWideMode() })
    JButton closeButton = createBarButton(closeButtonSymbol, barForeground, "Close the panel", { closePanel() })
    closeButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { closeButton.setForeground(Color.RED) }

        @Override
        void mouseExited(MouseEvent e) { closeButton.setForeground(barForeground) }
    })

    JPanel barButtons = transparentPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0))
    barButtons.add(wideButton)
    barButtons.add(closeButton)

    titleBar.add(title, BorderLayout.CENTER)
    titleBar.add(barButtons, BorderLayout.EAST)
    return titleBar
}

JButton createBarButton(String symbol, Color barForeground, String tooltip, Closure action) {
    JButton button = new JButton(symbol)
    button.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize - 2))
    button.setForeground(barForeground)
    button.setToolTipText(tooltip)
    button.setPreferredSize(new Dimension(titleBarHeight, titleBarHeight))
    button.setOpaque(false)
    button.setContentAreaFilled(false)
    button.setBorderPainted(false)
    button.setFocusPainted(false)
    button.setMargin(new Insets(0, 0, 0, 0))
    Color hoverBackground = barHoverColor()
    button.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            button.setOpaque(true)
            button.setBackground(hoverBackground)
            button.repaint()
        }

        @Override
        void mouseExited(MouseEvent e) {
            button.setOpaque(false)
            button.repaint()
        }
    })
    button.addActionListener({ ActionEvent e -> action.call() } as ActionListener)
    return button
}

String wideTooltip() {
    return wideMode ? "Restore the normal width" : "Expand to " + wideWidthPercent + "% of the map and pin"
}

void toggleWideMode() {
    wideMode = !wideMode
    wideButton.setText(wideMode ? wideOnSymbol : wideOffSymbol)
    wideButton.setToolTipText(wideTooltip())
    fitPanelBounds()
}


JPanel createFilterBox() {
    filterField = new JTextField() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g)
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create()
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.setFont(getFont().deriveFont(Font.ITALIC))
                    g2.setColor(blendColors(getForeground(), getBackground(), 0.55f))
                    g2.drawString(filterFieldPlaceholder, getInsets().left,
                            getInsets().top + g2.getFontMetrics().getAscent())
                } finally {
                    g2.dispose()
                }
            }
        }
    }
    filterField.setName(FILTER_FIELD_NAME)
    filterField.setFont(itemFont())
    filterFieldDefaultBackground = filterField.getBackground()
    filterField.setBorder(BorderFactory.createCompoundBorder(
            filterField.getBorder(), BorderFactory.createEmptyBorder(2, 6, 2, 6)))

    // Click on search box
    filterField.addMouseListener(new MouseAdapter() {
        @Override
        void mouseClicked(MouseEvent e) {
            if (!filterField.hasFocus()) {
                filterField.requestFocusInWindow()
            }
        }
    })

    filterField.getDocument().addDocumentListener(new DocumentListener() {
        @Override
        void insertUpdate(DocumentEvent e) { onFilterEdited() }

        @Override
        void removeUpdate(DocumentEvent e) { onFilterEdited() }

        @Override
        void changedUpdate(DocumentEvent e) { onFilterEdited() }
    })

    filterField.addFocusListener(new FocusAdapter() {
        @Override
        void focusGained(FocusEvent e) { fitPanelBounds() }

        @Override
        void focusLost(FocusEvent e) { retractTimer.restart() }
    })

    // کلیدهای Enter برای ایجاد/اختصاص تگ
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, 0,
            "assignBestMatch", { commitFieldAction(false) })
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK,
            "forceCreateTag", { commitFieldAction(true) })

    // ===== کلیدهای پیمایش نتایج جستجو (وقتی فیلد فوکوس دارد) =====
    // 1. کلیدهای ساده Up/Down
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_DOWN, 0,
            "nextTagRow", { moveTreeSelection(1) })
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_UP, 0,
            "previousTagRow", { moveTreeSelection(-1) })
    
    // 2. کلیدهای Ctrl+Up/Down (همین کار را انجام میدهند)
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK,
            "filterSearchNext", { moveTreeSelection(1) })
    bindKey(filterField, JComponent.WHEN_FOCUSED, KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK,
            "filterSearchPrevious", { moveTreeSelection(-1) })

    // 3. کلیدهای Ctrl+Up/Down در سطح پنجره (برای زمانی که فیلد فوکوس ندارد)
    bindKey(filterField, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK,
            "filterSearchNextGlobal", { moveTreeSelection(1) })
    bindKey(filterField, JComponent.WHEN_IN_FOCUSED_WINDOW, KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK,
            "filterSearchPreviousGlobal", { moveTreeSelection(-1) })

    // دکمه Add Child Tag (سریع)
    JButton fastAddChildBtn = new JButton("⚡ Add Child Tag")
    fastAddChildBtn.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize))
    fastAddChildBtn.setMargin(new Insets(4, 8, 4, 8))
    fastAddChildBtn.setFocusPainted(false)
    fastAddChildBtn.setBackground(new Color(255, 220, 150))
    fastAddChildBtn.setForeground(new Color(150, 80, 0))
    fastAddChildBtn.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(new Color(200, 120, 0), 1),
        BorderFactory.createEmptyBorder(4, 10, 4, 10)
    ))
    fastAddChildBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
    fastAddChildBtn.setToolTipText("Add child tag fast (auto-removes after 2s)")
    fastAddChildBtn.addActionListener({ ActionEvent e -> 
        openFastChildPanel()
    } as ActionListener)

    // =====  Merge =====
    JButton mergeBtn = new JButton("🔀 Merge / Assign")    
    mergeBtn.setFont(new Font(panelTextFontName, Font.BOLD, panelTextFontSize))
    mergeBtn.setMargin(new Insets(4, 8, 4, 8))
    mergeBtn.setFocusPainted(false)
    mergeBtn.setBackground(new Color(220, 240, 220))
    mergeBtn.setForeground(new Color(0, 120, 0))
    mergeBtn.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(new Color(0, 150, 0), 1),
        BorderFactory.createEmptyBorder(4, 10, 4, 10)
    ))
    mergeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
    mergeBtn.setToolTipText("Open Merge/Assign panel")
    mergeBtn.addActionListener({ ActionEvent e -> 
        openMergePanel()
    } as ActionListener)

    JButton clearButton = new JButton(clearButtonSymbol)
    clearButton.setToolTipText("Clear the filter")
    clearButton.setFont(itemFont())
    clearButton.setPreferredSize(new Dimension(widthOfTheClearButton, 30))
    clearButton.setForeground(barTextColor())
    clearButton.setBackground(barColor)
    clearButton.setContentAreaFilled(false)
    clearButton.setOpaque(true)
    clearButton.setBorder(BorderFactory.createEmptyBorder())
    clearButton.setFocusPainted(false)
    Color clearHoverBackground = barHoverColor()
    clearButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { clearButton.setBackground(clearHoverBackground) }

        @Override
        void mouseExited(MouseEvent e) { clearButton.setBackground(barColor) }
    })
    clearButton.addActionListener({ ActionEvent e ->
        filterField.setText("")
        filterField.requestFocusInWindow()
    } as ActionListener)

    // Filter Mode button
    filterModeButton = new JButton(isFilterHides() ? filterHidesSymbol : highlightOnlySymbol)
    filterModeButton.setName("UnifiedTagPanelFilterModeButton")
    filterModeButton.setToolTipText(filterModeTooltip())
    filterModeButton.setFont(itemFont())
    filterModeButton.setPreferredSize(new Dimension(widthOfTheClearButton, 30))
    filterModeButton.setForeground(barTextColor())
    filterModeButton.setBackground(barColor)
    filterModeButton.setContentAreaFilled(false)
    filterModeButton.setOpaque(true)
    filterModeButton.setBorder(BorderFactory.createEmptyBorder())
    filterModeButton.setFocusPainted(false)
    filterModeButton.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) { filterModeButton.setBackground(barHoverColor()) }

        @Override
        void mouseExited(MouseEvent e) { filterModeButton.setBackground(barColor) }
    })
    filterModeButton.addActionListener({ ActionEvent e -> applyFilterHides(!isFilterHides()) } as ActionListener)

    // Place buttons
       
    JPanel buttons = transparentPanel(new GridLayout(1, 2, 0, 0))
    buttons.add(filterModeButton)
    buttons.add(clearButton)

    JPanel filterBox = transparentPanel(new BorderLayout())
    filterBox.add(filterField, BorderLayout.CENTER)
    filterBox.add(buttons, BorderLayout.EAST)
    
    return filterBox
}


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Usage counts ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// One pass over the map, tallying two things per node:
//  - each tag exactly as assigned            -> directUsage
//  - that tag AND every ancestor category    -> categoryUsage
// The per-node dedup (impliedHere) is what keeps a node tagged both 'work' and
// 'work::urgent' from being counted twice in the 'work' category.
void ensureUsageCounts() {
    if (!usageCountsStale) return
    usageCountsStale = false

    Map<String, Integer> direct = new HashMap<String, Integer>()
    Map<String, Integer> category = new HashMap<String, Integer>()
    String sep = separator()
    IconController iconController = IconController.getController()

    List<NodeModel> stack = new ArrayList<NodeModel>()
    stack.add(boundMapView.getMap().getRootNode())
    Set<String> impliedHere = new HashSet<String>()
    while (!stack.isEmpty()) {
        NodeModel current = stack.remove(stack.size() - 1)
        List tags = iconController.getTags(current)
        if (!tags.isEmpty()) {
            impliedHere.clear()
            tags.each { tag ->
                String content = tag.getContent()
                direct.put(content, (direct.get(content) ?: 0) + 1)
                impliedHere.add(content)
                int at = content.indexOf(sep)
                while (at >= 0) {
                    impliedHere.add(content.substring(0, at))
                    at = content.indexOf(sep, at + sep.length())
                }
            }
            impliedHere.each { category.put(it, (category.get(it) ?: 0) + 1) }
        }
        current.getChildren().each { stack.add(it) }
    }

    directUsage = direct
    categoryUsage = category
}

String usageTooltip(TagRow row, boolean hasChildren) {
    if (row == null || row.qualifiedName == null) return null
    if (!showUsageCounts) return row.qualifiedName
    int direct = directUsageOf(row.qualifiedName)
    int total = categoryUsageOf(row.qualifiedName)
    StringBuilder text = new StringBuilder(row.qualifiedName)
    text.append(" — ").append(direct).append(direct == 1 ? " node" : " nodes")
    if (hasChildren && total != direct) {
        text.append("; ").append(total).append(" in the whole category")
    }
    if (total == 0) text.append(" (unused)")
    return text.toString()
}

int directUsageOf(String qn) {
    return qn == null ? 0 : (directUsage.get(qn) ?: 0)
}

int categoryUsageOf(String qn) {
    return qn == null ? 0 : (categoryUsage.get(qn) ?: 0)
}

boolean isTagUnused(String qn) {
    return categoryUsageOf(qn) == 0
}

boolean isSortByUsage() {
    try {
        return ResourceController.getResourceController().getBooleanProperty(SORT_BY_USAGE_KEY, false)
    } catch (Throwable t) {
        return false
    }
}

void applySortByUsage(boolean enabled) {
    try {
        ResourceController.getResourceController().setProperty(SORT_BY_USAGE_KEY, enabled)
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
    refreshTree()
}

void buildFlatUsageRows(DefaultMutableTreeNode root, def state, String needle) {
    List<Map> entries = []
    def collect
    collect = { cat ->
        entries.add([qn: cat.qualifiedName, path: new ArrayList<String>(cat.path),
                     color: cat.color, uncategorized: false])
        cat.children.each { collect(it) }
    }
    state.categories.each { collect(it) }
    state.uncategorizedTags.each { item ->
        entries.add([qn: item.qualifiedName, path: new ArrayList<String>(item.path),
                     color: item.color, uncategorized: true])
    }

    entries = entries.findAll { entry ->
        (!hideUnusedTags || categoryUsageOf((String) entry.qn) > 0) &&
                (needle.isEmpty() || foldAccents(((String) entry.qn).toLowerCase()).contains(needle))
    }
    entries.sort { a, b ->
        int byUsage = categoryUsageOf((String) b.qn) <=> categoryUsageOf((String) a.qn)
        byUsage != 0 ? byUsage : ((String) a.qn).compareToIgnoreCase((String) b.qn)
    }

    entries.each { entry ->
        root.add(new DefaultMutableTreeNode(new TagRow(
                name: (String) entry.qn, qualifiedName: (String) entry.qn,
                path: (List<String>) entry.path, colorHex: (String) entry.color,
                uncategorized: (boolean) entry.uncategorized)))
    }
}

String usageSuffix(TagRow row, boolean hasChildren) {
    if (!showUsageCounts || row == null || row.qualifiedName == null) return ""
    int direct = directUsageOf(row.qualifiedName)
    int total = categoryUsageOf(row.qualifiedName)
    if (!showCategoryTotals || !hasChildren || direct == total) return " (" + total + ")"
    return " (" + direct + "/" + total + ")"
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Usage counts ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Favorites ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

JPanel createFavoritesStrip() {
    favoritesStrip = transparentPanel(new WrapLayout(FlowLayout.RIGHT, favoritesGapX, favoritesGapY))
    favoritesStrip.setName(FAVORITES_STRIP_NAME)
    favoritesStrip.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, panelBorderColor()))
    favoritesStrip.setVisible(false)
    // Only setComponentOrientation is sufficient
favoritesStrip.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT)
    
    favoritesStrip.setTransferHandler(new TransferHandler() {
        @Override
        boolean canImport(TransferHandler.TransferSupport support) {
            return tagDndFlavor != null && support.isDataFlavorSupported(tagDndFlavor)
        }

        @Override
        boolean importData(TransferHandler.TransferSupport support) {
            if (!canImport(support)) return false
            String qn = String.valueOf(support.getTransferable().getTransferData(tagDndFlavor))
            return addFavorite(qn)
        }
    })
    return favoritesStrip
}

void loadFavorites() {
    favorites.clear()
    try {
        Object stored = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.storage[FAVORITES_KEY]
        if (stored != null) {
            String.valueOf(stored).readLines().each { String line ->
                String qn = line.trim()
                if (!qn.isEmpty() && !favorites.contains(qn)) favorites.add(qn)
            }
        }
    } catch (Throwable t) {
        showStatus("Could not read the favorites: " + t.getMessage())
    }
}

void saveFavorites() {
    try {
        ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.storage[FAVORITES_KEY] = favorites.join("\n")
    } catch (Throwable t) {
        showStatus("Could not save the favorites: " + t.getMessage())
    }
}

boolean isFavorite(String qn) {
    return qn != null && favorites.contains(qn)
}

boolean addFavorite(String qn) {
    if (qn == null || qn.isEmpty() || favorites.contains(qn)) return false
    favorites.add(qn)
    saveFavorites()
    rebuildFavoritesStrip()
    remeasureRows([qn])
    if (tagTree != null) tagTree.repaint()
    showStatus("'" + qn + "' added to the favorites of this map")
    return true
}

void removeFavorite(String qn) {
    if (!favorites.remove(qn)) return
    saveFavorites()
    rebuildFavoritesStrip()
    remeasureRows([qn])
    if (tagTree != null) tagTree.repaint()
    showStatus("'" + qn + "' removed from the favorites")
}

void moveFavorite(String qn, int delta) {
    int index = favorites.indexOf(qn)
    if (index < 0) return
    int target = index + delta
    if (target < 0 || target >= favorites.size()) return
    favorites.remove(index)
    favorites.add(target, qn)
    saveFavorites()
    rebuildFavoritesStrip()
}

void remapFavorites(String oldQualifiedName, String newQualifiedName) {
    if (oldQualifiedName == null || favorites.isEmpty()) return
    String prefix = oldQualifiedName + separator()
    boolean changed = false

    List<String> updated = new ArrayList<String>()
    favorites.each { String qn ->
        String mapped
        if (qn == oldQualifiedName) {
            mapped = newQualifiedName
        } else if (qn.startsWith(prefix)) {
            mapped = newQualifiedName == null ? null
                    : newQualifiedName + separator() + qn.substring(prefix.length())
        } else {
            mapped = qn
        }
        if (mapped != qn) changed = true
        if (mapped != null && !updated.contains(mapped)) updated.add(mapped)
    }

    if (!changed) return
    favorites.clear()
    favorites.addAll(updated)
    saveFavorites()
    rebuildFavoritesStrip()
}

void rebuildFavoritesStrip() {
    if (favoritesStrip == null) return
    favoritesStrip.removeAll()
    favoritesStrip.setVisible(!favorites.isEmpty())
    favorites.each { String qn -> favoritesStrip.add(favoriteChip(qn)) }
    favoritesStrip.revalidate()
    favoritesStrip.repaint()
    fitPanelBounds()
}

JLabel favoriteChip(String qn) {
    boolean known = rowByQn(qn) != null
    Color background = colorForQualifiedName(qn)

    JLabel chip = new JLabel()
    chip.putClientProperty("tagQn", qn)
    chip.setOpaque(true)
    chip.setBackground(background)
    chip.setForeground(UITools.getTextColorForBackground(background))
    chip.setFont(itemFont().deriveFont((float) (panelTextFontSize - 1)))
    chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(known ? background : Color.RED, 1),
            BorderFactory.createEmptyBorder(1, 5, 1, 5)))
    chip.setToolTipText(known ? qn : qn + " — no longer in this map (clicking recreates it)")
    applyChipText(chip)

    chip.addMouseListener(new MouseAdapter() {
        @Override
        void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showFavoriteMenu(chip, qn, e)
                return
            }
            if (SwingUtilities.isLeftMouseButton(e)) toggleTagQn(qn)
        }

        @Override
        void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) showFavoriteMenu(chip, qn, e)
        }
    })
    chip.addMouseListener(hoverListener)
    return chip
}

void applyChipText(JLabel chip) {
    String qn = (String) chip.getClientProperty("tagQn")
    String marker = assignedAll.contains(qn) ? markAll + " " : assignedSome.contains(qn) ? markSome + " " : ""
    chip.setText(marker + shortNameOf(qn))
}

String shortNameOf(String qn) {
    String sep = separator()
    int at = qn.lastIndexOf(sep)
    return at < 0 ? qn : qn.substring(at + sep.length())
}

Color colorForQualifiedName(String qn) {
    TagRow row = rowByQn(qn)
    if (row != null) return chipColor(row)
    return new Tag(qn).getColor()
}

void showFavoriteMenu(JLabel chip, String qn, MouseEvent e) {
    JPopupMenu menu = new JPopupMenu()
    menu.add(menuItem("Assign to selected node(s)", { assignTagQn(qn) }))
    menu.add(menuItem("Remove from selected node(s)", { removeTagQn(qn) }))
    menu.addSeparator()
    if (rowByQn(qn) != null) {
        menu.add(menuItem("Show in the tree", { selectRowByQn(qn) }))
    }
    menu.add(menuItem("Move left", { moveFavorite(qn, -1) }))
    menu.add(menuItem("Move right", { moveFavorite(qn, 1) }))
    menu.addSeparator()
    menu.add(menuItem("Remove from favorites", { removeFavorite(qn) }))
    addPanelOptionItems(menu)
    attachPopupGuard(menu)
    menu.show(chip, e.getX(), e.getY())
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Favorites ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/

void onFilterEdited() {
    filterDebounceTimer.restart()
}

boolean applyFilterText() {
    filterDebounceTimer.stop()
    String text = filterField.getText().trim()
    if (text == filterText) return false
    filterText = text
    refreshTree()
    return true
}


JComponent createTreeArea() {
    treeRootNode = new DefaultMutableTreeNode(new TagRow(name: "tags", synthetic: true))
    tagTree = new JTree(new DefaultTreeModel(treeRootNode)) {
        @Override
        Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize()
        }
    }
    tagTree.setRootVisible(false)
    tagTree.setShowsRootHandles(true)
    tagTree.setOpaque(false)
    tagTree.setRowHeight(0)
    tagTree.setFont(itemFont())
    tagTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION)
    tagTree.setToggleClickCount(0)
    tagTree.setCellRenderer(createTagRenderer())

    tagTree.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT)

    renameEditorField = new JTextField()
    renameEditorField.setFont(itemFont())
    treeCellEditor = new DefaultCellEditor(renameEditorField) {
        @Override
        boolean isCellEditable(java.util.EventObject anEvent) { return anEvent == null }
    }
    tagTree.setEditable(true)
    tagTree.setCellEditor(treeCellEditor)
    tagTree.setInvokesStopCellEditing(true)
    treeCellEditor.addCellEditorListener([
            editingStopped : { ChangeEvent e -> commitRename() },
            editingCanceled: { ChangeEvent e -> renamingRow = null }
    ] as CellEditorListener)

    tagTree.addTreeExpansionListener(new TreeExpansionListener() {
        @Override
        void treeExpanded(TreeExpansionEvent event) {
            TagRow row = rowOf(event.getPath())
            if (row != null && row.qualifiedName != null) expandedQns.add(row.qualifiedName)
            fitPanelBounds()
        }

        @Override
        void treeCollapsed(TreeExpansionEvent event) {
            TagRow row = rowOf(event.getPath())
            if (row != null && row.qualifiedName != null) expandedQns.remove(row.qualifiedName)
            fitPanelBounds()
        }
    })

    // ===== کلیدهای پیمایش معمولی درخت =====
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_DOWN, 0,
            "treeNextRow", { 
                int rows = tagTree.getRowCount()
                if (rows > 0) {
                    int current = tagTree.getLeadSelectionRow()
                    int next = current < 0 ? 0 : Math.min(current + 1, rows - 1)
                    tagTree.setSelectionRow(next)
                    tagTree.scrollRowToVisible(next)
                }
            })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_UP, 0,
            "treePreviousRow", { 
                int rows = tagTree.getRowCount()
                if (rows > 0) {
                    int current = tagTree.getLeadSelectionRow()
                    int prev = current < 0 ? rows - 1 : Math.max(current - 1, 0)
                    tagTree.setSelectionRow(prev)
                    tagTree.scrollRowToVisible(prev)
                }
            })
    
    // ===== کلیدهای Ctrl+Up/Down برای پیمایش نتایج جستجو (وقتی درخت فوکوس دارد) =====
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK,
            "filterSearchNext", { moveTreeSelection(1) })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK,
            "filterSearchPrevious", { moveTreeSelection(-1) })

    // Alt+directions for moving
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK,
            "tagMoveUp", { moveSelectedTag('up') })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK,
            "tagMoveDown", { moveSelectedTag('down') })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK,
            "tagPromote", { moveSelectedTag('promote') })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK,
            "tagDemote", { moveSelectedTag('demote') })

    // Hover on tags (select only, without changing focus)
    tagTree.addMouseMotionListener(new MouseMotionAdapter() {
        @Override
        void mouseMoved(MouseEvent e) {
            TreePath path = tagTree.getPathForLocation(e.getX(), e.getY())
            if (path != null) {
                TagRow row = rowOf(path)
                if (row != null && !row.synthetic) {
                    tagTree.setSelectionPath(path)
                    // ===== فوکوس خودکار روی جدول =====
                    if (!tagTree.hasFocus()) {
                        tagTree.requestFocusInWindow()
                    }
                }
            }
        }
    })

    tagTree.addMouseListener(new MouseAdapter() {
        @Override
        void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showContextMenu(e)
                return
            }
            if (!SwingUtilities.isLeftMouseButton(e)) return
            
            TreePath path = tagTree.getPathForLocation(e.getX(), e.getY())
            if (path == null) return
            TagRow row = rowOf(path)
            if (row == null || row.synthetic) return
            
            tagTree.setSelectionPath(path)
            
            if (isMergePanelOpen) {
                selectTagFromTree()
                return
            }
            
            toggleTagOnSelection(row)
            
            if (fastWaitingForParent) {
                selectFastParentTag()
            }
        }
        
        @Override
        void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) {
                showContextMenu(e)
            }
        }
    })

    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_ENTER, 0,
            "toggleSelectedTag", { TagRow row = selectedRow(); if (row != null) toggleTagOnSelection(row) })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_SPACE, 0,
            "toggleSelectedTagSpace", { TagRow row = selectedRow(); if (row != null) toggleTagOnSelection(row) })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_F2, 0,
            "renameSelectedTag", { startRename() })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_INSERT, 0,
            "addChildTag", { addChildToSelected() })
    bindKey(tagTree, JComponent.WHEN_FOCUSED, KeyEvent.VK_DELETE, 0,
            "deleteSelectedTag", { deleteSelectedTag() })

    tagDndFlavor = new DataFlavor('application/x-unified-tag-panel; class=java.lang.String', 'UnifiedTagPanel tag')
    tagTree.setDragEnabled(true)
    tagTree.setDropMode(DropMode.ON_OR_INSERT)
    tagTree.setTransferHandler(createTreeDndHandler())
    tagTree.putClientProperty('UnifiedTagPanelDropTest', { String draggedQn, String parentQn, Integer childIndex ->
        TagRow dragged = rowByQn(draggedQn)
        TagRow parent = parentQn == null ? null : (parentQn == '::uncategorized::' ? uncategorizedHeaderRow() : rowByQn(parentQn))
        if (dragged == null) return 'dragged not found'
        Map plan = planDropMove(dragged, parent, childIndex)
        if (plan == null) return 'rejected'
        return performDropMove(plan)
    })

    treeScrollPane = new JScrollPane(tagTree)
    treeScrollPane.setOpaque(false)
    treeScrollPane.getViewport().setOpaque(false)
    treeScrollPane.setBorder(BorderFactory.createEmptyBorder())
treeScrollPane.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT)

    // ===== MouseListener برای اسکرول پن (فوکوس خودکار هنگام ورود ماوس) =====
    treeScrollPane.addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            // ===== وقتی ماوس وارد اسکرول پن می‌شود، فوکوس به جدول =====
            if (tagTree != null && !tagTree.hasFocus()) {
                tagTree.requestFocusInWindow()
            }
        }
        
        @Override
        void mousePressed(MouseEvent e) {
            if (tagTree != null) {
                tagTree.requestFocusInWindow()
            }
        }
    })

    // ===== MouseListener برای ویوپورت (فوکوس خودکار هنگام ورود ماوس) =====
    treeScrollPane.getViewport().addMouseListener(new MouseAdapter() {
        @Override
        void mouseEntered(MouseEvent e) {
            // ===== وقتی ماوس وارد ویوپورت می‌شود، فوکوس به جدول =====
            if (tagTree != null && !tagTree.hasFocus()) {
                tagTree.requestFocusInWindow()
            }
        }
        
        @Override
        void mousePressed(MouseEvent e) {
            if (tagTree != null) {
                tagTree.requestFocusInWindow()
            }
        }
    })

    return treeScrollPane
}


/*
 ============================================================================
 Tree model / rendering
 ============================================================================
*/

def readState() {
    return ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.tagCategories.read()
}

String separator() {
    try {
        return readState().categorySeparator ?: "::"
    } catch (Throwable t) {
        return "::"
    }
}

void refreshTree() {
    refreshTree(false)
}

void refreshTree(boolean force) {
    if (tagPanel == null || tagTree == null) return
    if (tagTree.getDropLocation() != null) {
        scheduleRefresh()
        return
    }
    if (tagTree.isEditing()) {
        if (!force) {
            scheduleRefresh()
            return
        }
        tagTree.cancelEditing()
    }

    // Save previous selection
    String oldSelectedQn = null
    TreePath oldSelection = tagTree.getSelectionPath()
    if (oldSelection != null) {
        TagRow oldRow = rowOf(oldSelection)
        if (oldRow != null && oldRow.qualifiedName != null) {
            oldSelectedQn = oldRow.qualifiedName
        }
    }

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }

    ensureUsageCounts()

    String needle = foldAccents(filterText.toLowerCase())
    String pruningNeedle = isFilterHides() ? needle : ""

    DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode(new TagRow(name: "tags", synthetic: true))
    if (isSortByUsage()) {
        buildFlatUsageRows(newRoot, state, pruningNeedle)
    } else {
        state.categories.each { cat ->
            DefaultMutableTreeNode child = buildCategoryNode(cat, pruningNeedle)
            if (child != null) newRoot.add(child)
        }

        List uncategorizedMatches = state.uncategorizedTags.findAll { item ->
            (!hideUnusedTags || categoryUsageOf(item.qualifiedName) > 0) &&
                    (pruningNeedle.isEmpty() || foldAccents(item.qualifiedName.toLowerCase()).contains(pruningNeedle))
        }
        if (!uncategorizedMatches.isEmpty()) {
            DefaultMutableTreeNode bucket = new DefaultMutableTreeNode(
                    new TagRow(name: "uncategorized", synthetic: true, qualifiedName: null))
            uncategorizedMatches.each { item ->
                bucket.add(new DefaultMutableTreeNode(new TagRow(
                        name: item.name, qualifiedName: item.qualifiedName,
                        path: new ArrayList<String>(item.path), colorHex: item.color,
                        uncategorized: true)))
            }
            newRoot.add(bucket)
        }
    }

    treeRootNode = newRoot
    ((DefaultTreeModel) tagTree.getModel()).setRoot(newRoot)

    int shown = countMatchingRows(newRoot)

    if (!firstBuildDone) {
        firstBuildDone = true
        collectAllCategoryQns(newRoot)
    }
    restoreExpansion(needle)
    
    // Restore previous selection if it exists
    if (oldSelectedQn != null) {
        DefaultMutableTreeNode node = findNodeByQn(treeRootNode, oldSelectedQn)
        if (node != null) {
            TreePath path = new TreePath(node.getPath())
            // Expand parent path
            TreePath parentPath = path.getParentPath()
            if (parentPath != null && parentPath.getPathCount() > 1) {
                tagTree.expandPath(parentPath)
            }
            tagTree.setSelectionPath(path)
            tagTree.scrollPathToVisible(path)
        } else {
            // If the previous tag no longer exists, select the first row
            if (!needle.isEmpty()) {
                int first = firstNavigableRow()
                if (first >= 0) {
                    tagTree.setSelectionRow(first)
                    tagTree.scrollRowToVisible(first)
                }
            } else {
                // If no filter, select the first visible row
                if (tagTree.getRowCount() > 0) {
                    tagTree.setSelectionRow(0)
                }
            }
        }
    } else {
        // If filter is active and no row is selected, select the first row
        if (!needle.isEmpty()) {
            int first = firstNavigableRow()
            if (first >= 0) {
                if (!selectionFromMap) {
                    tagTree.setSelectionRow(first)
                    tagTree.scrollRowToVisible(first)
                }
            }
        } else {
            // If no filter, select the first row (if no row is selected)
            if (tagTree.getSelectionCount() == 0 && tagTree.getRowCount() > 0) {
                tagTree.setSelectionRow(0)
            }
        }
    }

    int total = countTags(state)
    if (total == 0) {
        showStatus("No tags in this map yet — type and press Enter to create one")
    } else if (!needle.isEmpty()) {
        showStatus(shown + " of " + total + " tags match" +
                (isFilterHides() ? "" : " (highlighted, nothing hidden)") +
                (shown == 0 ? " — Enter creates '" + filterText + "'" : ""))
    } else if (showUsageCounts) {
        int unused = countUnusedTags(state)
        showStatus(total + " tags" + (unused > 0 ? " · " + unused + " unused" : "") +
                (hideUnusedTags ? " (unused hidden)" : "") +
                (isSortByUsage() ? " · by usage" : ""))
    } else {
        showStatus(total + " tags")
    }

    rebuildFavoritesStrip()
    updateAssignedMarks()
    fitPanelBounds()
}

DefaultMutableTreeNode buildCategoryNode(def cat, String needle) {
    boolean selfMatches = needle.isEmpty() || foldAccents(cat.qualifiedName.toLowerCase()).contains(needle)
    if (hideUnusedTags && categoryUsageOf(cat.qualifiedName) == 0) return null

    List<DefaultMutableTreeNode> children = []
    cat.children.each { child ->
        DefaultMutableTreeNode built = buildCategoryNode(child, selfMatches ? "" : needle)
        if (built != null) children.add(built)
    }

    if (!selfMatches && children.isEmpty()) return null

    DefaultMutableTreeNode node = new DefaultMutableTreeNode(new TagRow(
            name: cat.name, qualifiedName: cat.qualifiedName,
            path: new ArrayList<String>(cat.path), colorHex: cat.color))
    children.each { node.add(it) }
    return node
}

int countMatchingRows(DefaultMutableTreeNode node) {
    int count = 0
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        if (rowMatchesFilter((TagRow) child.getUserObject())) count++
        count += countMatchingRows(child)
    }
    return count
}

int countTags(def state) {
    int count = state.uncategorizedTags.size()
    List stack = new ArrayList(state.categories)
    while (!stack.isEmpty()) {
        def cat = stack.remove(stack.size() - 1)
        count++
        stack.addAll(cat.children)
    }
    return count
}

int countUnusedTags(def state) {
    int count = state.uncategorizedTags.count { categoryUsageOf(it.qualifiedName) == 0 }
    List stack = new ArrayList(state.categories)
    while (!stack.isEmpty()) {
        def cat = stack.remove(stack.size() - 1)
        if (categoryUsageOf(cat.qualifiedName) == 0) count++
        stack.addAll(cat.children)
    }
    return count
}

List<Map> unusedTagsToDelete(def state) {
    List<Map> found = []
    state.uncategorizedTags.each { item ->
        if (categoryUsageOf(item.qualifiedName) == 0) {
            found.add([path: new ArrayList<String>(item.path), qn: item.qualifiedName, uncategorized: true])
        }
    }
    List stack = new ArrayList(state.categories)
    while (!stack.isEmpty()) {
        def cat = stack.remove(stack.size() - 1)
        if (categoryUsageOf(cat.qualifiedName) == 0) {
            found.add([path: new ArrayList<String>(cat.path), qn: cat.qualifiedName, uncategorized: false])
        } else {
            stack.addAll(cat.children)
        }
    }
    return found
}

void collectAllCategoryQns(DefaultMutableTreeNode node) {
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (row.qualifiedName != null && child.getChildCount() > 0) expandedQns.add(row.qualifiedName)
        collectAllCategoryQns(child)
    }
}

void restoreExpansion(String needle) {
    if (!needle.isEmpty() && isFilterHides()) {
        int i = 0
        while (i < tagTree.getRowCount()) {
            tagTree.expandRow(i)
            i++
        }
        return
    }

    expandMatching(treeRootNode)
    if (!needle.isEmpty()) revealMatchAncestors(treeRootNode)
    for (int i = 0; i < treeRootNode.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRootNode.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (row.synthetic) tagTree.expandPath(new TreePath(child.getPath()))
    }
}

void revealMatchAncestors(DefaultMutableTreeNode node) {
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        if (rowMatchesFilter((TagRow) child.getUserObject())) {
            TreePath path = new TreePath(child.getPath())
            List<TreePath> chain = []
            TreePath ancestor = path.getParentPath()
            while (ancestor != null && ancestor.getPathCount() > 1) {
                chain.add(0, ancestor)
                ancestor = ancestor.getParentPath()
            }
            chain.each { tagTree.expandPath(it) }
        }
        revealMatchAncestors(child)
    }
}

void expandMatching(DefaultMutableTreeNode node) {
    for (int i = 0; i < node.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (row.qualifiedName != null && expandedQns.contains(row.qualifiedName)) {
            tagTree.expandPath(new TreePath(child.getPath()))
        }
        expandMatching(child)
    }
}

TagRow rowOf(TreePath path) {
    if (path == null) return null
    Object last = path.getLastPathComponent()
    if (!(last instanceof DefaultMutableTreeNode)) return null
    Object userObject = ((DefaultMutableTreeNode) last).getUserObject()
    return userObject instanceof TagRow ? (TagRow) userObject : null
}

TagRow selectedRow() {
    return rowOf(tagTree != null ? tagTree.getSelectionPath() : null)
}

DefaultMutableTreeNode findNodeByQn(DefaultMutableTreeNode from, String qn) {
    for (int i = 0; i < from.getChildCount(); i++) {
        DefaultMutableTreeNode child = (DefaultMutableTreeNode) from.getChildAt(i)
        TagRow row = (TagRow) child.getUserObject()
        if (qn.equals(row.qualifiedName)) return child
        DefaultMutableTreeNode found = findNodeByQn(child, qn)
        if (found != null) return found
    }
    return null
}

void selectRowByQn(String qn) {
    // Only if from Locate or from tree click
    // If selectionFromMap == true, do nothing
    if (selectionFromMap && !locateFromMap) {
        return
    }
    
    DefaultMutableTreeNode node = findNodeByQn(treeRootNode, qn)
    if (node == null) return
    TreePath path = new TreePath(node.getPath())
    tagTree.expandPath(path.getParentPath())
    tagTree.setSelectionPath(path)
    tagTree.scrollPathToVisible(path)
}

void moveTreeSelection(int delta) {
    applyFilterText()

    int rows = tagTree.getRowCount()
    if (rows == 0) return

    int step = delta > 0 ? 1 : -1
    int current = tagTree.getLeadSelectionRow()
    int candidate = current < 0 ? (delta > 0 ? 0 : rows - 1) : current + step

    for (int tried = 0; tried < rows; tried++) {
        int wrapped = ((candidate % rows) + rows) % rows
        if (isNavigableRow(wrapped)) {
            tagTree.setSelectionRow(wrapped)
            tagTree.scrollRowToVisible(wrapped)
            return
        }
        candidate = wrapped + step
    }
}
boolean isNavigableRow(int rowIndex) {
    return rowMatchesFilter(rowOf(tagTree.getPathForRow(rowIndex)))
}

boolean rowMatchesFilter(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return false
    String needle = foldAccents(filterText.toLowerCase())
    if (needle.isEmpty()) return true
    return foldAccents(row.qualifiedName.toLowerCase()).contains(needle)
}

boolean isFilterHides() {
    try {
        return ResourceController.getResourceController().getBooleanProperty(FILTER_HIDES_KEY, true)
    } catch (Throwable t) {
        return true
    }
}

void applyFilterHides(boolean hides) {
    try {
        ResourceController.getResourceController().setProperty(FILTER_HIDES_KEY, hides)
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
    if (filterModeButton != null) {
        filterModeButton.setText(hides ? filterHidesSymbol : highlightOnlySymbol)
        filterModeButton.setToolTipText(filterModeTooltip())
    }
    refreshTree()
}

String filterModeTooltip() {
    return isFilterHides()
            ? "Filtering: tags that do not match are hidden. Click to only highlight instead."
            : "Highlighting only: every tag stays visible. Click to hide what does not match."
}

List<int[]> matchRangesIn(String text, String foldedNeedle) {
    List<int[]> ranges = []
    if (foldedNeedle.isEmpty()) return ranges
    String haystack = foldAccents(text.toLowerCase())
    int at = haystack.indexOf(foldedNeedle)
    while (at >= 0) {
        ranges.add([at, at + foldedNeedle.length()] as int[])
        at = haystack.indexOf(foldedNeedle, at + foldedNeedle.length())
    }
    return ranges
}

String highlightedFragment(String text) {
    String needle = foldAccents(filterText.toLowerCase())
    if (needle.isEmpty()) return null
    List<int[]> ranges = matchRangesIn(text, needle)
    if (ranges.isEmpty()) return null

    StringBuilder html = new StringBuilder()
    int cursor = 0
    ranges.each { int[] range ->
        html.append(HtmlUtils.toXMLEscapedText(text.substring(cursor, range[0])))
        html.append('<span style="background-color:').append(matchHighlightHex).append('; color:#000000;">')
        html.append(HtmlUtils.toXMLEscapedText(text.substring(range[0], range[1])))
        html.append('</span>')
        cursor = range[1]
    }
    html.append(HtmlUtils.toXMLEscapedText(text.substring(cursor)))
    return html.toString()
}

int firstNavigableRow() {
    for (int i = 0; i < tagTree.getRowCount(); i++) {
        if (isNavigableRow(i)) return i
    }
    return -1
}

TreeCellRenderer createTagRenderer() {
    JLabel label = new JLabel()
    label.setOpaque(true)
    return new TreeCellRenderer() {
        @Override
        Component getTreeCellRendererComponent(JTree tree, Object value, boolean isSelected,
                boolean expanded, boolean leaf, int rowIndex, boolean hasFocus) {
            TagRow row = (value instanceof DefaultMutableTreeNode
                    && ((DefaultMutableTreeNode) value).getUserObject() instanceof TagRow)
                    ? (TagRow) ((DefaultMutableTreeNode) value).getUserObject() : null

            // Simplify: use a single JLabel for the entire row
            JLabel tagLabel = new JLabel()
            tagLabel.setOpaque(true)
            tagLabel.setFont(itemFont())
            tagLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))

            Color bgColor = mapBackground()

            if (row == null || row.synthetic) {
                tagLabel.setOpaque(false)
                tagLabel.setText(row == null ? String.valueOf(value) : row.name)
                tagLabel.setToolTipText(null)
                tagLabel.setForeground(UITools.getTextColorForBackground(mapBackground()))
                tagLabel.setFont(itemFont().deriveFont(Font.ITALIC, (float) (panelTextFontSize - 2)))
                tagLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6))
                return tagLabel
            }

            boolean hasChildren = value instanceof DefaultMutableTreeNode &&
                    ((DefaultMutableTreeNode) value).getChildCount() > 0
            boolean unused = isTagUnused(row.qualifiedName)

            Color chip = chipColor(row)
            if (unused && showUsageCounts) chip = blendColors(chip, mapBackground(), unusedTagFadeRatio)

            String marker = assignedAll.contains(row.qualifiedName) ? markAll + " "
                    : assignedSome.contains(row.qualifiedName) ? markSome + " " : ""
            String star = isFavorite(row.qualifiedName) ? favoriteSymbol : ""
            
            tagLabel.setBackground(chip)
            
            Color textColor = UITools.getTextColorForBackground(chip)
            if (isSelected) {
                float brightness = (chip.getRed() * 0.299f + chip.getGreen() * 0.587f + chip.getBlue() * 0.114f) / 255f
                textColor = brightness > 0.5f ? Color.BLACK : Color.WHITE
            }
            tagLabel.setForeground(textColor)
            
            String prefix = star + marker
            String suffix = usageSuffix(row, hasChildren)
            String highlighted = highlightedFragment(row.name)
            if (highlighted == null) {
                tagLabel.setText(prefix + row.name + suffix)
            } else {
                tagLabel.setText("<html>" + HtmlUtils.toXMLEscapedText(prefix) + highlighted
                        + HtmlUtils.toXMLEscapedText(suffix) + "</html>")
            }
            tagLabel.setToolTipText(usageTooltip(row, hasChildren))

            boolean armed = row.qualifiedName != null && row.qualifiedName.equals(armedDeleteQn)
            
            // Simplify: use a single Border for the entire row
            if (isSelected) {
                Color selectionBorder = new Color(0, 180, 0, 200)
                tagLabel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(selectionBorder, 3),
                        BorderFactory.createEmptyBorder(2, 8, 2, 8)))
                tagLabel.setBackground(blendColors(chip, new Color(0, 255, 0), 0.08f))
            } else if (armed) {
                tagLabel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color.RED, 2),
                        BorderFactory.createEmptyBorder(1, 8, 1, 8)))
            } else {
                tagLabel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(chip, 2),
                        BorderFactory.createEmptyBorder(1, 8, 1, 8)))
            }
            
            return tagLabel
        }
    }
}

Color chipColor(TagRow row) {
    Color raw = parseTagColor(row.colorHex, row.qualifiedName ?: row.name)
    if (raw.getAlpha() == 255) return raw
    return blendColors(mapBackground(), new Color(raw.getRed(), raw.getGreen(), raw.getBlue()),
            raw.getAlpha() / 255f)
}

Color parseTagColor(String hex, String content) {
    if (hex == null || hex.length() < 7) return new Tag(content ?: "").getColor()
    try {
        int r = Integer.parseInt(hex.substring(1, 3), 16)
        int g = Integer.parseInt(hex.substring(3, 5), 16)
        int b = Integer.parseInt(hex.substring(5, 7), 16)
        int a = hex.length() >= 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255
        return new Color(r, g, b, a)
    } catch (Throwable t) {
        return new Tag(content ?: "").getColor()
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Tree model / rendering ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Assigning (the Edit-Tags role) ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

List<NodeModel> selectedMapNodes() {
    def selection = boundMapView.getMapSelection()
    return selection == null ? [] : new ArrayList<NodeModel>(selection.getSelection())
}

List<String> tagsOf(NodeModel nodeModel) {
    return ProxyFactory.createNode(nodeModel, null).getTags().getTags()
}

// Add to Assigning section
void updateTreeSelectionFromMap() {
    if (tagTree == null || treeRootNode == null) return
    
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        // If no node is selected, clear tree selection
        tagTree.clearSelection()
    return
}

    // Collect all tags from selected nodes
    Set<String> allTags = new HashSet<String>()
    selected.each { nodeModel ->
        allTags.addAll(tagsOf(nodeModel))
    }
    
    if (allTags.isEmpty()) {
        tagTree.clearSelection()
        return
    }
    
    // Select the first tag (priority to tags with higher priority)
    String firstTag = allTags.first()
    
    // Find and select the tag in the tree
    DefaultMutableTreeNode node = findNodeByQn(treeRootNode, firstTag)
    if (node != null) {
        TreePath path = new TreePath(node.getPath())
        // Unfold the path
        TreePath parentPath = path.getParentPath()
        while (parentPath != null && parentPath.getPathCount() > 1) {
            tagTree.expandPath(parentPath)
            parentPath = parentPath.getParentPath()
        }
        tagTree.setSelectionPath(path)
        tagTree.scrollPathToVisible(path)
    } else {
        // If the tag is not in the tree (may be filtered),
        // try to find another tag
        for (String tag : allTags) {
            node = findNodeByQn(treeRootNode, tag)
            if (node != null) {
                TreePath path = new TreePath(node.getPath())
                TreePath parentPath = path.getParentPath()
                while (parentPath != null && parentPath.getPathCount() > 1) {
                    tagTree.expandPath(parentPath)
                    parentPath = parentPath.getParentPath()
                }
                tagTree.setSelectionPath(path)
                tagTree.scrollPathToVisible(path)
                break
            }
        }
    }
}

void updateAssignedMarks() {
    if (tagTree == null) return

    Set<String> markedBefore = new HashSet<String>(assignedAll)
    markedBefore.addAll(assignedSome)

    assignedAll.clear()
    assignedSome.clear()
    List<NodeModel> selected = selectedMapNodes()
    if (!selected.isEmpty()) {
        List<Set<String>> perNode = selected.collect { new HashSet<String>(tagsOf(it)) }
        Set<String> union = new HashSet<String>()
        perNode.each { union.addAll(it) }
        union.each { qn ->
            if (perNode.every { it.contains(qn) }) assignedAll.add(qn)
            else assignedSome.add(qn)
        }
    }

    Set<String> markedNow = new HashSet<String>(assignedAll)
    markedNow.addAll(assignedSome)
    Set<String> changed = new HashSet<String>(markedBefore)
    changed.removeAll(markedNow)
    Set<String> appeared = new HashSet<String>(markedNow)
    appeared.removeAll(markedBefore)
    changed.addAll(appeared)
    remeasureRows(changed)

    tagTree.repaint()

    if (favoritesStrip != null) {
        favoritesStrip.components.each { if (it instanceof JLabel) applyChipText((JLabel) it) }
        favoritesStrip.repaint()
    }
    
    // Remove call to updateTreeSelectionFromMap
    // Node selection in the map no longer affects tree selection
}

void toggleTagOnSelection(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return
    toggleTagQn(row.qualifiedName)
}

void toggleTagQn(String qn) {
    if (qn == null || qn.isEmpty()) return
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        showStatus("No node selected in the map")
        return
    }
    boolean allHave = selected.every { tagsOf(it).contains(qn) }
    int touched = 0
    selected.each { nodeModel ->
        def tags = ProxyFactory.createNode(nodeModel, null).getTags()
        if (allHave) {
            if (tags.remove(qn)) touched++
        } else if (!tags.getTags().contains(qn)) {
            tags.add(qn)
            touched++
        }
    }
    showStatus((allHave ? "Removed '" : "Assigned '") + qn + (allHave ? "' from " : "' to ")
            + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
    if (!allHave && rowByQn(qn) == null) scheduleRefresh()
    if (!allHave) maybeCloseAfterInsert(touched)
}

void assignTagToSelection(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return
    assignTagQn(row.qualifiedName)
}

void assignTagQn(String qn) {
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        showStatus("No node selected in the map")
        return
    }
    int touched = 0
    selected.each { nodeModel ->
        def tags = ProxyFactory.createNode(nodeModel, null).getTags()
        if (!tags.getTags().contains(qn)) {
            tags.add(qn)
            touched++
        }
    }
    showStatus("Assigned '" + qn + "' to " + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
    if (rowByQn(qn) == null) scheduleRefresh()
    maybeCloseAfterInsert(touched)
}

void removeTagFromSelection(TagRow row) {
    if (row == null || row.synthetic || row.qualifiedName == null) return
    removeTagQn(row.qualifiedName)
}

void removeTagQn(String qn) {
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        showStatus("No node selected in the map")
        return
    }
    int touched = 0
    selected.each { nodeModel ->
        if (ProxyFactory.createNode(nodeModel, null).getTags().remove(qn)) touched++
    }
    showStatus("Removed '" + qn + "' from " + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
}

void commitFieldAction(boolean forceCreate) {
    applyFilterText()

    String text = filterField.getText().trim()
    if (text.isEmpty()) return

    TagRow target = forceCreate ? null : bestMatchRow()
    filterField.setText("")

    if (target != null) {
        assignTagToSelection(target)
        return
    }
    createAndAssignTag(text)
}

TagRow bestMatchRow() {
    TagRow selected = selectedRow()
    if (rowMatchesFilter(selected)) return selected

    int first = firstNavigableRow()
    if (first >= 0) return rowOf(tagTree.getPathForRow(first))

    for (int i = 0; i < tagTree.getRowCount(); i++) {
        TagRow row = rowOf(tagTree.getPathForRow(i))
        if (row != null && !row.synthetic) return row
    }
    return null
}

void revealAncestorsOf(String qualifiedText) {
    List<String> segments = qualifiedText.split(java.util.regex.Pattern.quote(separator())) as List<String>
    for (int i = 1; i < segments.size(); i++) {
        expandedQns.add(segments.subList(0, i).join(separator()))
    }
}

void createAndAssignTag(String qualifiedText) {
    revealAncestorsOf(qualifiedText)
    List<NodeModel> selected = selectedMapNodes()
    if (selected.isEmpty()) {
        if (createMissingSegments(qualifiedText, true)) {
            showStatus("Created '" + qualifiedText + "' (no node selected, nothing assigned)")
        } else {
            showStatus("'" + qualifiedText + "' already exists (no node selected, nothing assigned)")
        }
        scheduleRefresh()
        return
    }

    createMissingSegments(qualifiedText, false)

    int touched = 0
    selected.each { nodeModel ->
        def tags = ProxyFactory.createNode(nodeModel, null).getTags()
        if (!tags.getTags().contains(qualifiedText)) {
            tags.add(qualifiedText)
            touched++
        }
    }
    showStatus("Created and assigned '" + qualifiedText + "' to " + touched + " node" + (touched == 1 ? "" : "s"))
    updateAssignedMarks()
    scheduleRefresh()
    maybeCloseAfterInsert(touched)
}

void loadPanelPreferences() {
    try {
        showUsageCounts = ResourceController.getResourceController()
                .getBooleanProperty(SHOW_USAGE_COUNTS_KEY, showUsageCounts)
    } catch (Throwable t) {
    }
}

boolean isCloseAfterInsert() {
    try {
        return ResourceController.getResourceController()
                .getBooleanProperty(CLOSE_AFTER_INSERT_KEY, closeAfterInsertDefault)
    } catch (Throwable t) {
        return closeAfterInsertDefault
    }
}

void applyCloseAfterInsert(boolean enabled) {
    try {
        ResourceController.getResourceController().setProperty(CLOSE_AFTER_INSERT_KEY, enabled)
        showStatus(enabled ? "The panel will close as soon as a tag is assigned"
                           : "The panel stays open after assigning")
    } catch (Throwable t) {
        showStatus("Could not save the option: " + t.getMessage())
    }
}

void maybeCloseAfterInsert(int assignedCount) {
    if (assignedCount <= 0 || tagPanel == null) return
    if (!isCloseAfterInsert()) return
    closePanel()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Assigning (the Edit-Tags role) ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Colour policy (#2950) ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

String newTagColorMode() {
    try {
        return ResourceController.getResourceController()
                .getProperty(NEW_TAG_COLOR_MODE_KEY, newTagColorModeDefault)
    } catch (Throwable t) {
        return newTagColorModeDefault
    }
}

String chosenFixedColor() {
    try {
        return ResourceController.getResourceController().getProperty(NEW_TAG_COLOR_KEY, null)
    } catch (Throwable t) {
        return null
    }
}

Map<String, String> colorByQualifiedName(def state) {
    Map<String, String> colors = new HashMap<String, String>()
    def walk
    walk = { cat ->
        colors.put(cat.qualifiedName, cat.color)
        cat.children.each { walk(it) }
    }
    state.categories.each { walk(it) }
    state.uncategorizedTags.each { colors.put(it.qualifiedName, it.color) }
    return colors
}

List<String> colorsForNewPath(List<String> path, Map<String, String> existingColors) {
    String mode = newTagColorMode()

    if (mode == "fixed") {
        String fixed = chosenFixedColor() ?: newTagColorFallback
        return path.collect { fixed }
    }
    if (mode != "inherit") {
        return path.collect { (String) null }
    }

    String separator = separator()
    String inherited = null
    List<String> colors = new ArrayList<String>()
    StringBuilder qualified = new StringBuilder()
    for (int i = 0; i < path.size(); i++) {
        if (i > 0) qualified.append(separator)
        qualified.append(path.get(i))
        String existing = existingColors.get(qualified.toString())
        if (existing != null) inherited = existing
        colors.add(existing != null ? existing : (inherited ?: chosenFixedColor()))
    }
    return colors
}

boolean createMissingSegments(String qualifiedText, boolean evenInDefaultMode) {
    if (!evenInDefaultMode && newTagColorMode() == "default") return false

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        return false
    }

    Map<String, String> existing = colorByQualifiedName(state)
    List<String> path = qualifiedText.split(java.util.regex.Pattern.quote(separator())) as List<String>
    List<String> colors = colorsForNewPath(path, existing)

    String separator = separator()
    List instructions = []
    StringBuilder qualified = new StringBuilder()
    for (int i = 0; i < path.size(); i++) {
        if (i > 0) qualified.append(separator)
        qualified.append(path.get(i))
        if (existing.containsKey(qualified.toString())) continue
        instructions.add(new MapTagCategoryInstruction(MapTagCategoryInstructionType.ADD_TAG,
                new ArrayList<String>(path.subList(0, i + 1)), null, null,
                MapTagTargetLocation.CATEGORIZED, null, colors.get(i), null))
    }
    if (instructions.isEmpty()) return false

    try {
        def categories = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.tagCategories
        categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, instructions))
        return true
    } catch (Throwable t) {
        showStatus("Could not create '" + qualifiedText + "': " + t.getMessage())
        return false
    }
}

String hexOf(Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue())
}

List<List<String>> branchPaths(def state, List<String> rootPath) {
    List<List<String>> paths = [new ArrayList<String>(rootPath)]
    List pending = new ArrayList(childrenAt(state, rootPath))
    while (!pending.isEmpty()) {
        def category = pending.remove(pending.size() - 1)
        paths.add(new ArrayList<String>(category.path))
        pending.addAll(category.children)
    }
    return paths
}

boolean hasChildrenInMap(TagRow row) {
    if (row == null || row.synthetic || row.uncategorized || row.path == null) return false
    try {
        return !childrenAt(readState(), row.path).isEmpty()
    } catch (Throwable t) {
        return false
    }
}

void chooseBranchColor(TagRow row) {
    Color chosen = JColorChooser.showDialog(tagPanel,
            "Color of '" + row.qualifiedName + "' and its sub-tags", chipColor(row))
    if (chosen == null) return
    applyBranchColor(row, hexOf(chosen))
}

void applyBranchColor(TagRow row, String colorSpec) {
    if (row == null || row.path == null || colorSpec == null) return

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }

    List<List<String>> paths = branchPaths(state, row.path)
    if (paths.size() <= 1) {
        applyTagColor(row, colorSpec)
        return
    }

    try {
        def categories = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap.tagCategories
        List instructions = paths.collect { List<String> path ->
            new MapTagCategoryInstruction(MapTagCategoryInstructionType.SET_COLOR, path, null, null,
                    MapTagTargetLocation.CATEGORIZED, null, colorSpec, null)
        }
        categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, instructions))
        showStatus("Colored " + paths.size() + " tags under '" + row.qualifiedName + "' — one Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Color change failed: " + t.getMessage())
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Colour policy (#2950) ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Structure edits (the Manage-Categories role) ↓↓↓↓↓↓↓↓
*/

def runInstruction(MapTagCategoryInstructionType type, Map args) {
    def mindMap = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap
    def categories = mindMap.tagCategories
    def instruction = new MapTagCategoryInstruction(type,
            (List<String>) args.path, (String) args.newName, (List<String>) args.newParentPath,
            (MapTagTargetLocation) args.targetLocation, (Integer) args.index,
            (String) args.color, null)
    categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, [instruction]))
}

void moveSelectedTag(String direction) {
    TagRow row = selectedRow()
    if (row == null || row.synthetic) {
        showStatus("Select a tag first")
        return
    }
    if (isSortByUsage()) {
        showStatus("Reordering is off while sorting by usage — switch back to the tree order")
        return
    }
    if (row.uncategorized) {
        showStatus("Uncategorized tags are sorted alphabetically — no manual order")
        return
    }

    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }

    List<String> path = row.path
    List<String> parentPath = path.size() > 1 ? path.subList(0, path.size() - 1) as List<String> : []
    List siblings = childrenAt(state, parentPath)
    int index = siblings.findIndexOf { it.name == row.name }
    if (index < 0) {
        showStatus("Tag not found (changed underneath?) — refreshing")
        scheduleRefresh()
        return
    }

    List<String> newParentPath = null
    Integer newIndex = null
    switch (direction) {
        case 'up':
            if (index <= 0) { showStatus("Already first"); return }
            newParentPath = parentPath; newIndex = index - 1
            break
        case 'down':
            if (index >= siblings.size() - 1) { showStatus("Already last"); return }
            newParentPath = parentPath; newIndex = index + 2
            break
        case 'demote':
            if (index <= 0) { showStatus("No previous sibling to move under"); return }
            newParentPath = new ArrayList<String>(parentPath); newParentPath.add(siblings[index - 1].name)
            newIndex = null
            break
        case 'promote':
            if (path.size() < 2) { showStatus("Already at the top level"); return }
            List<String> grandParentPath = path.size() > 2 ? path.subList(0, path.size() - 2) as List<String> : []
            List parentSiblings = childrenAt(state, grandParentPath)
            int parentIndex = parentSiblings.findIndexOf { it.name == parentPath[parentPath.size() - 1] }
            newParentPath = grandParentPath; newIndex = parentIndex + 1
            break
        default:
            return
    }

    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: path, newParentPath: newParentPath, targetLocation: MapTagTargetLocation.CATEGORIZED,
                 index: newIndex])
        String newQn = (newParentPath.isEmpty() ? "" : newParentPath.join(separator()) + separator()) + row.name
        expandedQns.addAll(newParentPath.isEmpty() ? [] : [newParentPath.join(separator())])
        remapFavorites(row.qualifiedName, newQn)
        showStatus("Moved '" + row.name + "' (" + direction + ") — Ctrl+Z undoes")
        refreshTree()
        selectRowByQn(newQn)
    } catch (Throwable t) {
        showStatus("Move failed: " + t.getMessage())
    }
}

List childrenAt(def state, List<String> path) {
    List current = state.categories
    for (String segment : path) {
        def next = current.find { it.name == segment }
        if (next == null) return []
        current = next.children
    }
    return current
}

void startRename() {
    TagRow row = selectedRow()
    if (row == null || row.synthetic) {
        showStatus("Select a tag first")
        return
    }
    renamingRow = row
    tagTree.startEditingAtPath(tagTree.getSelectionPath())
    renameEditorField.selectAll()
    fitPanelBounds()
}

void commitRename() {
    TagRow row = (TagRow) renamingRow
    renamingRow = null
    if (row == null) return
    String newName = renameEditorField.getText().trim()
    if (newName.isEmpty() || newName == row.name) return
    if (newName.contains(separator())) {
        showStatus("The name of one level cannot contain '" + separator() + "'")
        return
    }
    try {
        runInstruction(MapTagCategoryInstructionType.RENAME_TAG, [path: row.path, newName: newName,
                targetLocation: row.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED])
        List<String> parentPath = row.path.size() > 1 ? row.path.subList(0, row.path.size() - 1) : []
        String newQn = (parentPath.isEmpty() ? "" : parentPath.join(separator()) + separator()) + newName
        remapFavorites(row.qualifiedName, newQn)
        showStatus("Renamed to '" + newName + "' — node tags follow; Ctrl+Z undoes")
        refreshTree(true)
        selectRowByQn(newQn)
    } catch (Throwable t) {
        showStatus("Rename failed: " + t.getMessage())
        scheduleRefresh()
    }
}

void addChildToSelected() {
    TagRow row = selectedRow()
    if (row == null || row.synthetic || row.uncategorized) {
        showStatus("Select a categorized tag first")
        return
    }
    addChildTag(row)
}

void addChildTag(TagRow parentRow) {
    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }
    List existing = childrenAt(state, parentRow.path)
    String base = "new tag"
    String name = base
    int suffix = 2
    while (existing.any { it.name == name }) {
        name = base + " " + suffix
        suffix++
    }
    List<String> newPath = new ArrayList<String>(parentRow.path)
    newPath.add(name)
    String newColor = colorsForNewPath(newPath, colorByQualifiedName(state)).last()
    try {
        runInstruction(MapTagCategoryInstructionType.ADD_TAG,
                [path: newPath, targetLocation: MapTagTargetLocation.CATEGORIZED, color: newColor])
        expandedQns.add(parentRow.qualifiedName)
        refreshTree()
        String newQn = parentRow.qualifiedName + separator() + name
        selectRowByQn(newQn)
        showStatus("Added '" + name + "' — type the name")
        startRename()
    } catch (Throwable t) {
        showStatus("Add failed: " + t.getMessage())
    }
}

void deleteTagNow(TagRow row) {
    if (row == null || row.synthetic) return
    armedDeleteQn = null
    try {
        runInstruction(MapTagCategoryInstructionType.DELETE_TAG, [path: row.path,
                targetLocation: row.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED])
        remapFavorites(row.qualifiedName, null)
        showStatus("Deleted '" + row.qualifiedName + "' — Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Delete failed: " + t.getMessage())
    }
}

void deleteSelectedTag() {
    TagRow row = selectedRow()
    if (row == null || row.synthetic) return

    long now = System.currentTimeMillis()
    if (row.qualifiedName.equals(armedDeleteQn) && now - armedDeleteAt < deleteArmMs) {
        deleteTagNow(row)
        return
    }
    armedDeleteQn = row.qualifiedName
    armedDeleteAt = now
    tagTree.repaint()
    showStatus("Press Delete again to delete '" + row.qualifiedName + "' (and its subtags) from the map")
}

void chooseTagColor(TagRow row) {
    Color initial = chipColor(row)
    Color chosen = JColorChooser.showDialog(tagPanel, "Color of '" + row.qualifiedName + "'", initial)
    if (chosen == null) return
    applyTagColor(row, String.format("#%02x%02x%02x", chosen.getRed(), chosen.getGreen(), chosen.getBlue()))
}

void applyTagColor(TagRow row, String colorSpec) {
    try {
        runInstruction(MapTagCategoryInstructionType.SET_COLOR, [path: row.path, color: colorSpec,
                targetLocation: row.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED])
        showStatus((colorSpec == "none" ? "Color reset for '" : "Color set for '") + row.qualifiedName + "'")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Color change failed: " + t.getMessage())
    }
}


void moveToUncategorized(TagRow row) {
    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: row.path, targetLocation: MapTagTargetLocation.UNCATEGORIZED])
        showStatus("Moved '" + row.qualifiedName + "' to uncategorized — Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Move failed: " + t.getMessage())
    }
}

void categorizeAtTopLevel(TagRow row) {
    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: row.path, newParentPath: [], targetLocation: MapTagTargetLocation.CATEGORIZED])
        showStatus("Moved '" + row.qualifiedName + "' to the top level — Ctrl+Z undoes")
        refreshTree()
        selectRowByQn(row.name)
    } catch (Throwable t) {
        showStatus("Move failed: " + t.getMessage())
    }
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Structure edits (the Manage-Categories role) ↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Drag & drop (always enabled) ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

TransferHandler createTreeDndHandler() {
    return new TransferHandler() {
        @Override
        int getSourceActions(JComponent component) {
            // Drag & drop is always available - no edit mode needed
            return TransferHandler.MOVE
        }

        @Override
        Transferable createTransferable(JComponent component) {
            TagRow row = selectedRow()
            if (row == null || row.synthetic) return null
            draggedRow = row
            return new Transferable() {
                @Override
                DataFlavor[] getTransferDataFlavors() { return [tagDndFlavor] as DataFlavor[] }

                @Override
                boolean isDataFlavorSupported(DataFlavor flavor) { return flavor.equals(tagDndFlavor) }

                @Override
                Object getTransferData(DataFlavor flavor) {
                    if (!flavor.equals(tagDndFlavor)) throw new UnsupportedFlavorException(flavor)
                    return row.qualifiedName
                }
            }
        }

        @Override
        boolean canImport(TransferHandler.TransferSupport support) {
            if (draggedRow == null) return false
            if (!support.isDataFlavorSupported(tagDndFlavor)) return false
            return dropPlanFrom(support) != null
        }

        @Override
        boolean importData(TransferHandler.TransferSupport support) {
            Map plan = dropPlanFrom(support)
            if (plan == null) return false
            SwingUtilities.invokeLater { performDropMove(plan) }
            return true
        }

        @Override
        void exportDone(JComponent source, Transferable data, int action) {
            draggedRow = null
        }
    }
}

Map dropPlanFrom(TransferHandler.TransferSupport support) {
    if (!support.isDrop()) return null
    JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation()
    TreePath path = location.getPath()
    if (path == null) return null
    TagRow pathRow = rowOf(path)
    TagRow parent = (pathRow == null || (pathRow.synthetic && pathRow.name == "tags")) ? null : pathRow
    int childIndex = location.getChildIndex()
    return planDropMove((TagRow) draggedRow, parent, childIndex < 0 ? null : childIndex)
}

Map planDropMove(TagRow dragged, TagRow parentRow, Integer childIndex) {
    if (dragged == null || dragged.synthetic) return null
    if (!filterText.isEmpty()) return null
    if (isSortByUsage()) return null
    String sep = separator()

    boolean toUncategorized = parentRow != null &&
            (parentRow.uncategorized || (parentRow.synthetic && parentRow.name == "uncategorized"))
    if (toUncategorized) {
        if (dragged.uncategorized) return null
        DefaultMutableTreeNode draggedNode = findNodeByQn(treeRootNode, dragged.qualifiedName)
        if (draggedNode != null && draggedNode.getChildCount() > 0) return null
        return [path: dragged.path, newParentPath: null, targetLocation: MapTagTargetLocation.UNCATEGORIZED,
                index: null, newQn: dragged.name, expandQn: null]
    }

    if (parentRow != null && parentRow.synthetic) return null
    List<String> parentPath = parentRow == null ? [] : parentRow.path
    String parentQn = parentRow?.qualifiedName
    if (parentQn != null && !dragged.uncategorized) {
        if (parentQn == dragged.qualifiedName) return null
        if (parentQn.startsWith(dragged.qualifiedName + sep)) return null
    }

    String oldParentQn = dragged.uncategorized ? "::uncategorized::"
            : (dragged.path.size() > 1 ? dragged.path.subList(0, dragged.path.size() - 1).join(sep) : null)
    boolean sameParent = !dragged.uncategorized && oldParentQn == parentQn
    if (sameParent) {
        if (childIndex == null) return null
        DefaultMutableTreeNode parentNode = parentRow == null ? treeRootNode : findNodeByQn(treeRootNode, parentQn)
        int oldIndex = indexAmongTagChildren(parentNode, dragged.qualifiedName)
        if (oldIndex >= 0 && (childIndex == oldIndex || childIndex == oldIndex + 1)) return null
    }

    Integer index = childIndex
    if (index != null) {
        DefaultMutableTreeNode parentNode = parentRow == null ? treeRootNode : findNodeByQn(treeRootNode, parentQn)
        index = Math.max(0, Math.min(index, tagChildCount(parentNode)))
    }

    String newQn = (parentPath.isEmpty() ? "" : parentPath.join(sep) + sep) + dragged.name
    return [path: dragged.path, newParentPath: parentPath, targetLocation: MapTagTargetLocation.CATEGORIZED,
            index: index, newQn: newQn, expandQn: parentQn]
}

String performDropMove(Map plan) {
    String oldQn = ((List<String>) plan.path).join(separator())
    try {
        runInstruction(MapTagCategoryInstructionType.MOVE_TAG,
                [path: plan.path, newParentPath: plan.newParentPath,
                 targetLocation: plan.targetLocation, index: plan.index])
        if (plan.expandQn != null) expandedQns.add((String) plan.expandQn)
        remapFavorites(oldQn, (String) plan.newQn)
        String message = "Moved '" + plan.newQn + "' — Ctrl+Z undoes"
        showStatus(message)
        refreshTree()
        selectRowByQn((String) plan.newQn)
        return message
    } catch (Throwable t) {
        String message = "Move failed: " + t.getMessage()
        showStatus(message)
        return message
    }
}

void remeasureRows(Collection<String> qualifiedNames) {
    if (qualifiedNames.isEmpty() || tagTree == null) return
    DefaultTreeModel model = (DefaultTreeModel) tagTree.getModel()
    qualifiedNames.each { String qn ->
        DefaultMutableTreeNode node = findNodeByQn(treeRootNode, qn)
        if (node != null) model.nodeChanged(node)
    }
}

TagRow rowByQn(String qn) {
    DefaultMutableTreeNode node = findNodeByQn(treeRootNode, qn)
    return node == null ? null : (TagRow) node.getUserObject()
}

TagRow uncategorizedHeaderRow() {
    for (int i = 0; i < treeRootNode.getChildCount(); i++) {
        TagRow row = (TagRow) ((DefaultMutableTreeNode) treeRootNode.getChildAt(i)).getUserObject()
        if (row.synthetic && row.name == "uncategorized") return row
    }
    return null
}

int tagChildCount(DefaultMutableTreeNode parentNode) {
    if (parentNode == null) return 0
    int count = 0
    for (int i = 0; i < parentNode.getChildCount(); i++) {
        TagRow row = (TagRow) ((DefaultMutableTreeNode) parentNode.getChildAt(i)).getUserObject()
        if (!row.synthetic) count++
    }
    return count
}

int indexAmongTagChildren(DefaultMutableTreeNode parentNode, String qn) {
    if (parentNode == null) return -1
    int index = 0
    for (int i = 0; i < parentNode.getChildCount(); i++) {
        TagRow row = (TagRow) ((DefaultMutableTreeNode) parentNode.getChildAt(i)).getUserObject()
        if (row.synthetic) continue
        if (qn.equals(row.qualifiedName)) return index
        index++
    }
    return -1
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Drag & drop (always enabled) ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Context menu ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void showContextMenu(MouseEvent e) {
    TreePath path = tagTree.getPathForLocation(e.getX(), e.getY())
    if (path == null) return
    tagTree.setSelectionPath(path)
    TagRow row = rowOf(path)
    if (row == null || row.synthetic) return

    JPopupMenu menu = new JPopupMenu()
    menu.add(menuItem("Assign to selected node(s)", { assignTagToSelection(row) }))
    menu.add(menuItem("Remove from selected node(s)", { removeTagFromSelection(row) }))
    menu.addSeparator()
    menu.add(isFavorite(row.qualifiedName)
            ? menuItem("Remove from favorites", { removeFavorite(row.qualifiedName) })
            : menuItem("Add to favorites  " + favoriteSymbol, { addFavorite(row.qualifiedName) }))
    menu.addSeparator()
    menu.add(menuItem("Rename  (F2)", { startRename() }))
    if (!row.uncategorized) {
        menu.add(menuItem("Add child tag  (Insert)", { addChildTag(row) }))
       
        menu.add(menuItem("⚡ Add Child Tag (Fast)", { 
         
                selectRowByQn(row.qualifiedName)
            
                openFastChildPanel()
        }))
    }
    menu.add(menuItem("Delete", { deleteTagNow(row) }))
    menu.addSeparator()
    
    // ===== فقط یک آیتم برای Merge/Assign =====
    menu.add(menuItem("🔀 Merge / Assign", { 
        openMergePanel()
    }))

    // ===== اضافه کردن گزینه Locate =====
    menu.add(menuItem("📍 Locate Tag", { 
        installClickLocator()
    }))
    
    menu.addSeparator()
    menu.add(menuItem("Set color…", { chooseTagColor(row) }))
    menu.add(menuItem("Reset color to default", { applyTagColor(row, "none") }))
    if (hasChildrenInMap(row)) {
        menu.add(menuItem("Set color for this and all sub-tags…", { chooseBranchColor(row) }))
        menu.add(menuItem("Recolor sub-tags to match this category",
                { applyBranchColor(row, row.colorHex) }))
    }
    if (!row.uncategorized) {
        menu.addSeparator()
        menu.add(menuItem("Move up  (Alt+↑)", { moveSelectedTag('up') }))
        menu.add(menuItem("Move down  (Alt+↓)", { moveSelectedTag('down') }))
        menu.add(menuItem("Promote  (Alt+←)", { moveSelectedTag('promote') }))
        menu.add(menuItem("Demote  (Alt+→)", { moveSelectedTag('demote') }))
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent()
        if (node.getChildCount() == 0) {
            menu.add(menuItem("Move to uncategorized", { moveToUncategorized(row) }))
        }
    } else {
        menu.addSeparator()
        menu.add(menuItem("Categorize at the top level", { categorizeAtTopLevel(row) }))
    }
    menu.addSeparator()
    menu.add(menuItem("Filter map by this tag", { filterMapByTag(row) }))
    if (mapFilterActive) {
        menu.add(menuItem("Clear the map filter", { clearMapFilter(true) }))
    }
    addUsageMenuItems(menu)
    addPanelOptionItems(menu)

    attachPopupGuard(menu)
    menu.show(tagTree, e.getX(), e.getY())
}

void attachPopupGuard(JPopupMenu menu) {
    menu.addPopupMenuListener([
            popupMenuWillBecomeVisible  : { PopupMenuEvent ev -> popupOpen = true },
            popupMenuWillBecomeInvisible: { PopupMenuEvent ev -> popupOpen = false; retractTimer.restart() },
            popupMenuCanceled           : { PopupMenuEvent ev -> popupOpen = false }
    ] as PopupMenuListener)
}

void addPanelOptionItems(JPopupMenu menu) {
    menu.addSeparator()
    JCheckBoxMenuItem closeItem = new JCheckBoxMenuItem("Close after insert", isCloseAfterInsert())
    closeItem.setToolTipText("Hide the panel as soon as a tag is assigned — trigger, type, Enter, back to the map")
    closeItem.addActionListener({ ActionEvent e ->
        applyCloseAfterInsert(closeItem.isSelected())
    } as ActionListener)
    menu.add(closeItem)
    menu.add(menuItem("Options…", { showOptionsDialog() }))
}

/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Options dialog ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void showOptionsDialog() {
    Object opened = boundScrollPane.getClientProperty(OPTIONS_DIALOG_KEY)
    if (opened instanceof JDialog && ((JDialog) opened).isDisplayable()) {
        ((JDialog) opened).toFront()
        return
    }

    JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(tagPanel),
            "Tag panel options", Dialog.ModalityType.MODELESS)
    dialog.setName(OPTIONS_DIALOG_KEY)

    JPanel content = new JPanel()
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS))
    content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12))

    JLabel parentPreview = previewChip("parent")
    JLabel childPreview = previewChip("child")
    JButton colorButton = new JButton("      ")
    colorButton.setName("UnifiedTagPanelColorButton")

    JRadioButton defaultMode = new JRadioButton("Freeplane default (color from the name)")
    JRadioButton inheritMode = new JRadioButton("Inherit from the parent category")
    JRadioButton fixedMode = new JRadioButton("Fixed color")
    defaultMode.setName("UnifiedTagPanelModeDefault")
    inheritMode.setName("UnifiedTagPanelModeInherit")
    fixedMode.setName("UnifiedTagPanelModeFixed")
    ButtonGroup modes = new ButtonGroup()
    [defaultMode, inheritMode, fixedMode].each { modes.add(it) }

    Closure refreshOptionWidgets = { ->
        String mode = newTagColorMode()
        defaultMode.setSelected(mode == "default")
        inheritMode.setSelected(mode == "inherit")
        fixedMode.setSelected(mode == "fixed")
        colorButton.setEnabled(mode != "default")
        Color fixed = parseTagColor(chosenFixedColor() ?: newTagColorFallback, "")
        colorButton.setBackground(fixed)
        colorButton.setOpaque(true)
        colorButton.setBorderPainted(false)
        applyPreviewChips(parentPreview, childPreview)
    }

    Closure chooseMode = { String mode ->
        try {
            ResourceController.getResourceController().setProperty(NEW_TAG_COLOR_MODE_KEY, mode)
        } catch (Throwable t) {
            showStatus("Could not save the option: " + t.getMessage())
        }
        refreshOptionWidgets.call()
    }
    defaultMode.addActionListener({ ActionEvent e -> chooseMode.call("default") } as ActionListener)
    inheritMode.addActionListener({ ActionEvent e -> chooseMode.call("inherit") } as ActionListener)
    fixedMode.addActionListener({ ActionEvent e -> chooseMode.call("fixed") } as ActionListener)

    colorButton.addActionListener({ ActionEvent e ->
        Color chosen = JColorChooser.showDialog(dialog, "Color of new tags",
                parseTagColor(chosenFixedColor() ?: newTagColorFallback, ""))
        if (chosen == null) return
        try {
            ResourceController.getResourceController().setProperty(NEW_TAG_COLOR_KEY, hexOf(chosen))
        } catch (Throwable t) {
            showStatus("Could not save the color: " + t.getMessage())
        }
        refreshOptionWidgets.call()
    } as ActionListener)

    content.add(sectionLabel("New tags"))
    [defaultMode, inheritMode].each { content.add(leftAligned(it)) }
    JPanel fixedRow = transparentPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
    fixedRow.setOpaque(false)
    fixedRow.add(fixedMode)
    fixedRow.add(colorButton)
    content.add(leftAligned(fixedRow))
    JPanel previewRow = transparentPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
    previewRow.add(new JLabel("Preview:"))
    previewRow.add(parentPreview)
    previewRow.add(childPreview)
    content.add(leftAligned(previewRow))

    content.add(Box.createVerticalStrut(10))
    content.add(sectionLabel("Behaviour"))

    JCheckBox closeAfterInsertBox = new JCheckBox("Close after insert", isCloseAfterInsert())
    closeAfterInsertBox.setName("UnifiedTagPanelCloseAfterInsertBox")
    closeAfterInsertBox.addActionListener({ ActionEvent e ->
        applyCloseAfterInsert(closeAfterInsertBox.isSelected())
    } as ActionListener)
    content.add(leftAligned(closeAfterInsertBox))

    JCheckBox followTabsBox = new JCheckBox("Show on every tab", isFollowTabs())
    followTabsBox.setName("UnifiedTagPanelFollowTabsBox")
    followTabsBox.setToolTipText("The panel moves to whatever tab you switch to, instead of staying on the one it was opened in")
    followTabsBox.addActionListener({ ActionEvent e ->
        applyFollowTabs(followTabsBox.isSelected())
    } as ActionListener)
    content.add(leftAligned(followTabsBox))

    JCheckBox usageCountsBox = new JCheckBox("Show usage counts", showUsageCounts)
    usageCountsBox.setName("UnifiedTagPanelUsageCountsBox")
    usageCountsBox.addActionListener({ ActionEvent e ->
        showUsageCounts = usageCountsBox.isSelected()
        try {
            ResourceController.getResourceController().setProperty(SHOW_USAGE_COUNTS_KEY, showUsageCounts)
        } catch (Throwable t) {
            showStatus("Could not save the option: " + t.getMessage())
        }
        refreshTree()
    } as ActionListener)
    content.add(leftAligned(usageCountsBox))

    content.add(Box.createVerticalStrut(10))
    JLabel footnote = new JLabel("<html><i>Colors apply to tags created in this panel.<br>"
            + "Tags made elsewhere in Freeplane keep its own default.</i></html>")
    footnote.setFont(footnote.getFont().deriveFont((float) (panelTextFontSize - 3)))
    content.add(leftAligned(footnote))

    JButton closeButton = new JButton("Close")
    closeButton.addActionListener({ ActionEvent e -> dialog.dispose() } as ActionListener)
    JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0))
    buttonRow.add(closeButton)
    content.add(Box.createVerticalStrut(6))
    content.add(leftAligned(buttonRow))

    refreshOptionWidgets.call()

    dialog.getContentPane().add(content)
    dialog.pack()
    dialog.setLocationRelativeTo(tagPanel)
    dialog.addWindowListener(new WindowAdapter() {
        @Override
        void windowClosed(WindowEvent e) {
            boundScrollPane.putClientProperty(OPTIONS_DIALOG_KEY, null)
            refreshTree()
        }
    })
    boundScrollPane.putClientProperty(OPTIONS_DIALOG_KEY, dialog)
    dialog.setVisible(true)
}

// ============================================================
// Fast Child Panel (Add Child - Fast version)
// ============================================================

String getSelectedTagFromTree() {
    try {
        TreePath path = tagTree.getSelectionPath()
        if (path == null) return null
        
        def treeNode = path.lastPathComponent
        if (!(treeNode instanceof javax.swing.tree.DefaultMutableTreeNode)) return null
        
        def userObject = treeNode.userObject
        if (!(userObject instanceof TagRow)) return null
        
        return ((TagRow) userObject).qualifiedName
    } catch (Exception e) {
        return null
    }
}

void selectFastParentTag() {
    try {
        if (!fastChildPanelOpen || fastChildPanel == null || !fastChildPanel.isVisible()) return
        
        TreePath path = tagTree.getSelectionPath()
        if (path == null) return
        
        def treeNode = path.lastPathComponent
        if (!(treeNode instanceof javax.swing.tree.DefaultMutableTreeNode)) return
        
        def userObject = treeNode.userObject
        if (!(userObject instanceof TagRow)) return
        
        String fullPath = ((TagRow) userObject).qualifiedName
        if (fullPath == null) return
        
        SwingUtilities.invokeLater({
            fastParentField.setText(fullPath)
            fastParentField.setForeground(new Color(0, 120, 0))
            fastWaitingForParent = false
            fastStatusLabel.setText("✅ Parent selected: ${fullPath}")
            fastStatusLabel.setForeground(new Color(0, 120, 0))
            fastAddButton.setEnabled(true)
            fastChildNameField.requestFocusInWindow()
        })
        
    } catch (Exception e) {
        e.printStackTrace()
        fastWaitingForParent = false
    }
}

// Function to select tag from tree for Merge
void selectTagFromTree() {
    try {
        if (!isMergePanelOpen || mergePanel == null || !mergePanel.isVisible()) return
        
        TreePath path = tagTree.getSelectionPath()
        if (path == null) return
        
        def treeNode = path.lastPathComponent
        if (!(treeNode instanceof javax.swing.tree.DefaultMutableTreeNode)) return
        
        def userObject = treeNode.userObject
        if (!(userObject instanceof TagRow)) return
        
        String fullPath = ((TagRow) userObject).qualifiedName
        if (fullPath == null) return
        
        SwingUtilities.invokeLater({
            if (selectionStep == 1) {
                sourceField.setText(fullPath)
                sourceField.setForeground(new Color(0, 120, 0))
                selectionStep = 2
                statusLabel.setText("✅ Source: ${fullPath} | Now click on TARGET tag in tree")
                statusLabel.setForeground(new Color(0, 120, 0))
                mergeButton.setEnabled(false)
            } else if (selectionStep == 2) {
                String source = sourceField.getText()
                if (source == fullPath) {
                    statusLabel.setText("⚠️ Cannot use same tag as source and target")
                    statusLabel.setForeground(new Color(200, 100, 0))
                    return
                }
                targetField.setText(fullPath)
                targetField.setForeground(new Color(0, 120, 0))
                selectionStep = 1
                statusLabel.setText("✅ Source: ${source} | Target: ${fullPath} | Click MERGE")
                statusLabel.setForeground(new Color(0, 150, 0))
                mergeButton.setEnabled(true)
            }
        })
        
    } catch (Exception e) {
        e.printStackTrace()
    }
}

void openFastChildPanel() {
    try {
        def mindmapNode = Controller.currentController.getSelection().getSelected()
        if (mindmapNode == null) {
            JOptionPane.showMessageDialog(tagPanel,
                "Please select a node in the mindmap first.",
                "Add Child Tag (Fast)",
                JOptionPane.INFORMATION_MESSAGE)
            return
        }
        
        if (fastChildPanel != null && fastChildPanel.isVisible()) {
            fastChildPanel.toFront()
            return
        }
        
        fastChildPanelOpen = true
        fastWaitingForParent = false
        
        fastChildPanel = new JDialog(SwingUtilities.getWindowAncestor(tagPanel), "⚡ Add Child Tag (Fast)", false)
        fastChildPanel.setLayout(new BorderLayout(10, 10))
        fastChildPanel.setSize(550, 250)
        fastChildPanel.setLocationRelativeTo(tagPanel)
        fastChildPanel.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE)
        
        JPanel mainPanel = new JPanel(new GridBagLayout())
        GridBagConstraints gbc = new GridBagConstraints()
        gbc.insets = new Insets(8, 15, 8, 15)
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        JLabel helpLabel = new JLabel("💡 Click on a tag in the tree to select as PARENT")
        helpLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        helpLabel.setForeground(new Color(0, 100, 0))
        mainPanel.add(helpLabel, gbc)
        
        gbc.gridwidth = 1
        gbc.gridy = 1
        gbc.gridx = 0
        JLabel parentLabel = new JLabel("📁 Parent:")
        parentLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        mainPanel.add(parentLabel, gbc)
        
        gbc.gridx = 1
        gbc.gridwidth = 1
        fastParentField = new JTextField()
        fastParentField.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        fastParentField.setPreferredSize(new Dimension(350, 30))
        fastParentField.setEditable(false)
        fastParentField.setBackground(new Color(245, 245, 245))
        fastParentField.setForeground(new Color(100, 100, 100))
        fastParentField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ))
        fastParentField.setToolTipText("Click on a tag in the tree to fill this field")
        mainPanel.add(fastParentField, gbc)
        
        gbc.gridy = 2
        gbc.gridx = 0
        gbc.gridwidth = 1
        JLabel childLabel = new JLabel("✏️ Child Name:")
        childLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        mainPanel.add(childLabel, gbc)
        
        gbc.gridx = 1
        fastChildNameField = new JTextField()
        fastChildNameField.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        fastChildNameField.setPreferredSize(new Dimension(350, 30))
        fastChildNameField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ))
        fastChildNameField.setToolTipText("Enter the name for the child tag")
        fastChildNameField.addActionListener({ ActionEvent e ->
            if (fastAddButton.isEnabled()) {
                performFastAddChild()
            }
        } as ActionListener)
        mainPanel.add(fastChildNameField, gbc)
        
        gbc.gridy = 3
        gbc.gridx = 0
        gbc.gridwidth = 2
        fastStatusLabel = new JLabel(" 🔵 Click on a tag in the tree to select as PARENT")
        fastStatusLabel.setFont(new Font(panelTextFontName, Font.BOLD, 12))
        fastStatusLabel.setForeground(new Color(0, 0, 150))
        fastStatusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5))
        mainPanel.add(fastStatusLabel, gbc)
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5))
        
        fastAddButton = new JButton("⚡ Add & Remove")
        fastAddButton.setFont(new Font(panelTextFontName, Font.BOLD, 14))
        fastAddButton.setBackground(new Color(255, 200, 100))
        fastAddButton.setForeground(new Color(150, 80, 0))
        fastAddButton.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 120, 0), 2),
            BorderFactory.createEmptyBorder(8, 25, 8, 25)
        ))
        fastAddButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
        fastAddButton.setEnabled(false)
        fastAddButton.setToolTipText("Add child tag (auto-removes after 2s)")
        fastAddButton.addActionListener({ ActionEvent e ->
            performFastAddChild()
        } as ActionListener)
        buttonPanel.add(fastAddButton)
        
        JButton resetButton = new JButton("↺ Reset")
        resetButton.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        resetButton.addActionListener({ ActionEvent e ->
            fastParentField.setText("")
            fastParentField.setForeground(new Color(100, 100, 100))
            fastChildNameField.setText("")
            fastWaitingForParent = true
            fastStatusLabel.setText(" 🔵 Click on a tag in the tree to select as PARENT")
            fastStatusLabel.setForeground(new Color(0, 0, 150))
            fastAddButton.setEnabled(false)
            String currentTag = getSelectedTagFromTree()
            if (currentTag != null) {
                fastParentField.setText(currentTag)
                fastParentField.setForeground(new Color(0, 120, 0))
                fastWaitingForParent = false
                fastStatusLabel.setText("✅ Parent selected: ${currentTag}")
                fastStatusLabel.setForeground(new Color(0, 120, 0))
                fastAddButton.setEnabled(true)
                fastChildNameField.requestFocusInWindow()
            }
        } as ActionListener)
        buttonPanel.add(resetButton)
        
        JButton closeBtn = new JButton("✕ Close")
        closeBtn.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        closeBtn.addActionListener({ ActionEvent e ->
            fastChildPanel.dispose()
            fastChildPanelOpen = false
            fastWaitingForParent = false
        } as ActionListener)
        buttonPanel.add(closeBtn)
        
        fastChildPanel.add(mainPanel, BorderLayout.CENTER)
        fastChildPanel.add(buttonPanel, BorderLayout.SOUTH)
        
        fastChildPanel.addWindowListener(new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent e) {
                fastChildPanelOpen = false
                fastWaitingForParent = false
            }
        })
        
        fastChildPanel.setVisible(true)
        
        String currentTag = getSelectedTagFromTree()
        if (currentTag != null) {
            fastParentField.setText(currentTag)
            fastParentField.setForeground(new Color(0, 120, 0))
            fastWaitingForParent = false
            fastStatusLabel.setText("✅ Parent selected: ${currentTag}")
            fastStatusLabel.setForeground(new Color(0, 120, 0))
            fastAddButton.setEnabled(true)
            fastChildNameField.requestFocusInWindow()
        } else {
            fastWaitingForParent = true
            fastStatusLabel.setText(" 🔵 Please click on a tag in the tree to select as PARENT")
            fastStatusLabel.setForeground(new Color(0, 0, 150))
        }
        
    } catch (Exception e) {
        JOptionPane.showMessageDialog(tagPanel,
            "Error: ${e.message}",
            "Error",
            JOptionPane.ERROR_MESSAGE)
        e.printStackTrace()
        fastChildPanelOpen = false
        fastWaitingForParent = false
    }
}

void performFastAddChild() {
    try {
        String parentPath = fastParentField.getText()
        String childName = fastChildNameField.getText().trim()
        
        if (parentPath.isEmpty()) {
            JOptionPane.showMessageDialog(fastChildPanel,
                "Please select a parent tag from the tree.",
                "Error",
                JOptionPane.WARNING_MESSAGE)
            return
        }
        
        if (childName.isEmpty()) {
            JOptionPane.showMessageDialog(fastChildPanel,
                "Please enter a name for the child tag.",
                "Error",
                JOptionPane.WARNING_MESSAGE)
            return
        }
        
        def mindmapNode = Controller.currentController.getSelection().getSelected()
        if (mindmapNode == null) {
            JOptionPane.showMessageDialog(fastChildPanel,
                "No mindmap node selected.",
                "Error",
                JOptionPane.ERROR_MESSAGE)
            return
        }
        
        String fullTagName = "${parentPath}::${childName}"
        
        // Use IconController.getController()
        def iconController = IconController.getController()
        if (iconController == null) {
            JOptionPane.showMessageDialog(fastChildPanel,
                "Icon controller not available.",
                "Error",
                JOptionPane.ERROR_MESSAGE)
            return
        }
        
        def tag = new Tag(fullTagName, Color.ORANGE)
        iconController.addTags(mindmapNode, [tag])
        
        Timer timer = new Timer(fastRemoveDelayMs, { e ->
            try {
                def tags = new HashSet<Tag>()
                tags.add(tag)
                iconController.removeTags(mindmapNode, tags)
                println "Tag '${fullTagName}' removed from mindmap node."
                scheduleRefresh()
            }
            catch(Exception ex) {
                ex.printStackTrace()
            }
        } as java.awt.event.ActionListener)
        
        timer.setRepeats(false)
        timer.start()
        
        scheduleRefresh()
        
        fastParentField.setText("")
        fastParentField.setForeground(new Color(100, 100, 100))
        fastChildNameField.setText("")
        
        String currentTag = getSelectedTagFromTree()
        if (currentTag != null) {
            fastParentField.setText(currentTag)
            fastParentField.setForeground(new Color(0, 120, 0))
            fastWaitingForParent = false
            fastStatusLabel.setText("✅ Parent selected: ${currentTag}")
            fastStatusLabel.setForeground(new Color(0, 120, 0))
            fastAddButton.setEnabled(true)
            fastChildNameField.requestFocusInWindow()
        } else {
            fastWaitingForParent = true
            fastStatusLabel.setText(" 🔵 Click on a tag in the tree to select as PARENT")
            fastStatusLabel.setForeground(new Color(0, 0, 150))
            fastAddButton.setEnabled(false)
        }
        
    } catch (Exception ex) {
        ex.printStackTrace()
        JOptionPane.showMessageDialog(fastChildPanel,
            "Error: ${ex.getMessage()}",
            "Error",
            JOptionPane.ERROR_MESSAGE)
    }
}


// ============================================================
// Merge/Copy Panel
// ============================================================

void openMergePanel() {
    try {
        if (mergePanel != null && mergePanel.isVisible()) {
            mergePanel.toFront()
            return
        }
        
        isMergePanelOpen = true
        selectionStep = 1
        
        mergePanel = new JDialog(SwingUtilities.getWindowAncestor(tagPanel), "🔀 Merge OR Assign", false)
        mergePanel.setLayout(new BorderLayout(10, 10))
        mergePanel.setSize(600, 300)
        mergePanel.setLocationRelativeTo(tagPanel)
        mergePanel.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE)
        
        JPanel mainPanel = new JPanel(new GridBagLayout())
        GridBagConstraints gbc = new GridBagConstraints()
        gbc.insets = new Insets(8, 15, 8, 15)
        gbc.fill = GridBagConstraints.HORIZONTAL
        
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 3
        JLabel helpLabel = new JLabel("💡 Click 1st tag in tree → SOURCE | Click 2nd tag → TARGET")
        helpLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        helpLabel.setForeground(new Color(0, 100, 0))
        mainPanel.add(helpLabel, gbc)
        
        gbc.gridwidth = 1
        gbc.gridy = 1
        gbc.gridx = 0
        JLabel sourceLabel = new JLabel("🔽 Source:")
        sourceLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        mainPanel.add(sourceLabel, gbc)
        
        gbc.gridx = 1
        gbc.gridwidth = 2
        sourceField = new JTextField()
        sourceField.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        sourceField.setPreferredSize(new Dimension(350, 30))
        sourceField.setEditable(false)
        sourceField.setBackground(new Color(245, 245, 245))
        sourceField.setForeground(new Color(100, 100, 100))
        sourceField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ))
        mainPanel.add(sourceField, gbc)
        
        gbc.gridwidth = 1
        gbc.gridy = 2
        gbc.gridx = 0
        JLabel targetLabel = new JLabel("🔼 Target:")
        targetLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        mainPanel.add(targetLabel, gbc)
        
        gbc.gridx = 1
        gbc.gridwidth = 2
        targetField = new JTextField()
        targetField.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        targetField.setPreferredSize(new Dimension(350, 30))
        targetField.setEditable(false)
        targetField.setBackground(new Color(245, 245, 245))
        targetField.setForeground(new Color(100, 100, 100))
        targetField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ))
        mainPanel.add(targetField, gbc)
        
        gbc.gridwidth = 1
        gbc.gridy = 3
        gbc.gridx = 0
        JLabel modeLabel = new JLabel("⚙️ Mode:")
        modeLabel.setFont(new Font(panelTextFontName, Font.BOLD, 13))
        mainPanel.add(modeLabel, gbc)
        
        gbc.gridx = 1
        gbc.gridwidth = 1
        String[] modes = ["🔀 Merge (remove source)", "➕ Assign Tag Additionally (keep both)"]
        modeCombo = new JComboBox<String>(modes)
        modeCombo.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        modeCombo.setPreferredSize(new Dimension(200, 30))
        modeCombo.setToolTipText("Merge: replace source with target (remove source) | Assign Tag Additionally: add source to nodes that have target (keep both)")
        mainPanel.add(modeCombo, gbc)
        
        gbc.gridwidth = 3
        gbc.gridy = 4
        gbc.gridx = 0
        statusLabel = new JLabel(" 🔵 Click on a tag in the tree to select as SOURCE")
        statusLabel.setFont(new Font(panelTextFontName, Font.BOLD, 12))
        statusLabel.setForeground(new Color(0, 0, 150))
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5))
        mainPanel.add(statusLabel, gbc)
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5))
        
        mergeButton = new JButton("⚡ OK")
        mergeButton.setFont(new Font(panelTextFontName, Font.BOLD, 14))
        mergeButton.setBackground(new Color(220, 240, 220))
        mergeButton.setForeground(new Color(0, 120, 0))
        mergeButton.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0, 150, 0), 2),
            BorderFactory.createEmptyBorder(8, 25, 8, 25)
        ))
        mergeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
        mergeButton.setEnabled(false)
        mergeButton.addActionListener({ ActionEvent e ->
            performMerge()
        } as ActionListener)
        buttonPanel.add(mergeButton)
        
        JButton resetButton = new JButton("↺ Reset")
        resetButton.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        resetButton.addActionListener({ ActionEvent e ->
            sourceField.setText("")
            sourceField.setForeground(new Color(100, 100, 100))
            targetField.setText("")
            targetField.setForeground(new Color(100, 100, 100))
            selectionStep = 1
            statusLabel.setText(" 🔵 Click on a tag in the tree to select as SOURCE")
            statusLabel.setForeground(new Color(0, 0, 150))
            mergeButton.setEnabled(false)
        } as ActionListener)
        buttonPanel.add(resetButton)
        
        JButton closeBtn = new JButton("✕ Close")
        closeBtn.setFont(new Font(panelTextFontName, Font.PLAIN, 13))
        closeBtn.addActionListener({ ActionEvent e ->
            mergePanel.dispose()
            isMergePanelOpen = false
            selectionStep = 1
        } as ActionListener)
        buttonPanel.add(closeBtn)
        
        mergePanel.add(mainPanel, BorderLayout.CENTER)
        mergePanel.add(buttonPanel, BorderLayout.SOUTH)
        
        mergePanel.addWindowListener(new WindowAdapter() {
            @Override
            void windowClosing(WindowEvent e) {
                isMergePanelOpen = false
                selectionStep = 1
            }
        })
        
        mergePanel.setVisible(true)
        
    } catch (Exception e) {
        JOptionPane.showMessageDialog(tagPanel,
            "Error: ${e.message}",
            "Merge Error",
            JOptionPane.ERROR_MESSAGE)
        e.printStackTrace()
        isMergePanelOpen = false
        selectionStep = 1
    }
}

// Execute merge
void performMerge() {
    try {
        String sourceFull = sourceField.getText()
        String targetFull = targetField.getText()
        
        if (sourceFull.isEmpty() || targetFull.isEmpty()) {
            JOptionPane.showMessageDialog(mergePanel,
                "Please select both source and target tags.",
                "Error",
                JOptionPane.WARNING_MESSAGE)
            return
        }
        
        if (sourceFull == targetFull) {
            JOptionPane.showMessageDialog(mergePanel,
                "Source and target cannot be the same.",
                "Error",
                JOptionPane.WARNING_MESSAGE)
            return
        }
        
        String source = sourceFull
        String target = targetFull
        
        String mode = modeCombo.getSelectedItem()
        boolean isMerge = mode.contains("Merge")
        
        String actionName = isMerge ? "Merge" : "Assign"
        String actionIcon = isMerge ? "🔀" : "➕"
        
        int confirm = JOptionPane.showConfirmDialog(mergePanel,
            "${actionIcon} ${actionName} '${sourceFull}' into '${targetFull}'?\n\n" +
            "Source: ${sourceFull} ${isMerge ? '(will be REMOVED)' : '(will be KEPT)'}\n" +
            "Target: ${targetFull} (will be kept)\n\n" +
            "Operation: ${isMerge ? 'Replace source with target' : 'Add source tag (keep both)'}",
            "Confirm ${actionName}",
            JOptionPane.YES_NO_OPTION)
        
        if (confirm != JOptionPane.YES_OPTION) return
        
        // Use IconController.getController() instead of MIconController
        def iconController = IconController.getController()
        if (iconController == null) {
            JOptionPane.showMessageDialog(mergePanel,
                "Icon controller not available.",
                "Error",
                JOptionPane.ERROR_MESSAGE)
            return
        }
        
        def map = boundMapView.getMap()
        def root = map.getRootNode()

        int updated = 0
        
        if (isMerge) {
            updated = mergeNodes(root, iconController, source, target)
        } else {
            updated = assignNodesReverse(root, iconController, target, source)
        }

        JOptionPane.showMessageDialog(mergePanel,
            "✅ ${actionName} completed!\n\n" +
            "Source : ${sourceFull}\n" +
            "Target : ${targetFull}\n\n" +
            "Updated nodes : ${updated}",
            "${actionName} Tag",
            JOptionPane.INFORMATION_MESSAGE)

        if (tagTree != null) {
            tagTree.updateUI()
            refreshTree()
        }
        
        // Reset fields
        sourceField.setText("")
        sourceField.setForeground(new Color(100, 100, 100))
        targetField.setText("")
        targetField.setForeground(new Color(100, 100, 100))
        selectionStep = 1
        statusLabel.setText(" 🔵 Click on a tag in the tree to select as SOURCE")
        statusLabel.setForeground(new Color(0, 0, 150))
        mergeButton.setEnabled(false)

    } catch (Exception e) {
        e.printStackTrace()
        JOptionPane.showMessageDialog(mergePanel,
            "Error: ${e.message}",
            "Error",
            JOptionPane.ERROR_MESSAGE)
    }
}

// ============================================================
// Merge and Copy functions
// ============================================================

int mergeNodes(NodeModel node, IconController iconController, String source, String target) {
    int changed = 0
    def tags = iconController.getTags(node)

    if (tags != null) {
        def names = tags.collect{
            it instanceof Tag ? it.getContent() : it.toString()
        }
        if (names.contains(source)) {
            def removeTag = new Tag(source, null)
        
            if (!names.contains(target)) {
                iconController.addTagsFromSpec(node, target)
            }
        
            iconController.removeTags(node, [removeTag] as Set)
            changed++
        }
    }

    node.getChildren().each{
        changed += mergeNodes(it, iconController, source, target)
    }

    return changed
}

int assignNodesReverse(NodeModel node, IconController iconController, String target, String source) {
    int changed = 0
    def tags = iconController.getTags(node)

    if (tags != null) {
        def names = tags.collect {
            it instanceof Tag ? it.getContent() : it.toString()
        }

        if (names.contains(target)) {
            if (!names.contains(source)) {
                iconController.addTagsFromSpec(node, source)
                changed++
            }
        }
    }

    node.getChildren().each {
        changed += assignNodesReverse(it, iconController, target, source)
    }

    return changed
}


// ============================================================
// Merge / Assign from right-click menu
// ============================================================

JLabel sectionLabel(String text) {
    JLabel label = new JLabel(text)
    label.setFont(label.getFont().deriveFont(Font.BOLD))
    label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0))
    return label
}

JComponent leftAligned(JComponent component) {
    component.setAlignmentX(Component.LEFT_ALIGNMENT)
    return component
}

JLabel previewChip(String text) {
    JLabel chip = new JLabel(text)
    chip.setOpaque(true)
    chip.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6))
    return chip
}

void applyPreviewChips(JLabel parentChip, JLabel childChip) {
    Map<String, String> nothingExists = new HashMap<String, String>()
    List<String> parentColors = colorsForNewPath(["parent"], nothingExists)
    Color parentColor = parseTagColor(parentColors.get(0), "parent")

    Map<String, String> parentExists = new HashMap<String, String>()
    parentExists.put("parent", hexOf(parentColor))
    List<String> childColors = colorsForNewPath(["parent", "child"], parentExists)
    Color childColor = parseTagColor(childColors.get(1), "parent" + separator() + "child")

    parentChip.setBackground(parentColor)
    parentChip.setForeground(UITools.getTextColorForBackground(parentColor))
    childChip.setBackground(childColor)
    childChip.setForeground(UITools.getTextColorForBackground(childColor))
}

/*
 ============================================================================
 End of Options dialog
 ============================================================================
*/

void addUsageMenuItems(JPopupMenu menu) {
    if (!showUsageCounts) return
    def state
    try {
        state = readState()
    } catch (Throwable t) {
        return
    }
    int unused = countUnusedTags(state)

    menu.addSeparator()
    JCheckBoxMenuItem hideItem = new JCheckBoxMenuItem("Hide unused tags", hideUnusedTags)
    hideItem.addActionListener({ ActionEvent e ->
        hideUnusedTags = hideItem.isSelected()
        refreshTree()
    } as ActionListener)
    hideItem.setEnabled(unused > 0 || hideUnusedTags)
    menu.add(hideItem)

    JCheckBoxMenuItem sortItem = new JCheckBoxMenuItem("Sort by usage", isSortByUsage())
    sortItem.setToolTipText("Drop the category nesting and list every tag, most used first")
    sortItem.addActionListener({ ActionEvent e -> applySortByUsage(sortItem.isSelected()) } as ActionListener)
    menu.add(sortItem)

    JMenu deleteUnused = new JMenu("Delete all unused tags (" + unused + ")")
    deleteUnused.setEnabled(unused > 0)
    deleteUnused.add(menuItem("Confirm — no node uses them, and Ctrl+Z undoes",
            { deleteAllUnusedTags() }))
    menu.add(deleteUnused)
}

void deleteAllUnusedTags() {
    def state
    try {
        state = readState()
    } catch (Throwable t) {
        showStatus("Could not read tags: " + t.getMessage())
        return
    }
    List<Map> victims = unusedTagsToDelete(state)
    if (victims.isEmpty()) {
        showStatus("No unused tags")
        return
    }

    try {
        def mindMap = ProxyFactory.createNode(boundMapView.map.rootNode, null).mindMap
        def categories = mindMap.tagCategories
        List instructions = victims.collect { victim ->
            new MapTagCategoryInstruction(MapTagCategoryInstructionType.DELETE_TAG,
                    (List<String>) victim.path, null, null,
                    victim.uncategorized ? MapTagTargetLocation.UNCATEGORIZED : MapTagTargetLocation.CATEGORIZED,
                    null, null, null)
        }
        categories.edit(new MapTagCategoryInstructionRequest(categories.read().revision, instructions))
        victims.each { remapFavorites((String) it.qn, null) }
        showStatus("Deleted " + victims.size() + " unused tag" + (victims.size() == 1 ? "" : "s") + " — Ctrl+Z undoes")
        refreshTree()
    } catch (Throwable t) {
        showStatus("Delete failed: " + t.getMessage())
    }
}

JMenuItem menuItem(String text, Closure action) {
    JMenuItem item = new JMenuItem(text)
    item.addActionListener({ ActionEvent e -> action.call() } as ActionListener)
    return item
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Context menu ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Map filter by tag ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

void filterMapByTag(TagRow row) {
    String qn = row.qualifiedName
    String prefix = qn + separator()
    def rootProxy = ProxyFactory.createNode(boundMapView.map.rootNode, null)
    Set<NodeModel> matches = new HashSet<NodeModel>()
    rootProxy.find { n ->
        n.getTags().getTags().any { String t -> t == qn || t.startsWith(prefix) }
    }.each { matches.add((NodeModel) it.delegate) }

    if (matches.isEmpty()) {
        showStatus("No node carries '" + qn + "'")
        return
    }

    MapModel map = boundMapView.getMap()
    ICondition condition = { NodeModel n -> matches.contains(n) } as ICondition
    Filter filter = new Filter(condition, false, true, showTagFilterDescendants, false, null)
    FilterController.getCurrentFilterController().applyFilter(map, true, filter)
    unfoldAncestorsTracking(matches, filter)
    mapFilterActive = true
    showStatus("Map filtered: " + matches.size() + " node" + (matches.size() == 1 ? "" : "s")
            + " with '" + qn + "'")
}

void unfoldAncestorsTracking(Collection<NodeModel> matches, Filter filter) {
    def mapController = Controller.getCurrentModeController().getMapController()
    Set<NodeModel> visited = new HashSet<NodeModel>()
    for (NodeModel match : matches) {
        NodeModel ancestor = match.getParentNode()
        while (ancestor != null) {
            if (visited.add(ancestor) && ancestor.isFolded()) {
                nodesUnfoldedByFilter.add(ancestor)
                mapController.setFolded(ancestor, false, filter)
            }
            ancestor = ancestor.getParentNode()
        }
    }
}

void clearMapFilter(boolean announce) {
    if (!mapFilterActive && nodesUnfoldedByFilter.isEmpty()) return

    if (boundMapView != null) {
        MapModel map = boundMapView.getMap()
        Filter noFilter = new Filter(FilterController.NO_FILTERING, false, true, showTagFilterDescendants, false, null)
        FilterController.getCurrentFilterController().applyFilter(map, true, noFilter)
        restoreFolding()
    }
    mapFilterActive = false
    if (announce) showStatus("Map filter cleared; folding restored")
}

void restoreFolding() {
    if (nodesUnfoldedByFilter.isEmpty()) return
    def mapController = Controller.getCurrentModeController().getMapController()
    def selection = boundMapView.getMapSelection()
    Filter current = selection != null ? selection.getFilter() : null
    for (NodeModel node : nodesUnfoldedByFilter) {
        if (isNodeInMap(node)) mapController.setFolded(node, true, current)
    }
    nodesUnfoldedByFilter.clear()
}

boolean isNodeInMap(NodeModel node) {
    NodeModel top = node
    while (top.getParentNode() != null) top = top.getParentNode()
    return top.is(boundMapView.getMap().getRootNode())
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Map filter by tag ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Retract / expand ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

int viewportHeight() {
    return boundScrollPane.getViewport().getHeight()
}

int retractedWidth() {
    return (int) (boundScrollPane.getViewport().getWidth() / retractedWidthFactor)
}

int expandedWidth() {
    return retractedWidth() * expandedWidthFactor
}

int wideWidth() {
    return (int) (boundScrollPane.getViewport().getWidth() * wideWidthPercent / 100)
}

int fittedHeight(int panelWidth) {
    invalidatePreferredSizeCache()
    int preferred = (int) tagPanel.getPreferredSize().height

    if (horizontalScrollBarNeeded(panelWidth)) {
        preferred += (int) treeScrollPane.getHorizontalScrollBar().getPreferredSize().height
    }
    return Math.min(preferred, viewportHeight())
}

void invalidatePreferredSizeCache() {
    if (tagTree != null) tagTree.invalidate()
    if (treeScrollPane != null) treeScrollPane.invalidate()
    if (favoritesStrip != null) favoritesStrip.invalidate()
    if (tagPanel != null) tagPanel.invalidate()
}

boolean horizontalScrollBarNeeded(int panelWidth) {
    if (treeScrollPane == null || tagTree == null || tagTree.getRowCount() == 0) return false

    int contentWidth = (int) tagTree.getPreferredSize().width
    boolean verticalLikely = ((int) tagPanel.getPreferredSize().height) > viewportHeight()
    int verticalWidth = verticalLikely ? (int) treeScrollPane.getVerticalScrollBar().getPreferredSize().width : 0
    int availableWidth = panelWidth - 2 * panelBorderThickness - verticalWidth

    return contentWidth > availableWidth
}

boolean panelHasFocus() {
    Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
    return owner != null && tagPanel != null && SwingUtilities.isDescendingFrom(owner, tagPanel)
}

void fitPanelBounds() {
    if (tagPanel == null) return
    boolean stayExpanded = mouseOverPanel || panelHasFocus() || popupOpen ||
            (tagTree != null && tagTree.isEditing())
    int width = wideMode ? wideWidth() : (stayExpanded ? expandedWidth() : retractedWidth())
    animatePanelToWidth(width)
}

void animatePanelToWidth(int targetWidth) {
    if (resizeAnimationTimer != null) {
        resizeAnimationTimer.stop()
        resizeAnimationTimer = null
    }

    int startWidth = tagPanel.getWidth()
    int rowCount = tagTree != null ? tagTree.getRowCount() : 0
    if (resizeAnimationSteps <= 1 || startWidth == targetWidth || rowCount > resizeAnimationMaxRows) {
        applyPanelBounds(targetWidth)
        return
    }

    int[] step = [0]
    resizeAnimationTimer = new Timer(resizeAnimationStepMs, { ActionEvent e ->
        step[0]++
        if (step[0] >= resizeAnimationSteps) {
            ((Timer) e.getSource()).stop()
            resizeAnimationTimer = null
            applyPanelBounds(targetWidth)
        } else {
            float t = step[0] / (float) resizeAnimationSteps
            float eased = 1f - (1f - t) * (1f - t)
            applyPanelBounds(startWidth + (int) ((targetWidth - startWidth) * eased))
        }
    } as ActionListener)
    resizeAnimationTimer.start()
}

void applyPanelBounds(int width) {
    if (tagPanel == null) return
    int height = fittedHeight(width)
    Rectangle viewportBounds = viewportBoundsInHost()
    int x = (viewportBounds.x as int) + (viewportBounds.width as int) - width
    int y = viewportBounds.y as int
    if (tagPanel.getX() == x && tagPanel.getY() == y
            && tagPanel.getWidth() == width && tagPanel.getHeight() == height) return

    tagPanel.setBounds(x, y, width, height)
    overlayHost.revalidate()
    overlayHost.repaint()
}

/*
 ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑ Retract / expand ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
*/


/*
 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓ Utilities ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
*/

// Get qualified tag name considering parents
String getTagQualifiedName(def icon) {
    try {
        def tag = icon.getTag()
        if (tag == null) return null
        
        // Use qualifiedTag (like sample code)
        try {
            def qualified = tag.getQualifiedTag()
            if (qualified != null) {
                String content = qualified.getContent()
                if (content != null && !content.isEmpty()) {
                    return content
                }
            }
        } catch (Throwable t) {
            // If qualifiedTag is not available, use alternative method
        }
        
        // Alternative method: get from TagCategories
        String simpleName = tag.getContent()
        if (simpleName == null || simpleName.isEmpty()) {
            simpleName = tag.toString()
        }
        
        try {
            def state = readState()
            Map<String, String> allTags = new HashMap<String, String>()
            
            def collect = { cat ->
                allTags.put(cat.qualifiedName, cat.name)
                cat.children.each { collect(it) }
            }
            state.categories.each { collect(it) }
            state.uncategorizedTags.each { 
                allTags.put(it.qualifiedName, it.name)
            }
            
            for (Map.Entry<String, String> entry : allTags.entrySet()) {
                if (entry.getValue() == simpleName) {
                    return entry.getKey()
                }
            }
            
            return simpleName
            
        } catch (Throwable t) {
            return simpleName
        }
        
    } catch (Throwable t) {
        return null
    }
}

void showStatus(String message) {
    if (statusLabel != null) statusLabel.setText(" " + message)
}

JPanel transparentPanel(LayoutManager layout) {
    JPanel panel = new JPanel(layout)
    panel.setOpaque(false)
    return panel
}

Font itemFont() {
    if (cachedItemFont == null) cachedItemFont = new Font(panelTextFontName, Font.PLAIN, panelTextFontSize)
    return cachedItemFont
}

Color mapBackground() {
    return boundMapView.getBackground() ?: Color.WHITE
}

Color barTextColor() {
    return UITools.getTextColorForBackground(barColor)
}

Color panelBorderColor() {
    Color base = UITools.getTextColorForBackground(mapBackground())
    return new Color(base.getRed(), base.getGreen(), base.getBlue(), panelBorderOpacity)
}

Color blendColors(Color base, Color tint, float ratio) {
    return new Color(
            (int) (base.getRed() + (tint.getRed() - base.getRed()) * ratio),
            (int) (base.getGreen() + (tint.getGreen() - base.getGreen()) * ratio),
            (int) (base.getBlue() + (tint.getBlue() - base.getBlue()) * ratio))
}

Color barHoverColor() {
    return blendColors(barColor, barTextColor(), 0.18f)
}

void bindKey(JComponent component, int condition, int keyCode, int modifiers, String actionName, Closure action) {
    component.getInputMap(condition).put(KeyStroke.getKeyStroke(keyCode, modifiers), actionName)
    component.getActionMap().put(actionName, new AbstractAction() {
        @Override
        void actionPerformed(ActionEvent e) { action.call() }
    })
}

void addHoverListenerRecursively(Component component) {
    component.addMouseListener(hoverListener)
    if (component instanceof Container) {
        ((Container) component).components.each { addHoverListenerRecursively(it) }
    }
}

void pickGlyphs() {
    Font font = itemFont()
    if (font.canDisplayUpTo(markAll) != -1) markAll = "*"
    if (font.canDisplayUpTo(markSome) != -1) markSome = "~"
    if (font.canDisplayUpTo(favoriteSymbol) != -1) favoriteSymbol = "!"
    if (font.canDisplayUpTo(filterHidesSymbol) != -1) filterHidesSymbol = "v"
    if (font.canDisplayUpTo(highlightOnlySymbol) != -1) highlightOnlySymbol = "-"
}

String foldAccents(String text) {
    StringBuilder out = null
    for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i)
        char folded = ch < ((char) 128) ? ch : foldChar(ch)
        if (out == null && folded != ch) {
            out = new StringBuilder(text.length())
            out.append(text, 0, i)
        }
        if (out != null) out.append(folded)
    }
    return out == null ? text : out.toString()
}

char foldChar(char ch) {
    Character cached = accentFoldCache.get(ch)
    if (cached != null) return cached.charValue()

    String decomposed = java.text.Normalizer.normalize(String.valueOf(ch), java.text.Normalizer.Form.NFD)
    char base = ch
    for (int j = 0; j < decomposed.length(); j++) {
        if (Character.getType(decomposed.charAt(j)) != Character.NON_SPACING_MARK) {
            base = decomposed.charAt(j)
            break
        }
    }
    accentFoldCache.put(ch, base)
    return base
}




// ============================================================
// Tag Click Locator - Click on tag in the map
// ============================================================

void installClickLocator() {
    JRootPane anchor = findMainRootPane()
    if (anchor == null) {
        showStatus("📍 Tag click locator: main window not found.")
        return
    }
    
    Object existing = anchor.getClientProperty(CLICK_LOCATOR_KEY)
    if (existing != null) {
        Toolkit.getDefaultToolkit().removeAWTEventListener((AWTEventListener) existing)
        anchor.putClientProperty(CLICK_LOCATOR_KEY, null)
        tagClickLocatorListener = null
        showStatus("📍 Locator: OFF")
        return
    }
    
    tagClickLocatorListener = new AWTEventListener() {
        @Override
        void eventDispatched(AWTEvent event) {
            if (!(event instanceof MouseEvent)) return
            MouseEvent e = (MouseEvent) event
            if (e.getID() != MouseEvent.MOUSE_CLICKED) return
            if (e.getButton() != MouseEvent.BUTTON1 || e.getClickCount() != 1) return
            Component comp = e.getComponent()
            if (comp == null) return
            
            if (!comp.getClass().getName().endsWith("MapViewIconListComponent")) return
            try {
                def icon = comp.getIconAt(e.getPoint())
                if (icon == null) return
                if (!icon.getClass().getName().endsWith("TagIcon")) return
                
                String qualified = icon.getTag().qualifiedTag().getContent()
                if (qualified != null && !qualified.isEmpty()) {
                    SwingUtilities.invokeLater({
                        revealTagInTree(qualified)
                    })
                }
            } catch (Throwable ignore) {}
        }
    }
    
    Toolkit.getDefaultToolkit().addAWTEventListener(tagClickLocatorListener, AWTEvent.MOUSE_EVENT_MASK)
    anchor.putClientProperty(CLICK_LOCATOR_KEY, tagClickLocatorListener)
    showStatus("📍 Locator: ON — click a TAG on a node to locate it")
}

// ============================================================

// Find node by content
DefaultMutableTreeNode findNodeByContent(DefaultMutableTreeNode node, String qualified) {
    if (node == null) return null
    
    def userObj = node.getUserObject()
    if (userObj instanceof TagRow) {
        if (qualified.equals(userObj.qualifiedName)) {
            return node
        }
    }
    
    for (int i = 0; i < node.getChildCount(); i++) {
        def child = node.getChildAt(i)
        if (child instanceof DefaultMutableTreeNode) {
            def result = findNodeByContent(child, qualified)
            if (result != null) return result
        }
    }
    return null
}

void revealTagInTree(String qualifiedContent) {
    if (tagTree == null || treeRootNode == null) {
        showStatus("📍 Tag tree not available")
        return
    }
    
    clearFilterIfActive()
    
    def targetNode = findNodeByContent(treeRootNode, qualifiedContent)
    
    if (targetNode == null) {
        showStatus("📍 Tag '${qualifiedContent}' not found in tree")
        return
    }
    
    TreePath path = new TreePath(targetNode.getPath())
    if (path.getParentPath() != null) {
        tagTree.expandPath(path.getParentPath())
    }
    tagTree.setSelectionPath(path)
    tagTree.scrollPathToVisible(path)
    showStatus("📍 Located: ${qualifiedContent}")
}

// Clear filter
void clearFilterIfActive() {
    if (filterField != null && !filterField.getText().trim().isEmpty()) {
        filterField.setText("")
        filterText = ""
        applyFilterText()
    }
    if (tagTree != null) {
        try {
            tagTree.setFilter(null)
        } catch (Throwable ignore) {}
    }
}

JRootPane findMainRootPane() {
    for (Window w : Window.getWindows()) {
        if (w.isShowing() && w instanceof JFrame) {
            return ((JFrame) w).getRootPane()
        }
    }
    return null
}

/*
 ============================================================================
 End of Utilities
 ============================================================================
*/
