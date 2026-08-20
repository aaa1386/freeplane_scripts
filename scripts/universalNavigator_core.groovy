// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2760
// Version: 1.0

// Universal Navigator - core
//
// Alt+Tab style navigator for Freeplane: one window listing the places you have been, drawn
// as a mind map of their own rather than as a list. Bind it to a shortcut -- Ctrl+Tab here.
//
// It reads and writes <user directory>/universalNavigator.json, so Freeplane's "execute
// scripts without file restrictions" permission has to be on. Recording is a separate
// script, universalNavigatorRecorder.groovy: without it this window still opens, and only
// remembers the jumps it made itself.
//
// Categories are pluggable: see buildCategories() at the bottom.

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import org.freeplane.core.resources.ResourceController
import org.freeplane.core.ui.components.UITools
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils

import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import java.awt.*
import java.awt.event.*
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.geom.CubicCurve2D
import java.awt.geom.RoundRectangle2D
// Must come after java.awt.*, which would otherwise shadow List with java.awt.List.
import java.util.List

// ---------------------------------------------------------------- configuration

MAX_RECENT_ROOTS = 50
/** Fewer than the roots: these are drawn as one tree, and a hundred boxes is not a picture. */
MAX_RECENT_NODES = 40
CONFIG_FILE_NAME = 'universalNavigator.json'
LEGACY_CONFIG_FILE_NAME = 'recentRootsConfig.json'
STORE_VERSION = 3

/**
 * Digits in chronological order, and 0 comes FIRST here -- it is the root you are standing in,
 * position zero, with 1..9 the nine you were in before it. That is the opposite of the usual
 * "1..9 then 0" (which exists because 0 sits right of 9 on the number row), and the reason is
 * that these labels are not a list of slots: they are a distance back in time.
 */
SHORTCUT_DIGITS = '0123456789'
/**
 * Single-key labels after the ten digits. M is left out for history -- it used to swap
 * between the two views -- and Z is the prefix below. Keeping M out costs one label out of
 * sixty-nine and leaves the key free for whatever comes next.
 */
SHORTCUT_LETTERS = 'ABCDEFGHIJKLNOPQRSTUVWXY'
/**
 * Reserved to START a two-key label, and never a label by itself. That is what keeps the
 * scheme unambiguous: if some entry were labelled A and another AB, pressing A could not be
 * acted on -- the navigator would have to wait and see, and a timeout is a terrible way to
 * find out what a key meant. Being prefix-free, every keystroke is decidable the moment it
 * arrives. 34 single-key labels plus 35 two-key ones is 69, well past the 50 the list holds.
 */
SHORTCUT_PREFIX = 'Z'
CRUMB_FONT_SIZE = 13f
// Size of the window, as a fraction of the Freeplane window it sits over. The width is the
// user's call; the height follows so the proportions stay sane on a wide monitor.
NAV_WIDTH_RATIO = 0.90d
NAV_HEIGHT_RATIO = 0.80d

// --- minimap ----------------------------------------------------------------
// The whole window: a mind map of the entries alone, drawn the way Freeplane draws one, with
// ancestors that merely join two of them left as anonymous dots. Chronology plays no part in
// the shape -- the tree is the whole message, and the heat stripe carries the time.
//
// It always shows everything, so there is never anything to pan to: boxes take the room
// their branch leaves them, titles are truncated only when they must be, and only when even
// a squeezed row height will not do does the whole drawing get scaled down.
MINIMAP_ROW_HEIGHT = 30
MINIMAP_MIN_ROW_HEIGHT = 15
MINIMAP_FONT_SIZE = 14f
MINIMAP_MIN_FONT_SIZE = 9f
MINIMAP_PAD = 18
MINIMAP_COLUMN_GAP = 26
/** No box narrower than this: below it even a truncated title says nothing. */
MINIMAP_MIN_BOX = 54
/**
 * How much of the room left on its path a single box may take, as a multiple of its fair
 * share. Above 1 because the fair share is pessimistic: it assumes every level below will
 * want as much, and most want far less. Too high and a long title starves its own children.
 */
MINIMAP_GREED = 1.7d
MINIMAP_BOX_PAD_X = 9
MINIMAP_JUNCTION_DOT = 10
// --- selection pulse ---------------------------------------------------------
// A 3px outline loses a contest against a screen full of coloured boxes. Motion wins it
// without taking any more of the palette: a ring expands out of the selected box and fades,
// twice a cycle, so the eye is pulled there even mid-scan. The box's own colours are
// untouched -- they still say which node it is.
/** Frame interval. 25 fps is plenty for a fade and costs a repaint of an already-laid layout. */
MINIMAP_PULSE_MS = 40
/** One full expansion, in milliseconds. Slow enough to read as breathing, not blinking. */
MINIMAP_PULSE_PERIOD = 1500
/** How far the ring travels out from the box, in natural pixels. */
MINIMAP_PULSE_REACH = 16
/** Never shrink past this: below it the titles stop being readable, which defeats the view. */
MINIMAP_MIN_SCALE = 0.45d
/**
 * Growing left to right is the preferred reading, so the two-sided layout is a concession,
 * not the default: the branches split only once a single side would squeeze the rows below
 * this height. Left of it the rows start costing font size, which costs legibility.
 */
MINIMAP_SPLIT_ROW_HEIGHT = 20
/** Nor blow it up past this: a handful of entries filling a wide monitor looks like a toy. */
MINIMAP_MAX_SCALE = 1.7d

// --- recency heat -----------------------------------------------------------
// The minimap says nothing about WHEN, by design -- it is a picture of structure. The heat
// stripe puts the chronology back without touching any of it: it rides the left edge of each
// box, the one channel nothing else is using. The box's own colour still belongs to the node
// in the map, which is what identifies the context and must not be overwritten.
//
// ONE stripe carries both readings, split into THREE blocks by the calendar: today and
// yesterday get the red ramp, the rest of the last week gets a blue one, and everything older
// gets grey. This was two stripes for a while, red for the order and green for the day, and
// collapsing them is a trade made with eyes open -- what the second stripe said is now said
// by which ramp an entry is on:
//
//   - the ramp within a block stays RELATIVE, so it is always used end to end (the reasoning
//     is at heatOfRank, and it did not change);
//   - BETWEEN blocks the order is carried by HUE, not by brightness: red reads as hotter than
//     blue and blue as livelier than grey whatever the two ramps are doing internally. That is
//     the only way three blocks can be ordered at a glance without one ramp shouting over the
//     next -- brightness is already spent saying "in what order, within this block";
//   - the boundary between today and yesterday is GONE from the drawing. It survives in the
//     tooltip only. That is a real loss, accepted for one lane instead of two;
//   - grey still does the work by subtraction. A list where three entries are red, six blue
//     and forty grey answers "what am I working on" faster than any highlighting could.
//
// Costs to keep in mind: back from a long holiday, EVERYTHING is grey and the window looks
// switched off; and an entry still changes ramp overnight, twenty minutes of clock time
// turning red into blue -- though the step is now a step and no longer a cliff, which is
// most of what the middle block is for. A week away and yesterday's work is grey; a Monday
// morning and Friday's is blue, which is the case this was asked for.
/**
 * Narrowest the stripe may be. It usually ends up wider than this, because it also carries
 * the shortcut label -- inside the stripe the label is plainly not part of the title, and the
 * stripe stops being a 5px sliver and becomes something the eye can aim at.
 */
MINIMAP_HEAT_MIN_WIDTH = 6

/**
 * How many CALENDAR days the blue block reaches back over, today included. 7 makes it "this
 * last week", of which the first two days are already spoken for by red, so blue covers the
 * day before yesterday back to six days ago. Widen it and blue starts to mean "this
 * fortnight"; the ramp inside it rescales by itself, since it is relative.
 */
HEAT_WEEK_DAYS = 7

// ---------------------------------------------------------------- model

/**
 * One navigable entry. Identified by nodeId + mapPath, because node ids are only
 * unique within a map -- copy/paste across .mm files produces duplicates.
 * Key format matches the one proposed in discussion #2760: <nodeId>@<mapPath|'null'>
 */
class NavItem {
    String nodeId
    String mapPath          // absolute path of the .mm, or null for unsaved maps
    String mapName
    String title            // cached: needed to render entries of closed maps
    String lastSelectedNodeId
    String lastSelectedTitle
    long ts
    /** Depth from the map root. Null for entries recorded before the rail existed. */
    Integer level
    /** Ids of every ancestor, map root first. Lets us spot that one entry sits inside
     *  another without opening either map. */
    List<String> ancestorIds = []
    /** The node's own pair of colours, so a row looks like the node looks on the map. */
    String bgColor
    String fgColor
    /** For a recent NODE: the view root it was seen under, so returning restores the context
     *  and not merely the node. Null for recent roots, which are their own context. */
    String contextRootId

    String key() { nodeId + '@' + (mapPath ?: 'null') }

    Map toJson() {
        [nodeId: nodeId, mapPath: mapPath, mapName: mapName, title: title,
         level: level, ancestorIds: ancestorIds, bgColor: bgColor, fgColor: fgColor,
         lastSelectedNodeId: lastSelectedNodeId, lastSelectedTitle: lastSelectedTitle, ts: ts]
    }

    static NavItem fromJson(Map m) {
        new NavItem(nodeId: m.nodeId, mapPath: m.mapPath, mapName: m.mapName, title: m.title,
                    level: (m.level == null ? null : m.level as Integer),
                    ancestorIds: (m.ancestorIds ?: []) as List<String>,
                    bgColor: m.bgColor, fgColor: m.fgColor,
                    lastSelectedNodeId: m.lastSelectedNodeId, lastSelectedTitle: m.lastSelectedTitle,
                    ts: (m.ts ?: 0L) as long)
    }

    /** True when this entry lives inside the other one, in the same map. */
    boolean isInside(NavItem other) {
        return other != null && other.mapPath == mapPath && ancestorIds.contains(other.nodeId)
    }
}

/**
 * A node of the context tree. It carries an item when it stands for a list entry; otherwise
 * it is a junction -- an ancestor shared by two entries, which is exactly what makes the
 * grouping visible even though it was never a root itself and has no title here.
 */
class TreeNode {
    String key
    /** Id of the node this stands for, junctions included -- what lets the live map be asked
     *  where it sits among its siblings. Null only for the per-map bucket. */
    String nodeId
    String mapPath
    /** Only way to find an UNSAVED map, which has no path to be found by. */
    String mapName
    NavItem item
    int listIndex = -1
    TreeNode parent
    List<TreeNode> children = []
    int x
    int y
}

// ---------------------------------------------------------------- freeplane glue

c = ScriptUtils.c()
mapViewManager = Controller.currentController.mapViewManager

File configFile() { new File(c.userDirectory, CONFIG_FILE_NAME) }

String mapPathOf(mindMap) { mindMap?.file?.absolutePath }

/** Depth from the map root. getNodeLevel(boolean) is public API; the walk is a fallback. */
Integer levelOf(node) {
    if (node == null) return null
    try {
        return node.getNodeLevel(true)
    } catch (Exception ignored) {
        int depth = 0
        def parent = node.parent
        while (parent != null) { depth++; parent = parent.parent }
        return depth
    }
}

/**
 * The node's effective colours, straight from the public API. Measured to agree with
 * NodeStyleController.getBackgroundColor/getColor on every node tried, so there is no
 * reason to reach for the internal controller the way SearchPanel does.
 */
String backgroundColorOf(node) {
    try { return node.style.backgroundColorCode } catch (Exception ignored) { return null }
}

String textColorOf(node) {
    try { return node.style.textColorCode } catch (Exception ignored) { return null }
}

/**
 * The pair of colours a row should be painted with. The fallbacks have to AGREE with each
 * other - independent black/white defaults produce black on a dark background - and a node
 * whose text and background are the same colour (they exist: the root of the user's map is
 * #333333 on #333333) has to be rescued, or the row is unreadable.
 */
List<Color> rowColorsOf(NavItem item, Color listBackground) {
    Color background = parseColor(item.bgColor) ?: listBackground
    Color foreground = parseColor(item.fgColor) ?: UITools.getTextColorForBackground(background)
    if (contrastRatio(foreground, background) < 2.0d) {
        foreground = UITools.getTextColorForBackground(background)
    }
    return [background, foreground]
}


Color blendColors(Color a, Color b, double weightOfB) {
    double w = Math.max(0.0d, Math.min(1.0d, weightOfB))
    int r = (int) Math.round(a.getRed() * (1 - w) + b.getRed() * w)
    int g = (int) Math.round(a.getGreen() * (1 - w) + b.getGreen() * w)
    int bl = (int) Math.round(a.getBlue() * (1 - w) + b.getBlue() * w)
    return new Color(r, g, bl)
}

/**
 * Relative luminance, per WCAG. Used to decide whether a node colour is readable against
 * the list background - a node with white text would otherwise vanish on a light theme.
 */
double luminanceOf(Color color) {
    Closure channel = { int raw ->
        double v = raw / 255.0d
        return v <= 0.03928d ? v / 12.92d : Math.pow((v + 0.055d) / 1.055d, 2.4d)
    }
    return 0.2126d * channel(color.getRed()) + 0.7152d * channel(color.getGreen()) + 0.0722d * channel(color.getBlue())
}

double contrastRatio(Color a, Color b) {
    double la = luminanceOf(a)
    double lb = luminanceOf(b)
    return (Math.max(la, lb) + 0.05d) / (Math.min(la, lb) + 0.05d)
}

Color parseColor(String code) {
    if (!code) return null
    try {
        return Color.decode(code.length() > 7 ? code.substring(0, 7) : code)
    } catch (Exception ignored) {
        return null
    }
}

/** Ids of every ancestor, map root first. */
List<String> ancestorIdsOf(node) {
    List<String> ids = new ArrayList<String>()
    def ancestor = node?.parent
    while (ancestor != null) { ids.add(0, ancestor.id); ancestor = ancestor.parent }
    return ids
}


def findOpenMindMap(String mapPath) {
    if (mapPath == null) return null
    c.openMindMaps.find { it.file?.absolutePath == mapPath }
}

/**
 * Resolves an item to a live NodeModel, opening the owning map if needed.
 * Returns null when the map or the node is gone.
 */
NodeModel resolveNode(NavItem item) {
    def mindMap = findOpenMindMap(item.mapPath)
    if (mindMap == null && item.mapPath) {
        File f = new File(item.mapPath)
        if (!f.exists()) return null
        try {
            mindMap = c.mapLoader(f).withView().mindMap
        } catch (Exception e) {
            e.printStackTrace()
            return null
        }
    }
    if (mindMap == null) return null
    return mindMap.root.delegate.map.getNodeForID(item.nodeId)
}

/**
 * Sets the view root. IMapViewManager.setViewRoot() calls changeToMap() internally,
 * so this switches maps on its own -- no JumpInAction / selection dance needed.
 */
void jumpInTo(NodeModel nodeModel) {
    mapViewManager.setViewRoot(nodeModel)
}

void selectNode(NodeModel nodeModel) {
    mapViewManager.mapView.mapSelection.selectAsTheOnlyOneSelected(nodeModel)
}

// ---------------------------------------------------------------- persistence

/**
 * Talks to the recorder listener, if it is installed.
 *
 * universalNavigatorRecorder.groovy holds recentRoots IN MEMORY and rewrites the whole store
 * file every few seconds, so anything we change on disk behind its back is undone at its
 * next flush. That is not a race we can win by writing faster: the rule is to ask it to
 * flush before reading, and to reload after writing. Its handles are typed as plain JDK
 * interfaces in UIManager, so we never need its class.
 *
 * Returns null when the recorder is not installed, which is a perfectly good state -- the
 * navigator then owns the file alone and nothing here changes.
 */
Object askRecorder(String handle) {
    try {
        def value = UIManager.get('universalNavigator.recorder.' + handle)
        if (value instanceof Runnable) { ((Runnable) value).run(); return true }
        if (value instanceof java.util.concurrent.Callable) return ((java.util.concurrent.Callable) value).call()
    } catch (Exception ignored) {
    }
    return null
}

Map loadStore() {
    // Its unflushed state is newer than the file's: read that first or the window shows a
    // list up to FLUSH_MS out of date.
    askRecorder('flush')
    File file = configFile()
    if (!file.exists()) {
        // Persist right away, so the import happens once and survives the v1 file going away.
        Map migrated = migrateLegacyStore()
        if (migrated.recentRoots) saveStore(migrated)
        return migrated
    }
    try {
        def parsed = new JsonSlurper().parseText(file.text)
        List<NavItem> items = (parsed.recentRoots ?: []).collect { NavItem.fromJson(it) }
        // The recorder's trail rides in the same file; the Recent nodes category is built
        // from it. Kept raw -- it is the recorder's to own, and we only ever read it.
        Map store = [recentRoots: items, events: (parsed.events ?: [])]
        // Both write back at once: the stale chains and the duplicates are in the FILE, not
        // just on screen, and leaving them there means doing this again every invocation.
        // The `|` and not `||` is deliberate -- both have to run, whatever the first returns.
        boolean changed = refreshFromOpenMaps(items) | reconcileSavedMaps(store)
        if (changed) {
            saveStore(store)
            askRecorder('reload')
        }
        return store
    } catch (Exception e) {
        e.printStackTrace()
        return [recentRoots: []]
    }
}

/**
 * Re-reads from the live map everything that was only ever a SNAPSHOT of it.
 *
 * ancestorIds, level, title and colours are all recorded at the moment of the jump in and
 * never revisited. Move a node afterwards -- or rename it, or recolour it -- and the entry
 * still describes where it used to be. The minimap then draws last week's hierarchy with a
 * straight face, which is the one failure mode nothing on screen hints at: measured on the
 * user's own store, 16 of 26 chains that could be checked were stale, one of them off by
 * nine levels.
 *
 * Only open maps can answer. Opening a map just to refresh a cache would be a terrible
 * trade, so entries of closed maps keep what they have and are drawn from it.
 *
 * Entries whose node is GONE from an open map are dropped. A root that no longer exists is
 * not a root: activating it can only report "node not found", and it takes up a shortcut key
 * and a box in the minimap on the way. A CLOSED map, on the other hand, says nothing about
 * whether its nodes still exist, so nothing there is ever dropped on suspicion.
 *
 * Returns true when anything changed, so the caller can persist it.
 */
boolean refreshFromOpenMaps(List<NavItem> items) {
    boolean changed = false
    List<NavItem> gone = new ArrayList<NavItem>()
    Map<String, Object> maps = new HashMap<String, Object>()
    items.each { NavItem item ->
        String cacheKey = item.mapPath ?: ('name:' + item.mapName)
        def mindMap
        if (maps.containsKey(cacheKey)) {
            mindMap = maps.get(cacheKey)
        } else {
            mindMap = item.mapPath ? findOpenMindMap(item.mapPath)
                                   : c.openMindMaps.find { it.file == null && it.name == item.mapName }
            maps.put(cacheKey, mindMap)
        }
        if (mindMap == null) return
        def node
        try {
            node = mindMap.node(item.nodeId)
        } catch (Exception ignored) {
            return
        }
        if (node == null) {
            // Only trust this when the map is genuinely loaded. Mid-reload a map can be open
            // with nothing in it yet, and dropping the whole list on that would be silent
            // and irreversible.
            boolean loaded = false
            try {
                loaded = (mindMap.root != null)
            } catch (Exception ignored) {
            }
            if (loaded) gone.add(item)
            return
        }

        Integer level = levelOf(node)
        List<String> ancestors = ancestorIdsOf(node)
        String title = (node.plainText ?: node.text ?: '').replaceAll('\\s+', ' ').trim()
        if (title.length() > 200) title = title.substring(0, 200)
        String background = backgroundColorOf(node)
        String foreground = textColorOf(node)

        // The remembered selection is a snapshot too, and the one that hurts: a node moved
        // out of this root cannot be selected under it, and asking anyway drops the view
        // back to the map root (see activateRecentRoot). Forgetting it costs the spot and
        // keeps the jump in.
        if (item.lastSelectedNodeId) {
            def remembered = null
            try {
                remembered = mindMap.node(item.lastSelectedNodeId)
            } catch (Exception ignored) {
            }
            if (remembered == null || !isUnder(remembered.delegate, node.delegate)) {
                item.lastSelectedNodeId = null
                item.lastSelectedTitle = null
                changed = true
            }
        }

        if (item.level != level) { item.level = level; changed = true }
        if (item.ancestorIds != ancestors) { item.ancestorIds = ancestors; changed = true }
        if (title && item.title != title) { item.title = title; changed = true }
        if (item.bgColor != background) { item.bgColor = background; changed = true }
        if (item.fgColor != foreground) { item.fgColor = foreground; changed = true }
    }
    if (!gone.isEmpty()) {
        // By identity: NavItem has no equals(), which is what we want here -- two entries can
        // legitimately share a node id across maps.
        gone.each { NavItem dead -> items.removeAll { it.is(dead) } }
        changed = true
    }
    return changed
}

void saveStore(Map store) {
    try {
        // universalNavigatorRecorder.groovy keeps a chronological trail in the same file.
        // It is not ours to rewrite, but it would be ours to destroy if we dropped it here.
        File file = configFile()
        def events = []
        if (file.exists()) {
            try {
                events = new JsonSlurper().parseText(file.text).events ?: []
            } catch (Exception ignored) {
            }
        }
        file.text = new JsonBuilder([
                version    : STORE_VERSION,
                recentRoots: store.recentRoots.collect { it.toJson() },
                events     : events
        ]).toPrettyString()
    } catch (Exception e) {
        e.printStackTrace()
    }
}

/**
 * Folds entries recorded before a map was first saved into the ones recorded after it.
 *
 * The key is nodeId@mapPath, and an unsaved map has no path -- so the first save silently
 * turns every entry already recorded into a stranger, and the same nodes come back a second
 * time under the file name. The whole subtree then appears twice.
 *
 * Matching on the node id AND on the entire ancestor chain is what makes this safe to do
 * automatically: ids are only unique within a map, but two different maps agreeing on an id
 * and on every one of its ancestors is not something copy/paste produces by accident.
 */
boolean reconcileSavedMaps(Map store) {
    List<NavItem> pathless = store.recentRoots.findAll { it.mapPath == null }
    if (pathless.isEmpty()) return false
    boolean changed = false
    pathless.each { NavItem orphan ->
        NavItem saved = store.recentRoots.find { NavItem other ->
            other.mapPath != null && other.nodeId == orphan.nodeId &&
                    other.ancestorIds == orphan.ancestorIds
        }
        if (saved == null) return       // genuinely still unsaved: leave it alone
        int orphanAt = store.recentRoots.indexOf(orphan)
        int savedAt = store.recentRoots.indexOf(saved)
        store.recentRoots.remove(orphan)
        // Position decides which visit was the more recent, NOT the timestamp: the list's
        // own order is the record of recency (recordRoot inserts at the top), while ts is
        // zero on everything migrated from the v1 file. The survivor inherits the higher
        // place, and with it the selection remembered there.
        if (orphanAt < savedAt) {
            saved.lastSelectedNodeId = orphan.lastSelectedNodeId ?: saved.lastSelectedNodeId
            saved.lastSelectedTitle = orphan.lastSelectedTitle ?: saved.lastSelectedTitle
            store.recentRoots.remove(saved)
            store.recentRoots.add(Math.min(orphanAt, store.recentRoots.size()), saved)
        }
        saved.ts = Math.max(saved.ts, orphan.ts)
        changed = true
    }
    return changed
}

/**
 * Best effort import of the old Recent Roots Navigator config, which stored bare
 * node ids with no map path. Ids are resolved against currently open maps; the
 * ones that don't resolve are dropped, since they can't be disambiguated.
 */
Map migrateLegacyStore() {
    File legacy = new File(c.userDirectory, LEGACY_CONFIG_FILE_NAME)
    if (!legacy.exists()) return [recentRoots: []]
    try {
        def old = new JsonSlurper().parseText(legacy.text)
        def lastSelection = old.lastSelectionPerRoot ?: [:]
        def items = []
        (old.recentRootsLocations ?: []).reverse().each { String id ->
            for (mindMap in c.openMindMaps) {
                NodeModel found = mindMap.root.delegate.map.getNodeForID(id)
                if (found == null) continue
                String lastId = lastSelection[id]
                NodeModel lastNode = lastId ? mindMap.root.delegate.map.getNodeForID(lastId) : null
                items << new NavItem(
                        nodeId: id, mapPath: mapPathOf(mindMap), mapName: mindMap.name,
                        title: found.text, lastSelectedNodeId: lastId,
                        lastSelectedTitle: lastNode?.text, ts: 0L)
                break
            }
        }
        return [recentRoots: items]
    } catch (Exception e) {
        e.printStackTrace()
        return [recentRoots: []]
    }
}

// ---------------------------------------------------------------- recently edited

/** A live node turned into an entry, with `stamp` as the time this list is ordered by. */
NavItem navItemFromNode(node, long stamp) {
    def mindMap = node.mindMap
    String text = (node.plainText ?: node.text ?: '').replaceAll('\\s+', ' ').trim()
    return new NavItem(
            nodeId: node.id,
            mapPath: mapPathOf(mindMap),
            mapName: mindMap?.name,
            title: text.length() > 200 ? text.substring(0, 200) : text,
            level: levelOf(node),
            ancestorIds: ancestorIdsOf(node),
            bgColor: backgroundColorOf(node),
            fgColor: textColorOf(node),
            ts: stamp)
}

/**
 * The nodes edited most recently, read straight from the maps.
 *
 * Nothing is recorded and nothing can fall out of step: Freeplane already stamps every node
 * with a modification time (preference save_modification_times, on by default). So unlike
 * the other two categories this one works RETROACTIVELY -- it knows about edits made before
 * this script existed, and about edits made on another machine, because the stamp travels
 * inside the .mm.
 *
 * Only open maps can answer; a closed one would have to be parsed first.
 *
 * Keeps a short sorted list rather than collecting and sorting everything: measured on the
 * user's own maps, that is 67,194 nodes, and there is no reason to build a list of them all
 * to keep forty.
 */
List<NavItem> loadRecentlyEdited() {
    List best = new ArrayList()
    List<Long> stamps = new ArrayList<Long>()
    long floor = Long.MIN_VALUE
    c.openMindMaps.each { mindMap ->
        try {
            mindMap.root.findAll().each { node ->
                Date when = node.lastModifiedAt
                if (when == null) return
                long stamp = when.getTime()
                // Once the list is full, anything older than its tail cannot get in.
                if (stamps.size() >= MAX_RECENT_NODES && stamp <= floor) return
                int at = 0
                while (at < stamps.size() && stamps[at] > stamp) at++
                stamps.add(at, stamp)
                best.add(at, node)
                if (stamps.size() > MAX_RECENT_NODES) {
                    stamps.remove(stamps.size() - 1)
                    best.remove(best.size() - 1)
                }
                floor = stamps[stamps.size() - 1]
            }
        } catch (Exception ignored) {
        }
    }
    List<NavItem> items = new ArrayList<NavItem>()
    best.eachWithIndex { node, int i -> items.add(navItemFromNode(node, stamps[i])) }
    return items
}

/**
 * The view root this node was last seen under, according to the recorder's trail.
 *
 * The trail records every visit as a (node, root) pair, so it can answer this for any node
 * ever visited -- including one reached from a category that carries no context of its own.
 */
/** True when `target` is `ancestor` itself, or hangs anywhere below it. */
boolean isUnder(NodeModel target, NodeModel ancestor) {
    NodeModel walker = target
    while (walker != null) {
        if (walker.is(ancestor)) return true
        walker = walker.getParentNode()
    }
    return false
}

String contextRootIdFromTrail(Map store, NavItem item) {
    List events = (store.events ?: []) as List
    for (int i = events.size() - 1; i >= 0; i--) {
        Map event = (Map) events[i]
        if (event?.nodeId != item.nodeId) continue
        if ((event.mapPath ?: null) != (item.mapPath ?: null)) continue
        return event.rootId
    }
    return null
}

/**
 * Goes to a node, choosing the view root to show it under. Three answers, in this order:
 *
 *  1. The trail remembers a root for it, and the node is still under that root -- go there.
 *     Restoring the context is the point; it is where the node made sense last time.
 *  2. No context, but the node is already reachable from where you are -- change nothing.
 *  3. Neither -- the map's own root. Selecting a node while the view is rooted elsewhere
 *     would leave the selection off screen entirely.
 *
 * Context comes FIRST, and reachability only rescues what it cannot answer. Ordering it the
 * other way looks safer and is nearly useless: open a fresh map view and the view root is
 * the map root, which makes every node in the map "already reachable" -- so the remembered
 * context would be discarded in exactly the situation it exists for. That is the common way
 * in, not an edge case.
 */
void activateAnyNode(Map store, NavItem item) {
    rememberSelectionOfCurrentRoot(store)
    NodeModel target = resolveNode(item)
    if (target == null) {
        UITools.informationMessage("Universal Navigator: node not found (map closed, moved or deleted).")
        return
    }

    String rootId = item.contextRootId ?: contextRootIdFromTrail(store, item)
    NodeModel context = rootId ? target.map.getNodeForID(rootId) : null
    // A remembered root ages exactly like every other recorded position: move the node
    // afterwards and it no longer sits under it. Measured on the real trail, 2 of 29
    // remembered roots were already stale -- jumping to those would have left the selection
    // outside the view, which looks like the navigator simply failing.
    if (context != null && !isUnder(target, context)) context = null

    def viewRoot = c.viewRoot
    boolean reachable = false
    if (viewRoot != null && viewRoot.mindMap?.file?.absolutePath == item.mapPath) {
        NodeModel walker = target
        while (walker != null && !reachable) {
            if (walker.getID() == viewRoot.id) reachable = true
            walker = walker.getParentNode()
        }
    }

    if (context != null) {
        // Already rooted exactly there: jumping would be a no-op with a repaint attached.
        if (viewRoot == null || viewRoot.id != context.getID()) jumpInTo(context)
    } else if (!reachable) {
        jumpInTo(target.map.rootNode)
    }
    selectNode(target)
    recordRoot(store, c.viewRoot)
}

// ---------------------------------------------------------------- recent nodes

/**
 * The recent NODES: the recorder's chronological trail, one entry per node, newest first.
 *
 * The trail holds every visit, so the same node appears many times; here each one is kept
 * once, at its most recent visit. Capped well below the roots list because these are drawn
 * as one tree and a hundred boxes stop being a picture of anything.
 *
 * Entries recorded before the recorder cached ancestry arrive without it. refreshFromOpenMaps
 * fills that in for whatever map is open; the rest hang directly off their map, which is a
 * flatter tree but an honest one.
 */
List<NavItem> loadRecentNodes(Map store) {
    List events = (store.events ?: []) as List
    Set<String> seen = new LinkedHashSet<String>()
    List<NavItem> items = new ArrayList<NavItem>()
    for (int i = events.size() - 1; i >= 0; i--) {
        Map event = (Map) events[i]
        if (event?.nodeId == null) continue
        String key = event.nodeId + '@' + (event.mapPath ?: 'null')
        if (!seen.add(key)) continue
        items.add(new NavItem(
                nodeId: event.nodeId,
                mapPath: event.mapPath,
                mapName: event.mapName,
                title: event.title,
                level: (event.level == null ? null : event.level as Integer),
                ancestorIds: (event.ancestorIds ?: []) as List<String>,
                bgColor: event.bgColor,
                fgColor: event.fgColor,
                contextRootId: event.rootId,
                ts: (event.ts ?: 0L) as long))
        if (items.size() >= MAX_RECENT_NODES) break
    }
    return items
}

/**
 * Recent nodes and Recently edited go to a node the same way -- see activateAnyNode. The
 * only difference is where the remembered root comes from: an entry off the trail carries
 * its own, one found by modification time has to be looked up.
 */
void activateRecentNode(Map store, NavItem item) {
    activateAnyNode(store, item)
}

// ---------------------------------------------------------------- recent roots

/** Records the node currently selected, so returning to this root restores it. */
void rememberSelectionOfCurrentRoot(Map store) {
    def viewRoot = c.viewRoot
    if (viewRoot == null) return
    def selected = c.selected
    NavItem entry = store.recentRoots.find { it.nodeId == viewRoot.id && it.mapPath == mapPathOf(viewRoot.mindMap) }
    if (entry != null && selected != null) {
        entry.lastSelectedNodeId = selected.id
        entry.lastSelectedTitle = selected.text
    }
}

/** Moves a node to the top of the recent roots list. */
void recordRoot(Map store, rootNode) {
    if (rootNode == null) return
    def mindMap = rootNode.mindMap
    String path = mapPathOf(mindMap)
    NavItem existing = store.recentRoots.find { it.nodeId == rootNode.id && it.mapPath == path }
    store.recentRoots.remove(existing)
    store.recentRoots.add(0, new NavItem(
            nodeId: rootNode.id,
            mapPath: path,
            mapName: mindMap?.name,
            title: rootNode.text,
            level: levelOf(rootNode),
            ancestorIds: ancestorIdsOf(rootNode),
            bgColor: backgroundColorOf(rootNode),
            fgColor: textColorOf(rootNode),
            lastSelectedNodeId: existing?.lastSelectedNodeId,
            lastSelectedTitle: existing?.lastSelectedTitle,
            ts: System.currentTimeMillis()))
    while (store.recentRoots.size() > MAX_RECENT_ROOTS) store.recentRoots.remove(store.recentRoots.size() - 1)
    saveStore(store)
}

/**
 * Drops every entry older than the anchor from the recent list -- everything below it, since
 * the list is ordered most recent first. Returns how many went, so the caller can say so.
 *
 * Anchored by key rather than by list position: a category other than Recent roots may well
 * be showing something else, and an index into the wrong list would trim the wrong tail.
 */
/**
 * Drops one entry, leaving everything else alone. Same handshake as forgetOlderThan, for the
 * same reason: the recorder holds the list in memory and would put it back at its next flush.
 *
 * Returns the position it held, or -1 if it was not there -- the caller uses it to put the
 * selection somewhere sensible afterwards.
 */
int forgetEntry(Map store, NavItem anchor) {
    if (anchor == null) return -1
    Map onDisk = loadStore()
    if (onDisk.recentRoots) store.recentRoots = onDisk.recentRoots
    int at = store.recentRoots.findIndexOf { it.key() == anchor.key() }
    if (at < 0) return -1
    store.recentRoots.remove(at)
    saveStore(store)
    askRecorder('reload')
    return at
}

int forgetOlderThan(Map store, NavItem anchor) {
    if (anchor == null) return 0
    // Deletion is the one edit the recorder does not make itself, so it is the one that
    // needs the full handshake: work from what it holds (loadStore flushes it first), then
    // make it read the result back. Without the reload it keeps the deleted entries in
    // memory and restores them at its next flush -- the list returns out of nowhere, a
    // minute later, which is exactly how this was reported.
    Map onDisk = loadStore()
    if (onDisk.recentRoots) store.recentRoots = onDisk.recentRoots

    int at = store.recentRoots.findIndexOf { it.key() == anchor.key() }
    if (at < 0) return 0
    int dropped = store.recentRoots.size() - at - 1
    if (dropped <= 0) return 0
    while (store.recentRoots.size() > at + 1) {
        store.recentRoots.remove(store.recentRoots.size() - 1)
    }
    saveStore(store)
    askRecorder('reload')
    return dropped
}

void activateRecentRoot(Map store, NavItem item) {
    rememberSelectionOfCurrentRoot(store)
    NodeModel target = resolveNode(item)
    if (target == null) {
        UITools.informationMessage("Universal Navigator: node not found (map closed, moved or deleted).")
        return
    }
    jumpInTo(target)
    NodeModel toSelect = null
    if (item.lastSelectedNodeId) {
        toSelect = target.map.getNodeForID(item.lastSelectedNodeId)
        // The remembered selection ages exactly like the remembered root does in
        // activateAnyNode: work through an inbox and the node you stopped at gets moved out
        // from under the root you stopped in. Selecting it anyway does not merely miss the
        // spot -- MapView.display() calls restoreRootNode() for any node outside the current
        // view root (verified in the dev runtime: jump in to A, select a node outside A, and
        // the view root comes back as the MAP root). So the jump in we just made is undone
        // and the whole activation looks like it did nothing at all. Measured on the real
        // store: 1 of 26 entries carrying a remembered selection was already stale, and it
        // was exactly the root reported as "the navigator does not work for this one".
        if (toSelect != null && !isUnder(toSelect, target)) toSelect = null
    }
    selectNode(toSelect ?: target)
    recordRoot(store, c.viewRoot)
}

// ---------------------------------------------------------------- context tree

/**
 * Grows a trie out of the ancestor chains: entries of the same map that share ancestors
 * meet at the deepest one they have in common. The map itself is the first step of the
 * chain, since ancestry never crosses .mm files.
 */
TreeNode buildContextTree(List<NavItem> items) {
    TreeNode root = new TreeNode(key: '')
    Map<String, TreeNode> byKey = new LinkedHashMap<String, TreeNode>()
    (items ?: []).eachWithIndex { NavItem item, int index ->
        String mapKey = item.mapPath ?: ('name:' + (item.mapName ?: '?'))
        List<String> chain = [mapKey] + ((item.ancestorIds ?: []) as List<String>) + [item.nodeId]
        TreeNode walker = root
        StringBuilder path = new StringBuilder()
        chain.eachWithIndex { String step, int depth ->
            path.append('/').append(step)
            String k = path.toString()
            TreeNode found = byKey.get(k)
            if (found == null) {
                // Step 0 is the map bucket, not a node; everything after it is a node id.
                found = new TreeNode(key: k, parent: walker, mapPath: item.mapPath,
                                     mapName: item.mapName,
                                     nodeId: (depth == 0 ? null : step))
                walker.children.add(found)
                byKey.put(k, found)
            }
            walker = found
        }
        // An entry may well be an ancestor of another one, in which case the node is already
        // here as a junction: it only has to be told that it is also a list entry.
        walker.item = item
        walker.listIndex = index
    }
    return root
}

/**
 * Drops junctions that fork nothing. A textless node with a single child says nothing at
 * all -- it cannot show a grouping, and it has no title to say what it is -- so a chain of
 * them is replaced by the point where the branch actually splits.
 */
void compressTree(TreeNode node) {
    List<TreeNode> kept = new ArrayList<TreeNode>()
    node.children.each { TreeNode child ->
        TreeNode walker = child
        while (walker.item == null && walker.children.size() == 1) walker = walker.children[0]
        walker.parent = node
        kept.add(walker)
    }
    node.children = kept
    node.children.each { compressTree(it) }
}


/** Every list position under a node, itself included. */
void gatherListIndexes(TreeNode node, List<Integer> out) {
    if (node.listIndex >= 0) out.add(node.listIndex)
    node.children.each { gatherListIndexes(it, out) }
}


/**
 * The live node a tree node stands for, junctions included, or null when the map that would
 * answer is not open. Reading the order out of a closed .mm would mean parsing the file,
 * which is a steep price for cosmetics.
 */
def liveNodeOf(TreeNode node, Map cache) {
    if (node.nodeId == null) return null
    // An unsaved map has no path, so it has to be found by name among the open ones -- the
    // same fallback refreshFromOpenMaps already uses. Without it every entry of an unsaved
    // map answers "I don't know" to the sibling question, and the whole map falls back to
    // being ordered by recency: visibly NOT the order the map itself is in, which is the one
    // thing the minimap promises. Cached under a key of its own so the two lookups, by path
    // and by name, cannot collide.
    String cacheKey = node.mapPath ?: ('name:' + (node.mapName ?: '?'))
    def mindMap
    if (cache.containsKey(cacheKey)) {
        mindMap = cache.get(cacheKey)
    } else {
        mindMap = node.mapPath ? findOpenMindMap(node.mapPath)
                               : c.openMindMaps.find { it.file == null && it.name == node.mapName }
        cache.put(cacheKey, mindMap)
    }
    if (mindMap == null) return null
    try {
        return mindMap.node(node.nodeId)
    } catch (Exception ignored) {
        return null
    }
}

/**
 * Where `child` sits among `parent`'s children IN THE MAP -- following the map's branches,
 * not this tree's.
 *
 * The two are NOT the same tree, and that is the whole difficulty. Compression drops every
 * ancestor nobody listed, so a child here is often a grandchild there. Asking each node for
 * its index among its OWN parent's children compares numbers taken from different parents,
 * and every only-child answers 0 -- which is how "bbb", the only child of an unlisted "ggg",
 * tied with "aaa" and then won the tie-break on recency, landing above it.
 *
 * So: climb from the child until reaching the node that really is a child of `parent`, and
 * take THAT one's position. It is the branch of `parent` the child hangs from, which is the
 * thing being ordered.
 */
Integer siblingIndexUnder(TreeNode parent, TreeNode child, Map cache) {
    def parentLive = liveNodeOf(parent, cache)
    def childLive = liveNodeOf(child, cache)
    if (parentLive == null || childLive == null) return null
    try {
        def walker = childLive
        while (walker != null && walker.parent != null && walker.parent.id != parentLive.id) {
            walker = walker.parent
        }
        // Ran out of ancestors: parent is not above child in the map after all.
        if (walker == null || walker.parent == null) return null
        int at = parentLive.children.findIndexOf { it.id == walker.id }
        return at < 0 ? null : at
    } catch (Exception ignored) {
        return null
    }
}

/**
 * Orders siblings the way the map itself does. The minimap is meant to look like the map it
 * is drawn from, and nothing in the stored entries records where a node sits among its
 * siblings -- so the live map is asked, at the moment the window opens.
 *
 * Whatever it cannot answer for (closed map, deleted node, unsaved map) falls back to the
 * recency barycentre and is placed after the nodes it could answer for, so a partial answer
 * still produces a stable order rather than a scrambled one.
 */
void sortTreeByMapOrder(TreeNode node, Map cache) {
    Map<TreeNode, List> keys = new HashMap<TreeNode, List>()
    node.children.each { TreeNode child ->
        Integer at = siblingIndexUnder(node, child, cache)
        List<Integer> found = new ArrayList<Integer>()
        gatherListIndexes(child, found)
        double sum = 0.0d
        found.each { sum += it }
        double barycentre = found.isEmpty() ? Double.MAX_VALUE : sum / found.size()
        keys.put(child, [(at == null ? 1 : 0), (at == null ? 0 : at.intValue()), barycentre])
    }
    node.children.sort { TreeNode a, TreeNode b ->
        List ka = keys.get(a), kb = keys.get(b)
        int byKnown = Integer.compare((int) ka[0], (int) kb[0])
        if (byKnown != 0) return byKnown
        int byMap = Integer.compare((int) ka[1], (int) kb[1])
        if (byMap != 0) return byMap
        return Double.compare((double) ka[2], (double) kb[2])
    }
    node.children.each { sortTreeByMapOrder(it, cache) }
}


/** Longest path down from here. Doubles as the horizontal position of a junction. */
int subtreeHeight(TreeNode node) {
    int best = 0
    node.children.each { int h = subtreeHeight(it); if (h > best) best = h }
    return node.children.isEmpty() ? 0 : best + 1
}

void assignTreeY(TreeNode node) {
    if (node.children.isEmpty()) return    // leaves were placed already
    node.children.each { assignTreeY(it) }
    node.y = (int) Math.round((node.children.first().y + node.children.last().y) / 2.0d)
}



TreeNode buildPreparedTree(List<NavItem> items) {
    TreeNode root = buildContextTree(items)
    compressTree(root)
    // The synthetic root is itself a junction that forks nothing while a single map is in
    // play, and it would draw a stub going nowhere.
    while (root.item == null && root.children.size() == 1) {
        root = root.children[0]
        root.parent = null
    }
    return root
}


/** Ordered like the map it is a picture of. */
TreeNode prepareMinimapTree(List<NavItem> items) {
    TreeNode root = buildPreparedTree(items)
    sortTreeByMapOrder(root, new HashMap())
    return root
}



// ---------------------------------------------------------------- minimap

/**
 * How hot an entry is WITHIN ITS BLOCK: 1 for the most recent of the block, 0 for the oldest,
 * the rest interpolated by POSITION. Absolute dates decide which block an entry lands in and
 * play no part after that.
 *
 * The ramp is therefore always fully used: five visits made this morning spread across the
 * whole red, and so do forty entries spanning three months across the whole grey. An absolute
 * scale within a block would paint that first list one flat colour, which is exactly when
 * telling them apart matters most.
 *
 * The cost, stated plainly: inside a block the stripe answers "in what order", never "how
 * long ago". Two neighbouring shades of red can be a minute or a day and a half apart. The
 * tooltip carries the real age, and the block carries the only absolute claim there is.
 */
double heatOfRank(int listIndex, int total) {
    if (listIndex < 0 || total <= 1) return 1.0d
    return 1.0d - (listIndex / (double) (total - 1))
}

/**
 * One hue, red: the recent block. Both ends stay unmistakably RED -- the ramp runs from a
 * strong salmon to full red, not from near-white to full red as it did when this stripe
 * carried the whole list.
 *
 * That compression is not cosmetic. With only two entries in the block, the older one sits at
 * heat 0, and the old pale end (242,205,200) came out FAINTER than the strongest grey: the
 * quiet block would have been shouting over the loud one, which is the one thing the split
 * into blocks exists to prevent. Every red must beat every grey; the ramp is what orders them
 * after that.
 */
Color recentColor(double heat, Color background) {
    boolean light = luminanceOf(background) > 0.5d
    // On a dark theme "faded" must not mean "pale": a near-white pink would SHOUT against the
    // panel and the scale would read backwards, the oldest entry drawing the eye first. Faded
    // there means deep and dim, so that in both themes strong still means recent.
    Color faded = light ? new Color(232, 128, 116) : new Color(156, 56, 46)
    Color full = light ? new Color(208, 32, 16) : new Color(255, 74, 58)
    return blendColors(faded, full, Math.max(0.0d, Math.min(1.0d, heat)))
}

/**
 * Midnight today, midnight yesterday, and so on back over the week -- index i is the start of
 * the day i days ago, in local time.
 *
 * CALENDAR days, not 24 hour windows: at nine in the morning, "yesterday" has to mean the
 * whole of yesterday and not the stretch back to nine the previous morning, or the stripe
 * would disagree with the only calendar the user has. Calendar.add does the stepping, so a
 * daylight saving change does not shift a boundary by an hour.
 */
long[] dayBoundaries() {
    Calendar midnight = Calendar.getInstance()
    midnight.set(Calendar.HOUR_OF_DAY, 0)
    midnight.set(Calendar.MINUTE, 0)
    midnight.set(Calendar.SECOND, 0)
    midnight.set(Calendar.MILLISECOND, 0)
    long[] starts = new long[HEAT_WEEK_DAYS]
    for (int i = 0; i < HEAT_WEEK_DAYS; i++) {
        starts[i] = midnight.getTimeInMillis()
        midnight.add(Calendar.DAY_OF_MONTH, -1)
    }
    return starts
}

/**
 * How many calendar days ago the entry is: 0 today, 1 yesterday, and on back to the end of
 * the window. -1 for everything older -- and for a missing timestamp. Entries carried over
 * from the v1 config have ts = 0, and a stripe is a claim: better no claim at all than one
 * saying an unknown date is the first of January 1970.
 */
int dayBandOf(long ts, long[] boundaries) {
    if (ts <= 0L) return -1
    for (int i = 0; i < boundaries.length; i++) {
        if (ts >= boundaries[i]) return i
    }
    return -1
}

/** Blocks, in the order they are ranked and drawn. */
HEAT_BLOCK_RECENT = 0
HEAT_BLOCK_WEEK = 1
HEAT_BLOCK_AGED = 2
HEAT_BLOCK_COUNT = 3

/**
 * Which ramp a day band lands on. Only two dates are named in the drawing -- today and
 * yesterday -- and everything else within the window shares the middle one, so an entry moves
 * between ramps twice in its life instead of falling off a cliff once.
 */
int heatBlockOf(int dayBand) {
    if (dayBand < 0) return HEAT_BLOCK_AGED
    if (dayBand <= 1) return HEAT_BLOCK_RECENT
    return HEAT_BLOCK_WEEK
}

/** The ramp of a block, at a given heat. */
Color stripeColor(int block, double heat, Color background) {
    if (block == HEAT_BLOCK_RECENT) return recentColor(heat, background)
    if (block == HEAT_BLOCK_WEEK) return weekColor(heat, background)
    return agedColor(heat, background)
}

/**
 * One hue, blue: the week block. Cool where red is hot, so the two never trade places at a
 * glance however bright either happens to be -- which is the whole reason a third block can
 * be added at all without the stripe becoming a puzzle.
 *
 * Both ends stay clearly BLUE and, like the red, both stay on the same side of the label's
 * contrast threshold (UITools.isLight uses 0.299R + 0.587G + 0.114B > 160): every shade here
 * is below it, so the shortcut label is white from end to end, exactly as it is on the red.
 */
Color weekColor(double heat, Color background) {
    boolean light = luminanceOf(background) > 0.5d
    // Same reasoning as the red ramp: on a dark theme "faded" means deep and dim, never pale,
    // or the oldest entry of the block would draw the eye first.
    Color faded = light ? new Color(122, 152, 206) : new Color(42, 74, 124)
    Color full = light ? new Color(26, 86, 192) : new Color(74, 150, 255)
    return blendColors(faded, full, Math.max(0.0d, Math.min(1.0d, heat)))
}

/**
 * The older block: no hue at all, so that colour itself means "current". Grey is what makes
 * the red mean something -- forty quiet entries are what let three loud ones be found.
 *
 * The ramp stays on ONE side of the label's contrast threshold on purpose. Running it across
 * the middle would flip UITools.getTextColorForBackground somewhere along the list, and the
 * shortcut labels would change from dark to light partway down a column for no reason the
 * reader can see. So: pale greys on a light theme, deep greys on a dark one, and the label
 * keeps one colour throughout.
 */
Color agedColor(double heat, Color background) {
    boolean light = luminanceOf(background) > 0.5d
    Color faded = light ? new Color(231, 231, 231) : new Color(66, 66, 66)
    Color full = light ? new Color(188, 188, 188) : new Color(122, 122, 122)
    return blendColors(faded, full, Math.max(0.0d, Math.min(1.0d, heat)))
}

/** Plain-language age for the tooltip, so the colour has something to be checked against. */
String ageLabel(NavItem item, long now) {
    if (!item?.ts) return 'no timestamp'
    long minutes = Math.max(0L, (long) ((now - item.ts) / 60000L))
    if (minutes < 2) return 'just now'
    if (minutes < 60) return minutes + ' min ago'
    long hours = (long) (minutes / 60)
    if (hours < 24) return hours + (hours == 1 ? ' hour ago' : ' hours ago')
    long days = (long) (hours / 24)
    if (days < 14) return days + (days == 1 ? ' day ago' : ' days ago')
    long weeks = (long) (days / 7)
    if (weeks < 9) return weeks + ' weeks ago'
    return (long) (days / 30) + ' months ago'
}

void gatherDepths(TreeNode node, int depth, Map<TreeNode, Integer> out, int[] deepest) {
    out.put(node, depth)
    if (depth > deepest[0]) deepest[0] = depth
    node.children.each { gatherDepths(it, depth + 1, out, deepest) }
}

/** Every node standing for a list entry, parents included -- in the minimap they are boxes. */
void gatherItemNodes(TreeNode node, List<TreeNode> out) {
    if (node.item != null) out.add(node)
    node.children.each { gatherItemNodes(it, out) }
}

int leafCountOf(TreeNode node) {
    if (node.children.isEmpty()) return node.item != null ? 1 : 0
    int total = 0
    node.children.each { total += leafCountOf(it) }
    return total
}

/**
 * Splits the root's branches into a left and a right half, balanced by leaf count. Halving
 * the height the drawing needs is what buys the rows their space: on one side only, fifty
 * entries drop to a 9pt font in a column using a fifth of a wide window.
 *
 * It is a concession all the same -- one direction reads better -- so `allowSplit` is false
 * whenever a single side still fits comfortably, and then everything grows to the right.
 *
 * Branches stay whole: splitting one across the two sides would put siblings at opposite
 * ends of the window, which is exactly the confusion this view exists to undo.
 */
List splitMinimapSides(TreeNode root, boolean allowSplit) {
    List<TreeNode> right = new ArrayList<TreeNode>()
    List<TreeNode> left = new ArrayList<TreeNode>()
    if (!allowSplit) return [left, new ArrayList<TreeNode>(root.children)]
    int total = 0
    root.children.each { total += leafCountOf(it) }
    int taken = 0
    root.children.each { TreeNode child ->
        if (taken * 2 < total) { right.add(child); taken += leafCountOf(child) }
        else left.add(child)
    }
    // One fat first branch can swallow the whole budget and leave the other side empty.
    if (left.isEmpty() && right.size() > 1) left.add(right.remove(right.size() - 1))
    return [left, right]
}

void markMinimapSide(TreeNode node, int side, Map<TreeNode, Integer> out) {
    out.put(node, side)
    node.children.each { markMinimapSide(it, side, out) }
}

/** Places the leaves of one side on consecutive rows, top down. */
void assignMinimapRows(TreeNode node, int[] counter, int rowHeight) {
    if (node.children.isEmpty()) {
        if (node.item != null) {
            node.y = (int) Math.round(MINIMAP_PAD + (counter[0] + 0.5d) * rowHeight)
            counter[0]++
        }
        return
    }
    node.children.each { assignMinimapRows(it, counter, rowHeight) }
}

/**
 * Places one branch and everything under it, box after box: a child starts where its parent
 * ends, instead of in a column shared with every other node of the same depth.
 *
 * That grid was where most of the width went. It gives every depth the same slice, so a
 * two-level branch is charged for the nine-level one next to it, and a node whose parent has
 * a short title still starts far to the right. Measured on the real store: 92% of the titles
 * were being truncated, "para as férias" among them, at ~70px per column.
 *
 * Each box may take GREED times its fair share of what is left along its own path, capped by
 * what is actually left. Fair share alone is too pessimistic -- it assumes every level below
 * wants as much, and most want far less.
 */
void placeMinimapBranch(TreeNode node, int offset, int space, Map<TreeNode, Integer> wants,
                        Map<TreeNode, Integer> widths, Map<TreeNode, Integer> offsets) {
    int available = Math.max(MINIMAP_MIN_BOX, space - offset)
    int levels = subtreeHeight(node) + 1
    int cap = (int) Math.round(available / (double) levels * MINIMAP_GREED)
    int width = Math.min((int) wants.get(node), Math.max(MINIMAP_MIN_BOX, Math.min(cap, available)))
    widths.put(node, width)
    offsets.put(node, offset)
    int next = offset + width + MINIMAP_COLUMN_GAP
    node.children.each { placeMinimapBranch(it, next, space, wants, widths, offsets) }
}

/**
 * Lays the context tree out as a mind map: the root in the middle, branches to both sides,
 * a box carrying the title for every entry, and an anonymous dot wherever an ancestor only
 * joins two branches.
 *
 * Everything is measured in natural coordinates and one scale plus an offset is returned, so
 * the caller can fit the whole drawing and centre it -- there is never anything to pan to.
 * Height is given up first (rows squeeze before anything shrinks), because a shorter row
 * costs nothing while a smaller scale costs legibility everywhere at once.
 *
 * Returns [boxes: [...], links: [...], scale, offsetX, offsetY, font].
 */
Map layoutMinimap(TreeNode root, int width, int height, Font baseFont, Closure metricsOf,
                  Map colors, int digitOffset) {
    // Every field is filled in here, so an early return still returns a complete shape --
    // a caller reading heatWidth off an empty layout should not have to guard against null.
    Map out = [boxes: [], links: [], scale: 1.0d, offsetX: 0, offsetY: 0,
               font: baseFont, labelFont: baseFont, heatWidth: MINIMAP_HEAT_MIN_WIDTH]
    if (root == null) return out
    List<TreeNode> allItems = new ArrayList<TreeNode>()
    gatherItemNodes(root, allItems)
    if (allItems.isEmpty()) return out

    // Rows are counted in LEAVES, not entries: a parent is centred on its children and costs
    // no row of its own, exactly as in the map this is drawn from.
    int leafTotal = Math.max(1, leafCountOf(root))
    int usableHeight = Math.max(1, height - MINIMAP_PAD * 2)
    // One direction reads better, so the tree only spills to the left once staying on one
    // side would squeeze the rows past comfort.
    boolean allowSplit = (usableHeight / (double) leafTotal) < MINIMAP_SPLIT_ROW_HEIGHT
    def (List<TreeNode> leftBranches, List<TreeNode> rightBranches) = splitMinimapSides(root, allowSplit)
    int leftLeaves = 0, rightLeaves = 0
    leftBranches.each { leftLeaves += leafCountOf(it) }
    rightBranches.each { rightLeaves += leafCountOf(it) }
    int rows = Math.max(1, Math.max(leftLeaves, rightLeaves))

    // Rows first: squeezing them is free until they get too thin to hold a readable font.
    int rowHeight = (int) Math.max(MINIMAP_MIN_ROW_HEIGHT,
                                   Math.min(MINIMAP_ROW_HEIGHT, usableHeight / (double) rows))
    // How much the rows alone failed to save, so the columns know how much natural room they
    // will really have. The final scale is settled at the bottom, from the drawing's extent.
    double provisional = Math.max(MINIMAP_MIN_SCALE,
                                  Math.min(1.0d, usableHeight / (double) (rows * rowHeight)))
    double naturalWidth = width / provisional

    float fontSize = (float) Math.max(MINIMAP_MIN_FONT_SIZE, Math.min(MINIMAP_FONT_SIZE, rowHeight - 9))
    Font font = baseFont.deriveFont(Font.PLAIN, fontSize)
    FontMetrics metrics = (FontMetrics) metricsOf.call(font)
    out.font = font

    // The stripe has to fit the widest label it can ever hold, and the same width for all of
    // them: stripes of differing widths would stop lining up, and lining up is what makes the
    // temperatures comparable at a glance. W is the widest of 1..9 0 A..Z.
    Font labelFont = baseFont.deriveFont(Font.BOLD, (float) Math.max(9.0f, fontSize * 0.82f))
    FontMetrics labelMetrics = (FontMetrics) metricsOf.call(labelFont)
    int heatWidth = Math.max(MINIMAP_HEAT_MIN_WIDTH, labelMetrics.stringWidth('W') + 8)
    out.labelFont = labelFont
    out.heatWidth = heatWidth

    Map<TreeNode, Integer> depths = new HashMap<TreeNode, Integer>()
    Map<TreeNode, Integer> sides = new HashMap<TreeNode, Integer>()
    int[] deepestLeft = [0] as int[]
    int[] deepestRight = [0] as int[]
    depths.put(root, 0)
    sides.put(root, 0)
    leftBranches.each { gatherDepths(it, 1, depths, deepestLeft); markMinimapSide(it, -1, sides) }
    rightBranches.each { gatherDepths(it, 1, depths, deepestRight); markMinimapSide(it, 1, sides) }

    // What every box would like to be, with its title untouched.
    Map<TreeNode, Integer> wants = new HashMap<TreeNode, Integer>()
    depths.each { TreeNode node, Integer depth ->
        if (node.item == null) {
            wants.put(node, MINIMAP_JUNCTION_DOT)
        } else {
            String title = (node.item.title ?: '(untitled)').replaceAll('\\s+', ' ').trim()
            wants.put(node, metrics.stringWidth(title) + MINIMAP_BOX_PAD_X * 2 + heatWidth)
        }
    }

    // The root may well be an entry itself -- when the whole branch is in the list, it is the
    // map's own root node -- in which case it is a box like any other.
    String rootText = null
    int rootWidth = MINIMAP_JUNCTION_DOT
    if (root.item != null) {
        // The root is measured before the branches, so it gets a fixed share -- but only
        // because the branches need the rest. With no branches there is nothing to reserve
        // for, and capping it anyway truncates the one box on an otherwise empty canvas.
        double rootShare = root.children.isEmpty() ? 1.0d : 0.28d
        rootText = fitToWidth((root.item.title ?: '(untitled)').replaceAll('\\s+', ' ').trim(),
                              metrics, (int) Math.max(60, naturalWidth * rootShare - MINIMAP_PAD * 2))
        rootWidth = metrics.stringWidth(rootText) + MINIMAP_BOX_PAD_X * 2 + heatWidth
    }

    // Split the room between the sides by how deep each one goes -- NOT in half. Halving it
    // hands an empty left side half the window whenever the tree grows in one direction only,
    // which is the common case now that splitting is a last resort.
    int usableWidth = (int) Math.round(naturalWidth) - MINIMAP_PAD * 2 - rootWidth -
                      MINIMAP_COLUMN_GAP * 2
    int totalDepth = deepestLeft[0] + deepestRight[0]
    int rightSpace = (totalDepth == 0) ? usableWidth
            : (int) Math.round(usableWidth * deepestRight[0] / (double) totalDepth)
    int leftSpace = usableWidth - rightSpace

    Map<TreeNode, Integer> widths = new HashMap<TreeNode, Integer>()
    Map<TreeNode, Integer> offsets = new HashMap<TreeNode, Integer>()
    rightBranches.each { placeMinimapBranch(it, 0, Math.max(MINIMAP_MIN_BOX, rightSpace), wants, widths, offsets) }
    leftBranches.each { placeMinimapBranch(it, 0, Math.max(MINIMAP_MIN_BOX, leftSpace), wants, widths, offsets) }

    // Laid out relative to the root, then shifted so the leftmost box lands on the padding.
    int leftReach = 0
    leftBranches.each { TreeNode branch ->
        markMinimapSide(branch, -1, sides)     // no-op, keeps the intent readable
    }
    offsets.each { TreeNode node, Integer offset ->
        if ((sides.get(node) ?: 1) < 0) {
            int reach = offset + widths.get(node)
            if (reach > leftReach) leftReach = reach
        }
    }
    int rootX = MINIMAP_PAD + (leftBranches.isEmpty() ? 0 : leftReach + MINIMAP_COLUMN_GAP)

    // Each side is numbered from the top independently: that is what halves the height.
    int[] leftCounter = [0] as int[]
    int[] rightCounter = [0] as int[]
    leftBranches.each { assignMinimapRows(it, leftCounter, rowHeight) }
    rightBranches.each { assignMinimapRows(it, rightCounter, rowHeight) }
    leftBranches.each { assignTreeY(it) }
    rightBranches.each { assignTreeY(it) }

    int boxHeight = (int) Math.max(metrics.getHeight() + 4, Math.min(rowHeight - 3, metrics.getHeight() + 10))
    Map<TreeNode, Map> placed = new HashMap<TreeNode, Map>()
    long now = System.currentTimeMillis()
    long[] dayStarts = dayBoundaries()
    // Which ramp each entry is on, and where it sits WITHIN that ramp. Walked in list order
    // -- the list is the chronology -- so the two counters come out as the rank inside each
    // block. Counting per block rather than reusing listIndex is what keeps both ramps fully
    // used: with three recent entries out of forty, listIndex would squeeze all three into
    // the first tenth of the red.
    Map<TreeNode, Integer> heatBlock = new HashMap<TreeNode, Integer>()
    Map<TreeNode, Integer> blockRank = new HashMap<TreeNode, Integer>()
    int[] blockCounts = new int[HEAT_BLOCK_COUNT]
    allItems.sort(false) { it.listIndex }.each { TreeNode entry ->
        int block = heatBlockOf(dayBandOf(entry.item?.ts ?: 0L, dayStarts))
        heatBlock.put(entry, block)
        blockRank.put(entry, blockCounts[block]++)
    }

    // The root sits at the middle of everything hanging off it, not at the middle of one side.
    // Settled before the boxes are cut, so its own box can be placed with the rest.
    int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE
    (leftBranches + rightBranches).each { TreeNode branch ->
        if (branch.y < top) top = branch.y
        if (branch.y > bottom) bottom = branch.y
    }
    if (top <= bottom) root.y = (int) Math.round((top + bottom) / 2.0d)

    depths.each { TreeNode node, Integer depth ->
        int side = sides.get(node) ?: 0
        Map box
        // Distance from the root's own edge, in the direction of this side.
        Closure edgeOf = { int ownWidth ->
            if (depth == 0) return rootX
            int offset = (int) (offsets.get(node) ?: 0)
            return (side > 0) ? (rootX + rootWidth + MINIMAP_COLUMN_GAP + offset)
                              : (rootX - MINIMAP_COLUMN_GAP - offset - ownWidth)
        }

        if (node.item == null) {
            box = [x: edgeOf(MINIMAP_JUNCTION_DOT), y: node.y - (int) (MINIMAP_JUNCTION_DOT / 2),
                   side: side, width: MINIMAP_JUNCTION_DOT, height: MINIMAP_JUNCTION_DOT,
                   junction: true]
        } else {
            // By age, not by where the box landed: listIndex is the position in the recent
            // list. Labelling by drawing order would make the key for an entry change every
            // time the tree reshaped around it.
            String digit = shortcutFor(node.listIndex + digitOffset)
            String text
            int boxWidth
            if (depth == 0) {
                text = rootText
                boxWidth = rootWidth
            } else {
                String title = (node.item.title ?: '(untitled)').replaceAll('\\s+', ' ').trim()
                int granted = (int) (widths.get(node) ?: MINIMAP_MIN_BOX)
                int room = granted - MINIMAP_BOX_PAD_X * 2 - heatWidth
                text = fitToWidth(title ?: '(untitled)', metrics, Math.max(12, room))
                boxWidth = metrics.stringWidth(text) + MINIMAP_BOX_PAD_X * 2 + heatWidth
            }
            int bx = edgeOf(boxWidth)
            def (Color boxBackground, Color boxForeground) = rowColorsOf(node.item, colors.background)
            int block = (int) heatBlock.get(node)
            double heat = heatOfRank((int) blockRank.get(node), blockCounts[block])
            Color stripe = stripeColor(block, heat, colors.background)
            int dayBand = dayBandOf(node.item.ts, dayStarts)
            box = [x: bx, y: node.y - (int) (boxHeight / 2), width: boxWidth, height: boxHeight,
                   junction: false, side: side, text: text, digit: digit, key: node.item.key(),
                   background: boxBackground, foreground: boxForeground,
                   heat: stripe,
                   // Read against the stripe, not the box: the stripe runs the whole ramp from
                   // washed out to full red, so a single fixed colour would lose one end of it.
                   labelColor: UITools.getTextColorForBackground(stripe),
                   // The calendar day is spelled out beside the age, because the two can
                   // honestly disagree: thirty hours ago is "1 day ago" and the day before
                   // yesterday at the same time. Without this the stripe would look wrong on
                   // exactly the entries where it is right. The blue block names itself too,
                   // since "3 days ago" alone does not say which side of the window it is on.
                   tooltip: ((node.item.title ?: '(untitled)') + '   —   ' + ageLabel(node.item, now) +
                             (dayBand == 0 ? ' · today'
                                           : (dayBand == 1 ? ' · yesterday'
                                                           : (block == HEAT_BLOCK_WEEK ? ' · this week' : '')))).toString()]
        }
        placed.put(node, box)
        out.boxes.add(box)
    }

    // A link leaves the side of the parent that faces its child and arrives at the child's
    // facing side, so a line never runs underneath a title.
    depths.each { TreeNode node, Integer depth ->
        Map parentBox = placed.get(node)
        node.children.each { TreeNode child ->
            Map childBox = placed.get(child)
            int childSide = sides.get(child) ?: 1
            int x1 = (childSide > 0) ? (int) parentBox.x + (int) parentBox.width : (int) parentBox.x
            int x2 = (childSide > 0) ? (int) childBox.x : (int) childBox.x + (int) childBox.width
            out.links.add([x1: x1, y1: node.y, x2: x2, y2: child.y])
        }
    }

    // Only now is the drawing's real extent known -- the boxes are as wide as their titles,
    // not as wide as the column they were offered. Fit to THAT, in both directions, and the
    // view uses the window instead of huddling in a corner of it. Scaling up is as much the
    // point as scaling down: a dozen entries on a wide monitor would otherwise be a stamp.
    // Both ends matter, not just the far one: a left side of short titles leaves dead space
    // before the first box, and measuring from zero would push the whole drawing off centre.
    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE
    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE
    out.boxes.each { box ->
        int left = (int) box.x, top2 = (int) box.y
        int right = left + (int) box.width, bottom2 = top2 + (int) box.height
        if (left < minX) minX = left
        if (top2 < minY) minY = top2
        if (right > maxX) maxX = right
        if (bottom2 > maxY) maxY = bottom2
    }
    int extentX = (maxX - minX) + MINIMAP_PAD * 2
    int extentY = (maxY - minY) + MINIMAP_PAD * 2
    double scale = Math.min(width / (double) extentX, height / (double) extentY)
    scale = Math.max(MINIMAP_MIN_SCALE, Math.min(MINIMAP_MAX_SCALE, scale))
    out.scale = scale
    // The offset both centres the drawing and cancels its own origin, so the paint and the
    // hit test share one transform: translate(offset), scale, then draw at natural coords.
    out.offsetX = (int) Math.round((width - extentX * scale) / 2.0d - (minX - MINIMAP_PAD) * scale)
    out.offsetY = (int) Math.round((height - extentY * scale) / 2.0d - (minY - MINIMAP_PAD) * scale)

    return out
}

// ---------------------------------------------------------------- ui

/**
 * The colour Freeplane marks its own selected node with -- Preferences > Defaults > Selection
 * Colors > Selected node bubble color. Worth borrowing: the minimap is a picture of the map,
 * so selection ought to look the same in both, and it follows the user's setting for free.
 *
 * Read through ResourceController, the same call SelectionPainter makes, because the public
 * scripting route (`config`) is not in the binding of every host this script runs in -- the
 * MCP host's binding is minimal and `config.getProperty` throws there.
 */
Color nodeSelectionColor() {
    try {
        Color found = ResourceController.getResourceController()
                .getColorProperty('standardselectednoderectanglecolor')
        if (found != null) return found
    } catch (Exception ignored) {
    }
    return UIManager.getColor('List.selectionBackground') ?: Color.BLUE
}

/** Colours pulled from the installed LAF, so the window matches light/dark themes. */
Map themeColors() {
    Color bg = UIManager.getColor('List.background') ?: Color.WHITE
    [background        : bg,
     foreground        : UITools.getTextColorForBackground(bg),
     selectionBackground: UIManager.getColor('List.selectionBackground') ?: Color.BLUE,
     selectionForeground: UIManager.getColor('List.selectionForeground') ?: Color.WHITE,
     nodeSelection     : nodeSelectionColor(),
     separator         : UIManager.getColor('Separator.foreground') ?: Color.LIGHT_GRAY]
}

/**
 * The depth rail: a fixed strip carrying tick marks for every level and one filled dot at
 * the entry's own depth. Everything it needs is computed here and captured, so the paint
 * method touches only plain values.
 */
/** Every symbol that can end a label: the digits, the plain letters, and the prefix itself. */
String shortcutAlphabet() {
    return SHORTCUT_DIGITS + SHORTCUT_LETTERS + SHORTCUT_PREFIX
}

/**
 * The shortcut label for an entry, BY ITS AGE: 0 for the root you are in, 1..9 for the nine
 * before it, then the single letters, then two-key labels starting with the prefix.
 *
 * The index is the position in the recent list, never the position in the drawing. In the
 * minimap the two disagree completely -- structure and chronology are unrelated -- and time
 * is the one you can predict without looking: "two roots ago" is 2, wherever it sits.
 *
 * The doubling only begins once every single key is spent, so the common entries keep their
 * one keystroke and only the tail costs two. Anything past all of them is still reachable
 * with Ctrl+Tab.
 */
String shortcutFor(int index) {
    if (index < 0) return null
    if (index < SHORTCUT_DIGITS.length()) return SHORTCUT_DIGITS.substring(index, index + 1)
    int letter = index - SHORTCUT_DIGITS.length()
    if (letter < SHORTCUT_LETTERS.length()) return SHORTCUT_LETTERS.substring(letter, letter + 1)
    int second = letter - SHORTCUT_LETTERS.length()
    String alphabet = shortcutAlphabet()
    if (second >= alphabet.length()) return null
    return SHORTCUT_PREFIX + alphabet.substring(second, second + 1)
}

/** Inverse of shortcutFor: which row a typed label points at, or -1. */
int shortcutIndexOf(String label) {
    if (!label) return -1
    int total = SHORTCUT_DIGITS.length() + SHORTCUT_LETTERS.length() + shortcutAlphabet().length()
    for (int i = 0; i < total; i++) {
        if (label == shortcutFor(i)) return i
    }
    return -1
}


/**
 * Breadcrumbs for the chain above the current view root, so climbing to an ancestor is one
 * click - including ancestors that were never a root before, which the recent list by
 * definition cannot offer. setViewRoot() accepts an ancestor of the current root (verified:
 * it took the view from level 5 back to level 3), so activating a crumb is a plain jump in.
 */
/** Shortens a string to fit a pixel budget, with an ellipsis. */
String fitToWidth(String text, FontMetrics metrics, int maxPixels) {
    if (metrics.stringWidth(text) <= maxPixels) return text
    int ellipsis = metrics.stringWidth('…')
    int chars = text.length()
    while (chars > 1 && metrics.stringWidth(text.substring(0, chars)) + ellipsis > maxPixels) chars--
    return text.substring(0, Math.max(1, chars)) + '…'
}

/**
 * Hands out a pixel budget across items: everyone who fits within an equal share keeps its
 * full width, and what they leave unused is redistributed to the ones that do not fit. A
 * flat per-item limit would truncate a long crumb while a short one wastes its half.
 */
List<Integer> shareWidths(List<Integer> wanted, int budget) {
    int remaining = budget
    List<Integer> granted = new ArrayList<Integer>(Collections.nCopies(wanted.size(), 0))
    List<Integer> pending = (0..<wanted.size()).toList()
    while (!pending.isEmpty()) {
        int share = (int) (remaining / pending.size())
        List<Integer> fitting = pending.findAll { wanted[it] <= share }
        if (fitting.isEmpty()) {
            pending.each { granted[it] = share }
            break
        }
        fitting.each { granted[it] = wanted[it]; remaining -= wanted[it] }
        pending = pending.findAll { !fitting.contains(it) }
    }
    return granted
}

Map buildBreadcrumbs(Map colors, int availableWidth, Closure onPick) {
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1))
    bar.opaque = false
    bar.border = new EmptyBorder(0, 8, 4, 8)
    Map result = [panel: bar, targets: [], labels: [], highlight: { int i -> }]

    def viewRoot = c.viewRoot
    if (viewRoot == null) return result

    List chain = []
    def walker = viewRoot
    while (walker != null) {
        chain.add(0, walker)
        walker = walker.parent
    }
    if (chain.size() < 2) return result   // already at the map root: nothing to climb to

    // Only the digits cap how many crumbs are worth showing; the width is handled by
    // sharing it out below, so a wide window keeps the whole chain readable.
    List shown = chain
    boolean elided = false
    if (chain.size() > 11) {
        shown = [chain[0]] + chain[(chain.size() - 10)..-1]
        elided = true
    }

    // Digits run right to left, so 1 is the ancestor you are most likely to want: the one
    // just above where you are. The list continues the same numbering after these.
    List targets = shown[0..-2].reverse()
    List labels = []

    // Work out how much room each crumb gets, from the width the window will actually have.
    Font crumbFont = bar.getFont().deriveFont(Font.PLAIN, CRUMB_FONT_SIZE)
    Font currentFont = bar.getFont().deriveFont(Font.BOLD, CRUMB_FONT_SIZE)
    FontMetrics metrics = bar.getFontMetrics(currentFont)
    // No shortcut labels here: every label now names a position in the recent list, so that
    // 0 always means "where I am" and the numbering never shifts with the crumb count.
    // The crumbs stay clickable.
    List<String> fullTexts = shown.collect { crumbNode ->
        String body = (crumbNode.plainText ?: crumbNode.text ?: '(untitled)').replaceAll('\\s+', ' ').trim()
        return body ?: '(untitled)'
    }
    int separatorsWidth = metrics.stringWidth(' › ') * Math.max(0, shown.size() - 1) + (elided ? metrics.stringWidth(' … ') : 0)
    int budget = Math.max(shown.size() * 40, availableWidth - separatorsWidth - 40)
    List<Integer> wanted = fullTexts.collect { metrics.stringWidth(it) + 8 }
    List<Integer> granted = shareWidths(wanted, budget)

    shown.eachWithIndex { crumbNode, int i ->
        if (i > 0) {
            JLabel sep = new JLabel((elided && i == 1) ? ' … › ' : ' › ')
            sep.foreground = new Color(colors.foreground.getRed(), colors.foreground.getGreen(), colors.foreground.getBlue(), 120)
            bar.add(sep)
        }
        boolean isCurrent = crumbNode.id == viewRoot.id
        int digitIndex = targets.indexOf(crumbNode)
        JLabel crumb = new JLabel(fitToWidth(fullTexts[i], metrics, granted[i]))
        crumb.font = isCurrent ? currentFont : crumbFont
        Color own = parseColor(textColorOf(crumbNode))
        crumb.foreground = (own != null && contrastRatio(own, colors.background) >= 3.0d) ? own : colors.foreground
        crumb.toolTipText = crumbNode.plainText ?: crumbNode.text
        crumb.border = new EmptyBorder(1, 3, 1, 3)
        if (!isCurrent) {
            crumb.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // MOUSE_PRESSED, not MOUSE_CLICKED: a click that moves a pixel never produces
            // MOUSE_CLICKED, and the action would silently not run.
            crumb.addMouseListener(new MouseAdapter() {
                @Override
                void mousePressed(MouseEvent e) { onPick(crumbNode) }
            })
            labels.add([node: crumbNode, label: crumb, baseForeground: crumb.foreground])
        }
        bar.add(crumb)
    }

    // Reorder to match `targets`: the labels were collected left to right, while the digits
    // run right to left. Leaving them out of step made key 1 highlight the FIRST crumb while
    // committing the LAST one - the highlight and the action would disagree.
    labels = targets.collect { t -> labels.find { entry -> entry.node.id == t.id } }.findAll { it != null }

    // Selecting a crumb by its digit has to look like selection, or the number key would
    // seem to do nothing until Ctrl is released.
    Closure highlight = { int selected ->
        labels.eachWithIndex { entry, int i ->
            JLabel label = (JLabel) entry.label
            boolean on = (i == selected)
            label.opaque = on
            label.background = on ? colors.selectionBackground : colors.background
            label.foreground = on ? colors.selectionForeground : entry.baseForeground
        }
        bar.repaint()
    }

    result.targets = targets
    result.labels = labels
    result.highlight = highlight
    return result
}





/**
 * The minimap view: the recent roots drawn as a mind map of their own. Nothing here says
 * anything about when a root was visited -- that is the whole point of it, and what the list
 * view is still there for.
 */
JComponent buildMinimapView(Map colors, Closure rootProvider, Map minimapState,
                            Closure selectedKeyProvider, Closure digitOffsetProvider,
                            Closure pendingPrefixProvider, Closure onPick, Closure onActivate) {
    final Color background = colors.background
    final Color foreground = colors.foreground
    final Color linkColor = new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 105)
    final Color junctionColor = new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 145)
    // Freeplane's own selection colour, not the list widget's blue: this view is a picture of
    // the map, so being selected should look the same here as it does there.
    final Color selectionColor = (Color) (colors.nodeSelection ?: colors.selectionBackground)
    final JComponent[] holder = new JComponent[1]
    // 0..1, where the expanding ring currently is. Driven by a timer, read by the painter.
    final double[] pulse = [0.0d] as double[]

    // Laying out is expensive -- measured at 18-35 ms for 10 to 50 entries -- and paintComponent
    // used to redo it every single time. That is paid on every keystroke that moves the
    // selection, and it makes animation impossible outright: at 25 fps it would be most of a
    // CPU core spent recomputing a layout that cannot have changed between frames.
    final Map layoutCache = [key: null, laid: null]
    final Closure layouter = { int w, int h ->
        JComponent self = holder[0]
        TreeNode root = (TreeNode) rootProvider.call()
        int offset = (int) digitOffsetProvider.call()
        // Identity of the tree, not its contents: showItems builds a NEW tree whenever the
        // entries change, so a different object is exactly the signal to lay out again.
        String key = w + 'x' + h + '#' + System.identityHashCode(root) + '#' + offset
        if (key == layoutCache.key && layoutCache.laid != null) return layoutCache.laid
        Font base = self.getFont() ?: UIManager.getFont('Label.font') ?: new Font('SansSerif', Font.PLAIN, 13)
        Map laid = layoutMinimap(root, w, h, base,
                                 { Font f -> self.getFontMetrics(f) }, colors, offset)
        layoutCache.key = key
        layoutCache.laid = laid
        minimapState.laid = laid
        return laid
    }

    JComponent view = new JComponent() {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create()
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // A bare JComponent has no UI delegate, so setOpaque alone paints no background.
            g2.setColor(background)
            g2.fillRect(0, 0, getWidth(), getHeight())

            Map laid = (Map) layouter.call(getWidth(), getHeight())
            double scale = (double) laid.scale
            g2.translate((int) laid.offsetX, (int) laid.offsetY)
            g2.scale(scale, scale)
            g2.setFont((Font) laid.font)
            FontMetrics metrics = g2.getFontMetrics()
            String selectedKey = (String) selectedKeyProvider.call()
            int stripeWidth = (int) (laid.heatWidth ?: MINIMAP_HEAT_MIN_WIDTH)
            Font labelFont = (Font) (laid.labelFont ?: laid.font)

            g2.setStroke(new BasicStroke(1.5f))
            g2.setColor(linkColor)
            ((List) laid.links).each { link ->
                double x1 = (double) ((int) link.x1)
                double y1 = (double) ((int) link.y1)
                double x2 = (double) ((int) link.x2)
                double y2 = (double) ((int) link.y2)
                double mid = (x2 - x1) / 2.0d
                g2.draw(new CubicCurve2D.Double(x1, y1, x1 + mid, y1, x2 - mid, y2, x2, y2))
            }

            String pending = (String) pendingPrefixProvider.call()
            Composite fullyOpaque = g2.getComposite()
            Map[] selectedBox = new Map[1]
            ((List) laid.boxes).each { box ->
                int bx = (int) box.x, by = (int) box.y, bw = (int) box.width, bh = (int) box.height
                // Halfway through a two-key label, everything it cannot lead to steps back.
                // Without this the first keystroke looks like it did nothing at all.
                boolean reachable = (pending == null) ||
                        (box.digit != null && ((String) box.digit).startsWith(pending))
                g2.setComposite(reachable ? fullyOpaque
                        : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f))
                if (box.junction) {
                    // The ancestor that only joins branches: present, unnamed, deliberately.
                    g2.setColor(junctionColor)
                    g2.fillOval(bx, by, bw, bh)
                    return
                }
                boolean selected = (box.key == selectedKey)
                g2.setColor((Color) box.background)
                g2.fillRoundRect(bx, by, bw, bh, 8, 8)
                if (box.heat != null) {
                    // Clipped to the box's own rounded shape, or the stripe would square off
                    // the two left corners and the box would look like a different widget.
                    Shape previousClip = g2.getClip()
                    g2.clip(new RoundRectangle2D.Double(bx, by, bw, bh, 8, 8))
                    g2.setColor((Color) box.heat)
                    g2.fillRect(bx, by, stripeWidth, bh)
                    g2.setClip(previousClip)
                    if (box.digit) {
                        // Centred in the stripe: it belongs to the box, not to the title, and
                        // sitting inside the colour is what says so without a separator.
                        g2.setFont(labelFont)
                        FontMetrics labelMetrics = g2.getFontMetrics()
                        String label = (String) box.digit
                        int labelX = bx + (int) ((stripeWidth - labelMetrics.stringWidth(label)) / 2)
                        int labelY = by + (int) ((bh - labelMetrics.getHeight()) / 2) + labelMetrics.getAscent()
                        g2.setColor((Color) box.labelColor)
                        g2.drawString(label, labelX, labelY)
                        g2.setFont((Font) laid.font)
                    }
                }
                if (selected) {
                    // Kept for the pulse, which is drawn last so nothing paints over it.
                    selectedBox[0] = box
                    g2.setColor(selectionColor)
                    g2.setStroke(new BasicStroke(3.5f))
                    g2.drawRoundRect(bx - 1, by - 1, bw + 2, bh + 2, 9, 9)
                } else {
                    g2.setColor(new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 70))
                    g2.setStroke(new BasicStroke(1.0f))
                    g2.drawRoundRect(bx, by, bw, bh, 8, 8)
                }
                int textY = by + (int) ((bh - metrics.getHeight()) / 2) + metrics.getAscent()
                int textX = bx + stripeWidth + MINIMAP_BOX_PAD_X
                g2.setColor((Color) box.foreground)
                g2.drawString((String) box.text, textX, textY)
            }
            g2.setComposite(fullyOpaque)

            // The pulse, last of all, so no neighbouring box can paint over it. Two rings half
            // a cycle apart, so there is always one in flight and the motion never stalls.
            Map chosen = selectedBox[0]
            if (chosen != null) {
                int bx = (int) chosen.x, by = (int) chosen.y
                int bw = (int) chosen.width, bh = (int) chosen.height
                [0.0d, 0.5d].each { double offset ->
                    double phase = (pulse[0] + offset) % 1.0d
                    int grow = (int) Math.round(phase * MINIMAP_PULSE_REACH)
                    int alpha = (int) Math.round(190 * (1.0d - phase) * (1.0d - phase))
                    if (alpha < 6) return
                    g2.setColor(new Color(selectionColor.getRed(), selectionColor.getGreen(),
                                          selectionColor.getBlue(), alpha))
                    g2.setStroke(new BasicStroke(2.5f))
                    g2.drawRoundRect(bx - 2 - grow, by - 2 - grow,
                                     bw + 4 + grow * 2, bh + 4 + grow * 2,
                                     10 + grow, 10 + grow)
                }
            }
            g2.dispose()
        }

        @Override
        String getToolTipText(MouseEvent event) {
            Map box = (Map) minimapState.hit?.call(event.getPoint())
            return box == null ? null : (String) box.tooltip
        }
    }
    holder[0] = view
    view.opaque = true
    view.background = background
    view.focusable = true
    // Ctrl+Tab and Ctrl+Shift+Tab are focus TRAVERSAL keys by default (measured: a focusable
    // JComponent lists "ctrl pressed TAB" among its forward keys). The focus manager eats
    // them before any key binding is consulted, so the Alt+Tab gesture silently did nothing
    // while this view had the focus.
    view.focusTraversalKeysEnabled = false
    view.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.emptySet())
    view.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Collections.emptySet())
    view.setToolTipText('')       // registers the component with the ToolTipManager

    // The pulse runs only while the view is actually on screen. Tied to the hierarchy rather
    // than started outright, or the timer would keep firing against a disposed window and
    // hold the whole component graph alive with it.
    final Timer pulseTimer = new Timer(MINIMAP_PULSE_MS, null)
    pulseTimer.addActionListener(new AbstractAction() {
        void actionPerformed(ActionEvent event) {
            if (!view.isShowing()) { pulseTimer.stop(); return }
            pulse[0] = (pulse[0] + MINIMAP_PULSE_MS / (double) MINIMAP_PULSE_PERIOD) % 1.0d
            // Repaint ONLY where the ring is. Redrawing the whole view 25 times a second to
            // animate one box costs the same as redrawing it for a real change, which is
            // absurd for something decorative; clipped, the cost follows the ring's area.
            Map laid = (Map) minimapState.laid
            String key = (String) selectedKeyProvider.call()
            Map box = (laid == null || key == null) ? null
                    : ((List) laid.boxes).find { it.key == key }
            if (box == null) { view.repaint(); return }
            double scale = (double) laid.scale
            int reach = MINIMAP_PULSE_REACH + 8
            int x = (int) Math.floor((((int) box.x) - reach) * scale) + (int) laid.offsetX
            int y = (int) Math.floor((((int) box.y) - reach) * scale) + (int) laid.offsetY
            int w = (int) Math.ceil((((int) box.width) + reach * 2) * scale) + 2
            int h = (int) Math.ceil((((int) box.height) + reach * 2) * scale) + 2
            view.repaint(x, y, w, h)
        }
    })
    view.addHierarchyListener(new HierarchyListener() {
        @Override
        void hierarchyChanged(HierarchyEvent event) {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return
            if (view.isShowing()) pulseTimer.start() else pulseTimer.stop()
        }
    })

    // Hit testing has to undo both transforms the painting applied, in the same order.
    minimapState.hit = { Point point ->
        Map laid = (Map) minimapState.laid
        if (laid == null) return null
        double scale = (double) laid.scale
        int px = (int) Math.round((((int) point.@x) - (int) laid.offsetX) / scale)
        int py = (int) Math.round((((int) point.@y) - (int) laid.offsetY) / scale)
        return ((List) laid.boxes).find { box ->
            !box.junction && px >= (int) box.x && px <= (int) box.x + (int) box.width &&
                    py >= (int) box.y && py <= (int) box.y + (int) box.height
        }
    }

    // MOUSE_PRESSED, not MOUSE_CLICKED: a click that moves a pixel never produces
    // MOUSE_CLICKED, and the selection would silently not happen.
    //
    // `.call(...)` and never `onPick(...)`: inside an anonymous class, a bare call is
    // resolved against THAT class, not against the captured closure, and dies at runtime with
    // MissingMethodException. Reading the variable and invoking call() on it is a plain
    // capture, which works. Same reason the painter above says renderer.call(...).
    view.addMouseListener(new MouseAdapter() {
        @Override
        void mousePressed(MouseEvent event) {
            Map box = (Map) minimapState.hit.call(event.getPoint())
            // Junctions are not entries, and the empty canvas is not either: hit() returns
            // null for both, and clicking them should leave the window exactly as it was.
            if (box == null) return
            // One click goes. Same reasoning as the labels: pointing AT a box is already an
            // unambiguous choice, so asking for a second click -- or for Enter -- would be
            // confirming something that was never in doubt.
            onPick.call((String) box.key)
            onActivate.call()
        }
    })
    return view
}

void showNavigator(Map store, List categories) {
    Map colors = themeColors()
    int categoryIndex = 0

    // The name of the map, when every entry shares one; the tabs carry it.
    Map listContext = [mapLabel: null]
    // The tree the minimap draws, rebuilt whenever the entries change.
    Map treeState = [mapRoot: null]
    Closure mapRootProvider = { treeState.mapRoot }
    // Kept at zero: the breadcrumbs no longer take labels. The layout still accepts an offset.
    Map digitOffset = [value: 0]
    Closure digitOffsetProvider = { (int) digitOffset.value }
    // -1 when the selection is in the entries; otherwise the index of the selected crumb.
    int[] selectedCrumb = [-1] as int[]
    // First key of a two-key label, while the second is awaited. Null the rest of the time.
    String[] pendingPrefix = [null] as String[]

    // The entries on show and which one is selected. This used to be a JList, which was both
    // the list view and the selection model; with the list view gone there is no widget left
    // to own a selection, and a plain list plus an index is the whole of what was being used.
    List<NavItem> entries = new ArrayList<NavItem>()
    int[] cursor = [-1] as int[]
    Closure selectedItem = { -> (cursor[0] >= 0 && cursor[0] < entries.size()) ? entries[cursor[0]] : null }
    Closure selectedKeyProvider = { -> selectedItem()?.key() }

    // The tabs are real components, not a line of text with brackets round the active one.
    // A filled tab is the only thing that reads as "selected" at a glance.
    JPanel headerTabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4))
    headerTabs.opaque = false
    headerTabs.border = new EmptyBorder(2, 6, 0, 8)
    JLabel headerInfo = new JLabel()
    headerInfo.border = new EmptyBorder(0, 10, 0, 0)

    // UITools.getCurrentFrame() is the Freeplane main window; the ancestor of the map view
    // is the same thing when a map is docked, and is the fallback if it ever is not.
    Window owner = UITools.getCurrentFrame() ?: SwingUtilities.getWindowAncestor(mapViewManager.mapViewComponent)
    JFrame frame = new JFrame('Universal Navigator')
    frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
    frame.alwaysOnTop = true

    // Sized and placed against the Freeplane window, not the screen: on a multi-monitor
    // setup the screen bounds would put it on the wrong display, and locationRelativeTo
    // has its own habit of leaving part of the window outside the desktop.
    // Done before the contents are built, because the breadcrumbs share out the width.
    Rectangle ownerBounds = owner != null ? owner.getBounds() : null
    if (ownerBounds == null || (int) ownerBounds.@width <= 0) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize()
        ownerBounds = new Rectangle(0, 0, (int) screen.@width, (int) screen.@height)
    }
    // .@ throughout: reading Rectangle.width as a property yields a double in Groovy, and
    // the Dimension/setLocation calls then fail to find a matching signature.
    int ownerX = (int) ownerBounds.@x
    int ownerY = (int) ownerBounds.@y
    int ownerWidth = (int) ownerBounds.@width
    int ownerHeight = (int) ownerBounds.@height
    int frameWidth = (int) Math.round(ownerWidth * NAV_WIDTH_RATIO)
    int frameHeight = (int) Math.round(ownerHeight * NAV_HEIGHT_RATIO)
    frame.setSize(frameWidth, frameHeight)
    frame.setLocation(ownerX + (int) ((ownerWidth - frameWidth) / 2),
                      ownerY + (int) ((ownerHeight - frameHeight) / 2))
    frame.layout = new BorderLayout()
    JPanel top = new JPanel()
    top.layout = new BoxLayout(top, BoxLayout.Y_AXIS)
    top.opaque = true
    top.background = colors.background
    // Trimming the recent list is the one destructive thing this window can do, so it gets a
    // button rather than a key: no gesture should be able to drop entries by accident. The
    // count goes in the label instead of behind a confirmation dialog -- you see the damage
    // before you click, and a modal here would freeze the MCP script host anyway.
    JButton forgetOne = new JButton('Forget item')
    forgetOne.focusable = false
    forgetOne.font = forgetOne.font.deriveFont(Font.PLAIN, 11f)
    forgetOne.toolTipText = 'Drop just the selected entry from the recent list'

    JButton trim = new JButton()
    trim.focusable = false      // or it would eat Space and Enter from the key bindings
    trim.font = trim.font.deriveFont(Font.PLAIN, 11f)
    trim.toolTipText = 'Drop every entry older than the selected one from the recent list'
    Closure refreshTrim = {
        int selected = cursor[0]
        int droppable = (selected < 0) ? 0 : Math.max(0, entries.size() - selected - 1)
        trim.enabled = droppable > 0
        trim.text = (droppable > 0 ? ('Forget ' + droppable + ' older') : 'Forget older').toString()
        forgetOne.enabled = selected >= 0
    }
    refreshTrim()

    JPanel headerRow = new JPanel(new BorderLayout())
    headerRow.opaque = false
    headerRow.add(headerTabs, BorderLayout.CENTER)
    JPanel trimHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 3))
    trimHolder.opaque = false
    // The one-entry button first, so the sweeping one is not the one under the cursor by
    // default: they sit side by side and only one of them is undoable by re-visiting.
    trimHolder.add(forgetOne)
    trimHolder.add(trim)
    headerRow.add(trimHolder, BorderLayout.EAST)
    headerRow.alignmentX = Component.LEFT_ALIGNMENT
    top.add(headerRow)

    // Climbing to an ancestor of the current root is a common move, and the recent list
    // cannot offer it: an ancestor you never jumped into before is not in there.
    Closure activateCrumb = { crumbNode ->
        frame.dispose()
        jumpInTo(crumbNode.delegate)
        selectNode(crumbNode.delegate)
        recordRoot(store, c.viewRoot)
    }
    Map crumbs = buildBreadcrumbs(colors, frameWidth, activateCrumb)
    JPanel crumbBar = (JPanel) crumbs.panel
    crumbBar.alignmentX = Component.LEFT_ALIGNMENT
    if (crumbBar.componentCount > 0) top.add(crumbBar)
    // The crumbs no longer consume any labels, so the list starts counting at zero.
    digitOffset.value = 0

    frame.add(top, BorderLayout.NORTH)

    Map minimapState = [laid: null, hit: null]
    // Set once the minimap exists; the selection helpers below need to repaint it.
    JComponent[] minimapHolder = new JComponent[1]
    Closure selectIndex = { int index ->
        if (index < 0 || index >= entries.size()) return
        cursor[0] = index
        minimapHolder[0]?.repaint()
        refreshTrim()
    }
    Closure selectByKey = { String key ->
        for (int i = 0; i < entries.size(); i++) {
            if (entries[i].key() == key) { selectIndex(i); return }
        }
    }
    Closure activateSelected = {
        NavItem selected = selectedItem()
        frame.dispose()
        if (selected != null) categories[categoryIndex].activate(selected)
    }
    JComponent minimap = buildMinimapView(colors, mapRootProvider, minimapState,
                                          selectedKeyProvider,
                                          digitOffsetProvider,
                                          { -> pendingPrefix[0] },
                                          selectByKey, activateSelected)
    minimapHolder[0] = minimap
    frame.add(minimap, BorderLayout.CENTER)

    // Feeding the list goes through here, because a relative rail scale has to be rebuilt
    // from the entries being shown before they are handed to the list. Published as a
    // client property so a script can drive the window with its own data.
    // The header depends on the list (it carries the shared map name), so it is rewritten
    // by whoever changes the list, not by the caller. Keeping it in loadCategory left it
    // stale whenever the data was replaced by any other route.
    // loadCategory is declared further down, so the tab listeners cannot name it directly --
    // and inside an anonymous class a bare call would be resolved against that class anyway.
    // Held in a one-slot array, filled in once loadCategory exists, and always invoked with
    // .call() so it stays a plain variable read.
    Closure[] categoryLoader = new Closure[1]
    final Color tabActiveBackground = (Color) (colors.nodeSelection ?: colors.selectionBackground)
    final Color tabActiveForeground = UITools.getTextColorForBackground(tabActiveBackground)
    final Color tabIdleForeground = new Color(colors.foreground.getRed(), colors.foreground.getGreen(),
                                              colors.foreground.getBlue(), 165)

    Closure refreshHeader = {
        headerTabs.removeAll()
        categories.eachWithIndex { cat, int i ->
            final boolean active = (i == categoryIndex)
            final Color fill = tabActiveBackground
            JLabel tab = new JLabel(cat.label.toString()) {
                @Override
                protected void paintComponent(Graphics g) {
                    if (active) {
                        Graphics2D g2 = (Graphics2D) g.create()
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.setColor(fill)
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10)
                        g2.dispose()
                    }
                    super.paintComponent(g)
                }
            }
            tab.opaque = false
            tab.foreground = active ? tabActiveForeground : tabIdleForeground
            tab.font = tab.font.deriveFont(active ? Font.BOLD : Font.PLAIN, 12f)
            tab.border = new EmptyBorder(4, 11, 4, 11)
            if (!active) {
                tab.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                // MOUSE_PRESSED, not MOUSE_CLICKED: a click that moves a pixel produces no
                // MOUSE_CLICKED at all, and the tab would silently not switch.
                final int index = i
                tab.addMouseListener(new MouseAdapter() {
                    @Override
                    void mousePressed(MouseEvent event) { categoryLoader[0]?.call(index) }
                })
            }
            headerTabs.add(tab)
        }
        // What the window answers to, said once rather than printed on every tab.
        String info = categories.size() > 1 ? '← →  tabs      Tab  older      label or Enter  go'
                                            : 'Tab  older      label or Enter  go'
        if (listContext.mapLabel) info = info + (info ? '      ' : '') + listContext.mapLabel
        if (pendingPrefix[0] != null) info = info + '        ' + pendingPrefix[0] + '…'
        headerInfo.text = info
        headerInfo.foreground = tabIdleForeground
        headerInfo.font = headerInfo.font.deriveFont(Font.PLAIN, 11f)
        headerTabs.add(headerInfo)
        headerTabs.revalidate()
        headerTabs.repaint()
    }

    Closure showItems = { List<NavItem> items ->
        // When every entry comes from one map, its name goes in the header rather than being
        // repeated. Unsaved maps have no path, so they are told apart by name instead.
        def mapKeys = (items ?: []).collect { it.mapPath ?: ('name:' + it.mapName) }.unique()
        boolean sameMap = mapKeys.size() == 1 && items
        listContext.mapLabel = sameMap ? (items[0].mapName ?: '(unsaved map)') : null
        entries.clear()
        if (items) entries.addAll(items)
        cursor[0] = -1
        treeState.mapRoot = prepareMinimapTree(entries)
        refreshHeader()
        minimap.repaint()
    }

    Closure loadCategory = { int index ->
        categoryIndex = ((index % categories.size()) + categories.size()) % categories.size()
        def category = categories[categoryIndex]
        List<NavItem> items = category.items()
        showItems(items)
        // Start on the second entry: the first is where we already are, so a bare
        // Ctrl+Tab toggles back to the previous root, like Alt+Tab does.
        int initial = items.size() > 1 ? 1 : 0
        if (items) selectIndex(initial)
    }
    categoryLoader[0] = loadCategory

    // Wired only now: they need loadCategory, which is declared above but after the buttons.
    forgetOne.addActionListener(new AbstractAction() {
        void actionPerformed(ActionEvent event) {
            NavItem anchor = selectedItem()
            int at = forgetEntry(store, anchor)
            if (at < 0) return
            loadCategory(categoryIndex)
            // Land on whatever took its place, so a run of deletions needs no re-aiming;
            // at the end of the list, step back instead.
            if (entries.size() > 0) selectIndex(Math.min(at, entries.size() - 1))
            refreshTrim()
        }
    })

    trim.addActionListener(new AbstractAction() {
        void actionPerformed(ActionEvent event) {
            NavItem anchor = selectedItem()
            if (forgetOlderThan(store, anchor) == 0) return
            loadCategory(categoryIndex)
            selectByKey(anchor.key())
            refreshTrim()
        }
    })

    def inputMap = frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
    def actionMap = frame.rootPane.actionMap

    Closure bind = { KeyStroke stroke, String name, Closure body ->
        inputMap.put(stroke, name)
        actionMap.put(name, new AbstractAction() {
            void actionPerformed(ActionEvent e) { body() }
        })
    }

    Closure leaveCrumbs = {
        // Moving is also the way back from a crumb.
        if (selectedCrumb[0] >= 0) {
            selectedCrumb[0] = -1
            crumbs.highlight(-1)
        }
    }

    /**
     * Recency order, which is the order of the labels and of the list itself. This is the
     * Alt+Tab gesture: hold the modifier, tap Tab to walk back through where you have been.
     * The drawing says nothing about when, so the selection does jump around it -- that is
     * the point, and the price of having one order rather than two. Wraps at both ends, as
     * Alt+Tab does.
     */
    Closure moveByRecency = { int delta ->
        if (entries.isEmpty()) return
        leaveCrumbs()
        selectIndex((cursor[0] + delta + entries.size()) % entries.size())
    }

    /**
     * Binds a key plain AND with Ctrl held.
     *
     * The window no longer needs the modifier for anything -- Enter commits -- but the way IN
     * is still Ctrl+Tab, so for a moment after it opens the modifier is very likely still
     * down. Accepting both spellings costs one line and means a key pressed a fraction too
     * early is not simply swallowed.
     */
    Closure bindWithAndWithoutCtrl = { int code, String name, Closure body ->
        bind(KeyStroke.getKeyStroke(code, KeyEvent.CTRL_DOWN_MASK, false), 'ctrl' + name, body)
        bind(KeyStroke.getKeyStroke(code, 0, false), name, body)
    }

    // Tab and the vertical arrows walk the same line: down and Tab go back in time, up and
    // Shift+Tab come forward. The arrows used to walk the DRAWING instead, top to bottom
    // through the tree, which meant the two gestures disagreed about what "next" is -- and
    // the drawing's order is one nothing else in the window uses: not the labels, not the
    // list, not Tab. One order, spelled the same way by every key that moves the selection.
    bindWithAndWithoutCtrl(KeyEvent.VK_TAB, 'next') { moveByRecency(1) }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK, false), 'ctrlPrevious') { moveByRecency(-1) }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK, false), 'previous') { moveByRecency(-1) }
    bindWithAndWithoutCtrl(KeyEvent.VK_DOWN, 'down') { moveByRecency(1) }
    bindWithAndWithoutCtrl(KeyEvent.VK_UP, 'up') { moveByRecency(-1) }

    // Left and right switch which set of entries is on show. They are free: the labels own
    // the keyboard now, and the horizontal arrows were never used for anything here.
    Closure switchCategory = { int delta ->
        if (categories.size() < 2) return
        pendingPrefix[0] = null
        loadCategory(categoryIndex + delta)
    }
    bindWithAndWithoutCtrl(KeyEvent.VK_RIGHT, 'nextCategory') { switchCategory(1) }
    bindWithAndWithoutCtrl(KeyEvent.VK_LEFT, 'previousCategory') { switchCategory(-1) }
    // Escape backs out of a half-typed label first, and only closes the window once there is
    // nothing left to back out of.
    bindWithAndWithoutCtrl(KeyEvent.VK_ESCAPE, 'cancel') {
        if (pendingPrefix[0] != null) {
            pendingPrefix[0] = null
            minimap.repaint()
            refreshHeader()
            return
        }
        frame.dispose()
    }

    // A label names a position in the recent list: 0 is where you are, 3 is three roots ago.
    // Pressing one only moves the selection -- releasing Ctrl is what commits, like Alt+Tab.
    Closure selectRow = { int rowIndex ->
        if (rowIndex < 0 || rowIndex >= entries.size()) return
        selectedCrumb[0] = -1
        crumbs.highlight(-1)
        selectIndex(rowIndex)
    }
    Closure selectCrumb = { int crumbIndex ->
        selectedCrumb[0] = crumbIndex
        cursor[0] = -1
        minimap.repaint()
        refreshTrim()
        crumbs.highlight(crumbIndex)
    }
    // Typing a label. Most are one key; the tail of a long list takes the prefix plus one,
    // and between the two keystrokes `pendingPrefix` holds the first.
    Closure repaintViews = { minimap.repaint(); refreshHeader() }

    Closure pressSymbol = { String symbol ->
        String label = (pendingPrefix[0] != null) ? (pendingPrefix[0] + symbol) : symbol
        pendingPrefix[0] = null
        // A lone prefix is never a label, so it can only mean "a two-key label starts here".
        if (label == SHORTCUT_PREFIX) {
            pendingPrefix[0] = symbol
            repaintViews()
            return
        }
        int position = shortcutIndexOf(label)
        repaintViews()
        // No such label, or no entry at it: the keystroke simply does nothing. Checked here
        // rather than left to selectRow, because going on to activate would then commit
        // whatever happened to be selected before -- a wrong jump instead of no jump.
        if (position < 0 || position >= entries.size()) return
        // Breadcrumbs no longer take labels: the digits belong entirely to the recent list,
        // so 0 is where you are and 3 is three roots ago, with nothing shifting the count.
        selectRow(position)
        // And go. A label names one entry unambiguously, so asking for Enter afterwards
        // would be confirming a choice already made. Tab and the arrows still only move the
        // selection -- those are for looking around, where a stray key should cost nothing.
        activateSelected()
    }

    shortcutAlphabet().each { String symbol ->
        char ch = symbol.charAt(0)
        int code = Character.isDigit(ch) ? (KeyEvent.VK_0 + Character.getNumericValue(ch))
                                         : (KeyEvent.VK_A + (((int) ch) - ((int) ('A' as char))))
        Closure go = { pressSymbol(symbol) }
        bind(KeyStroke.getKeyStroke(code, KeyEvent.CTRL_DOWN_MASK, false), 'slotCtrl' + symbol, go)
        bind(KeyStroke.getKeyStroke(code, 0, false), 'slotPlain' + symbol, go)
    }

    /**
     * Enter commits, and nothing else does.
     *
     * This used to be "release Ctrl", the Alt+Tab gesture. That fitted a window whose only
     * job was to confirm a choice already made before it opened -- but this one is a mind map
     * to be read, with three categories and a mouse, and a window that vanishes the moment
     * you relax your hand is hostile to reading. Holding the modifier also forced every key
     * to be registered twice and made the clicks unreachable: letting go to grab the mouse
     * committed instead.
     */
    bindWithAndWithoutCtrl(KeyEvent.VK_ENTER, 'activate') {
        if (selectedCrumb[0] >= 0) {
            def target = ((List) crumbs.targets)[selectedCrumb[0]]
            if (target != null) activateCrumb(target)   // disposes the frame itself
            return
        }
        activateSelected()
    }

    frame.addWindowFocusListener(new WindowAdapter() {
        @Override
        void windowLostFocus(WindowEvent e) {
            if (frame.displayable) frame.dispose()
        }
    })

    loadCategory(0)
    frame.visible = true
    minimap.requestFocusInWindow()
}

// ---------------------------------------------------------------- categories

/**
 * A category is [label, items, activate]. Adding one here is all it takes for it
 * to show up in the header and answer to its number key.
 */
List buildCategories(Map store) {
    return [
            [label   : 'Recent roots',
             items   : { -> store.recentRoots },
             activate: { NavItem item -> activateRecentRoot(store, item) }],
            // Same window, same drawing, another set of entries. The minimap does not care
            // what a list means -- it draws whatever it is given as the tree those entries
            // form, so a second category costs a loader and an activator.
            [label   : 'Recent nodes',
             items   : { ->
                 List<NavItem> nodes = loadRecentNodes(store)
                 refreshFromOpenMaps(nodes)
                 return nodes
             },
             activate: { NavItem item -> activateRecentNode(store, item) }],
            // Where you have BEEN versus what you have CHANGED -- two different questions,
            // and this one needs no recorder at all: the maps already carry the answer.
            [label   : 'Recently edited',
             items   : { -> loadRecentlyEdited() },
             activate: { NavItem item -> activateAnyNode(store, item) }]
    ]
}

// ---------------------------------------------------------------- entry point

Map store = loadStore()

// One script, one thing. This used to branch on whether Ctrl was held: with the modifier it
// showed the window, without it did a plain jump in and recorded the root. That second job is
// now the recorder's -- since it started polling the view root it notices Freeplane's OWN
// jump in too, so the native action records itself and there is nothing left to wrap.
// Show it inline: we are already on the EDT, this being a hotkey action.
if (SwingUtilities.isEventDispatchThread()) showNavigator(store, buildCategories(store))
else SwingUtilities.invokeAndWait { showNavigator(store, buildCategories(store)) }
