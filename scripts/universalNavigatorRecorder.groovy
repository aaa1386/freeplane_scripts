// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2760
// Version: 1.0

/**
 * Universal Navigator - navigation recorder.
 *
 * Records where you have been, so the navigator can offer it back. It replaces the
 * bookkeeping that universalNavigator.groovy used to do on its own, which only ever saw
 * the jumps it performed itself: a jump in made through the native action, and every
 * bookmark that opens as root (NodeBookmark.open -> NodeNavigator.open, which calls
 * setViewRoot directly), went unrecorded.
 *
 * It hooks a single INodeSelectionListener on the mode controller's map controller. That
 * listener is global and outlives map reloads, so it is installed once and covers every
 * open map. Every root change either passes through a selection or happens on top of one,
 * so reading the current view root inside onSelect reconstructs the (node, root) pair -
 * there is no view-root event in the API, and none is needed.
 *
 * Two things are written to <user directory>/universalNavigator.json:
 *
 *   recentRoots - most recently used roots, deduplicated, newest first. Same shape the
 *                 navigator already reads, so the existing list keeps working untouched.
 *   events      - the chronological trail of (node, root) pairs, which is what a
 *                 two-level navigator needs to group node history by context.
 *
 * A selection is only appended to the trail once it has held still for DWELL_MS. That
 * single rule does three jobs: it keeps arrow-key travel from flooding the trail, it
 * discards the phantom selection Freeplane makes on a neighbour while moving or deleting
 * a node, and it collapses the per-node events of a multiple selection.
 */

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import org.freeplane.features.map.INodeSelectionListener
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils

import javax.swing.UIManager
import java.awt.event.ActionListener
import java.lang.ref.WeakReference
import java.util.concurrent.Callable

/**
 * Deliberately NOT named after the file. Groovy names the script class after the file
 * (universalNavigatorRecorder), and Freeplane caches compiled scripts as .class files in
 * <user dir>/compiledscripts2. On Windows that directory is case-insensitive, so a class
 * called UniversalNavigatorRecorder and the script class universalNavigatorRecorder are
 * the same filename: one overwrites the other and the next startup dies with
 * NoClassDefFoundError. Any name that differs by more than case is safe.
 */
class UniversalNavigatorTracker implements INodeSelectionListener {

    static final String STORE_NAME = 'universalNavigator.json'
    static final int STORE_VERSION = 3

    static final int MAX_EVENTS = 300
    static final int MAX_RECENT_ROOTS = 50
    /** How long a selection must hold still before it counts as a visit. */
    static final int DWELL_MS = 1500
    /**
     * How often the view root is checked on its own.
     *
     * A jump in produces NO selection: the node is already selected when it becomes the root,
     * so onSelect never fires and the visit was lost outright -- measured, the view root sat
     * on a node for many seconds while the recorder still listed the map root. There is no
     * view-root event in the API to listen to, so this polls. The check is a reference
     * comparison and does nothing at all when the root has not moved.
     */
    static final int ROOT_POLL_MS = 400
    /** The store is written at most this often, and only when something changed. */
    static final int FLUSH_MS = 10000
    /** Titles are cached so closed maps can still be listed; no need to cache essays. */
    static final int MAX_TITLE_LENGTH = 200

    final File storeFile
    final List<Map> events = new ArrayList<Map>()
    final List<Map> recentRoots = new ArrayList<Map>()

    private final javax.swing.Timer dwellTimer
    private final javax.swing.Timer flushTimer
    private final javax.swing.Timer rootTimer
    /** Weak, so a closed map is never held alive by the recorder. */
    private WeakReference<NodeModel> lastRoot = null
    private boolean dirty = false
    private int selectionCount = 0
    private int dwellCount = 0

    UniversalNavigatorTracker(File storeFile) {
        this.storeFile = storeFile
        load()
        dwellTimer = new javax.swing.Timer(DWELL_MS, { recordVisit() } as ActionListener)
        dwellTimer.setRepeats(false)
        flushTimer = new javax.swing.Timer(FLUSH_MS, { flush() } as ActionListener)
        flushTimer.setRepeats(true)
        flushTimer.start()
        rootTimer = new javax.swing.Timer(ROOT_POLL_MS, { checkRoot() } as ActionListener)
        rootTimer.setRepeats(true)
        rootTimer.start()
    }

    // ------------------------------------------------------------------ listener

    /**
     * This runs on every single selection, so it does the least it can get away with:
     * identity comparison of the view root, and a timer restart. Building the map-path
     * key here instead would be an order of magnitude more expensive - MindMap.getFile
     * alone measures ~13 us - and it would be wasted, because the root only ever changes
     * on a jump in.
     */
    @Override
    void onSelect(NodeModel node) {
        // A throw in here would travel back into Freeplane's selection machinery.
        try {
            selectionCount++
            checkRoot(node)
            dwellTimer.restart()
        } catch (Throwable ignored) {
        }
    }

    /**
     * Notices that the view root moved, and promotes it.
     *
     * Called from onSelect AND from rootTimer. The timer is not belt-and-braces: a jump in
     * changes the root without any selection at all, so on its own onSelect misses every one
     * of them that is not followed by a click somewhere.
     *
     * `hint` is the node a selection arrived with, used only as a fallback for the map root;
     * the timer has no such node and reads the selection instead.
     */
    private void checkRoot(NodeModel hint = null) {
        try {
            def controller = ScriptUtils.c()
            def viewRoot = controller.viewRoot
            NodeModel rootModel
            if (viewRoot != null) rootModel = viewRoot.delegate
            else if (hint != null) rootModel = hint.getMap().getRootNode()
            else rootModel = controller.selected?.mindMap?.root?.delegate
            if (rootModel == null) return
            if (!rootModel.is(lastRoot?.get())) {
                lastRoot = new WeakReference<NodeModel>(rootModel)
                syncRoot(controller)
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ recording

    /**
     * Roots are promoted the moment they change, without waiting for the dwell timer: a
     * jump in is deliberate the instant it happens, unlike passing over a node.
     */
    private void syncRoot(controller) {
        def root = currentRoot(controller)
        if (root != null) promoteRoot(root)
    }

    private void recordVisit() {
        try {
            def controller = ScriptUtils.c()
            def selected = controller.selected
            def root = currentRoot(controller)
            if (selected == null || root == null) return
            dwellCount++

            // ancestorIds, level and the colours are here for the same reason they are on a
            // recent root: they are what lets the navigator draw the trail as a tree, and
            // they cannot be recovered later from a map that is closed by then.
            Map visit = [nodeId     : selected.id,
                         mapPath    : pathOf(selected),
                         mapName    : nameOf(selected),
                         title      : titleOf(selected),
                         level      : levelOf(selected),
                         ancestorIds: ancestorIdsOf(selected),
                         bgColor    : backgroundColorOf(selected),
                         fgColor    : textColorOf(selected),
                         rootId     : root.id,
                         rootTitle  : titleOf(root),
                         rootLevel  : levelOf(root),
                         ts         : System.currentTimeMillis()]

            Map previous = events.isEmpty() ? null : events.get(events.size() - 1)
            boolean samePlace = previous != null &&
                    previous.nodeId == visit.nodeId &&
                    previous.mapPath == visit.mapPath &&
                    previous.rootId == visit.rootId
            if (!samePlace) {
                events.add(visit)
                while (events.size() > MAX_EVENTS) events.remove(0)
            }

            // Where we stopped inside this root, so coming back restores the spot.
            Map entry = findRoot(keyOf(root))
            if (entry != null) {
                entry.lastSelectedNodeId = selected.id
                entry.lastSelectedTitle = titleOf(selected)
                // And WHEN we were last busy in it. Without this, ts would only ever be the
                // moment the root was entered: a root entered on Monday and worked in all
                // week would keep claiming Monday, which is precisely backwards for anything
                // asking how recently it saw use. Nothing orders by ts -- the list's own
                // position is the record of recency -- so this only sharpens what ts means.
                entry.ts = visit.ts
            }
            dirty = true
        } catch (Throwable ignored) {
        }
    }

    private void promoteRoot(root) {
        String key = keyOf(root)
        Map existing = findRoot(key)
        if (existing != null) recentRoots.remove(existing)
        recentRoots.add(0, [nodeId            : root.id,
                            mapPath           : pathOf(root),
                            mapName           : nameOf(root),
                            title             : titleOf(root),
                            level             : levelOf(root),
                            ancestorIds       : ancestorIdsOf(root),
                            bgColor           : backgroundColorOf(root),
                            fgColor           : textColorOf(root),
                            lastSelectedNodeId: existing?.lastSelectedNodeId,
                            lastSelectedTitle : existing?.lastSelectedTitle,
                            ts                : System.currentTimeMillis()])
        while (recentRoots.size() > MAX_RECENT_ROOTS) recentRoots.remove(recentRoots.size() - 1)
        dirty = true
    }

    /** Registers wherever we already are, so installing mid-session is not a blind spot. */
    void seed() {
        try {
            def controller = ScriptUtils.c()
            def root = currentRoot(controller)
            if (root != null) lastRoot = new WeakReference<NodeModel>(root.delegate)
            syncRoot(controller)
            recordVisit()
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ freeplane glue

    /** getViewRoot() is null while at the map root, which is still a root for our purposes. */
    private def currentRoot(controller) {
        def viewRoot = controller.viewRoot
        if (viewRoot != null) return viewRoot
        return controller.selected?.mindMap?.root
    }

    private static String pathOf(node) {
        return node?.mindMap?.file?.absolutePath
    }

    private static String nameOf(node) {
        return node?.mindMap?.name
    }

    private static String keyOf(node) {
        return node.id + '@' + (pathOf(node) ?: 'null')
    }

    private static String titleOf(node) {
        String text = node?.plainText ?: node?.text ?: ''
        text = text.replaceAll('\\s+', ' ').trim()
        return text.length() > MAX_TITLE_LENGTH ? text.substring(0, MAX_TITLE_LENGTH) : text
    }

    /**
     * Depth from the map root, which is what the navigator plots on its depth rail.
     * getNodeLevel(boolean) is public API; the walk is a fallback for older builds.
     */
    private static int levelOf(node) {
        try {
            return node.getNodeLevel(true)
        } catch (Throwable ignored) {
            int depth = 0
            def parent = node?.parent
            while (parent != null) {
                depth++
                parent = parent.parent
            }
            return depth
        }
    }

    /**
     * The node's own pair of colours, cached so entries of closed maps look like the node
     * does on the map. node.style resolves the effective colours through the style chain -
     * measured to agree with NodeStyleController on every node tried - so the public API is
     * enough and the internal controller is not needed.
     */
    private static String backgroundColorOf(node) {
        try {
            return node.style.backgroundColorCode
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String textColorOf(node) {
        try {
            return node.style.textColorCode
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Ids of every ancestor, root of the map first. Cached so the navigator can tell that
     * one recent root sits inside another without opening either map - which is the one
     * case where plotting entries by depth says something true about kinship.
     */
    private static List<String> ancestorIdsOf(node) {
        List<String> ids = new ArrayList<String>()
        def ancestor = node?.parent
        while (ancestor != null) {
            ids.add(0, ancestor.id)
            ancestor = ancestor.parent
        }
        return ids
    }

    private Map findRoot(String key) {
        return recentRoots.find { keyOfEntry(it) == key }
    }

    private static String keyOfEntry(Map entry) {
        return entry.nodeId + '@' + (entry.mapPath ?: 'null')
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        try {
            if (!storeFile.exists()) return
            def parsed = new JsonSlurper().parseText(storeFile.getText('UTF-8'))
            (parsed.recentRoots ?: []).each { recentRoots.add(new LinkedHashMap(it)) }
            (parsed.events ?: []).each { events.add(new LinkedHashMap(it)) }
        } catch (Throwable ignored) {
        }
    }

    void flush() {
        if (!dirty) return
        try {
            storeFile.setText(new JsonBuilder([version    : STORE_VERSION,
                                               recentRoots: recentRoots,
                                               events     : events]).toPrettyString(), 'UTF-8')
            dirty = false
        } catch (Throwable ignored) {
        }
    }

    /**
     * Throws away what is held in memory and reads the store back from disk.
     *
     * This recorder holds recentRoots for up to FLUSH_MS and then writes the WHOLE file, so
     * anything another script changes on disk meanwhile is silently undone at the next
     * flush. That is what made the navigator's "forget older" button appear to work and then
     * bring the old list back on the next navigation. Whoever edits the file calls flush()
     * first, writes, then calls this.
     *
     * Pending changes are dropped on purpose -- they are exactly the ones just superseded.
     */
    void reload() {
        try {
            recentRoots.clear()
            events.clear()
            load()
            dirty = false
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ lifecycle

    void uninstall() {
        try {
            dwellTimer.stop()
            flushTimer.stop()
            rootTimer.stop()
            flush()
            def mapController = Controller.currentModeController.mapController
            new ArrayList(mapController.getNodeSelectionListeners())
                    .findAll { it.is(this) }
                    .each { mapController.removeNodeSelectionListener(it) }
        } catch (Throwable ignored) {
        }
    }

    String dump() {
        StringBuilder out = new StringBuilder()
        out.append('selections=').append(selectionCount)
                .append(' visits=').append(dwellCount)
                .append(' events=').append(events.size())
                .append(' roots=').append(recentRoots.size())
                .append(' dirty=').append(dirty).append('\n')
        out.append('store=').append(storeFile.absolutePath).append('\n')
        out.append('-- recent roots --\n')
        recentRoots.take(8).each {
            out.append('  L').append(it.level).append(' ').append(it.title)
                    .append('  [').append(it.mapName).append(']')
                    .append(it.lastSelectedTitle ? '  > ' + it.lastSelectedTitle : '').append('\n')
        }
        out.append('-- trail (newest last) --\n')
        events.reverse().take(12).reverse().each {
            out.append('  L').append(it.rootLevel).append(' ').append(it.rootTitle)
                    .append('  |  ').append(it.title).append('\n')
        }
        return out.toString()
    }
}

// ---------------------------------------------------------------------- installation

def controller = ScriptUtils.c()
def mapController = Controller.currentModeController.mapController

// A recompiled script produces a different class, so the previous generation can only be
// recognised by name. Ask it to stand down first: it flushes what it holds before we read
// the store back in.
def previousUninstall = UIManager.get('universalNavigator.recorder.uninstall')
if (previousUninstall instanceof Runnable) previousUninstall.run()
new ArrayList(mapController.getNodeSelectionListeners())
        .findAll { it.class.simpleName == 'UniversalNavigatorTracker' }
        .each { mapController.removeNodeSelectionListener(it) }

def recorder = new UniversalNavigatorTracker(
        new File(controller.userDirectory, UniversalNavigatorTracker.STORE_NAME))
mapController.addNodeSelectionListener(recorder)
recorder.seed()

// Handles typed as JDK interfaces, so a later run can reach them without needing the class.
UIManager.put('universalNavigator.recorder.flush', { recorder.flush() } as Runnable)
UIManager.put('universalNavigator.recorder.reload', { recorder.reload() } as Runnable)
UIManager.put('universalNavigator.recorder.uninstall', { recorder.uninstall() } as Runnable)
UIManager.put('universalNavigator.recorder.dump', { recorder.dump() } as Callable)

return ('Universal Navigator recorder installed. ' + recorder.dump()).toString()
