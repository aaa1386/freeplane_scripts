// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2954
// Version: 1.3

//Added Column Sorting aaa1386
//Added Group Categorization aaa1386
//Added Drag & Drop from Table to Group Tree aaa1386
//Group Rename and Delete
//fix: group dropdown scroll in AutoRun table
//revert(autoRunScripts): restore column sorting in table

import java.awt.*
import java.awt.event.*
import java.awt.dnd.DropTargetEvent
import javax.swing.*
import javax.swing.event.*
import javax.swing.table.*
import java.util.List // after java.awt.*, whose own List is not generic
import javax.swing.tree.DefaultMutableTreeNode
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceAdapter
import java.awt.dnd.DragSourceDragEvent
import java.awt.dnd.DragGestureEvent
import java.awt.dnd.DragGestureListener
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DragSourceContext

import java.util.regex.Pattern
import javax.swing.tree.DefaultTreeCellRenderer
import org.freeplane.core.resources.ResourceController
import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.ConfigurationUtils
import org.freeplane.core.util.FileUtils
import org.freeplane.core.util.LogUtils
import org.freeplane.features.map.IMapLifeCycleListener
import org.freeplane.features.map.MapModel
import org.freeplane.features.map.NodeModel
import org.freeplane.features.mode.Controller
import org.freeplane.features.ui.IMapViewChangeListener
import org.freeplane.main.application.ApplicationLifecycleListener
import org.freeplane.plugin.script.ScriptResources
import org.freeplane.plugin.script.ScriptingEngine

// Runs scripts automatically, on the trigger chosen for each of them.
//
// One file, two jobs. Called from <user directory>/scripts/init/ it installs the triggers
// and runs the STARTUP ones; called from the Scripts menu it opens the configuration
// dialog. Which job is wanted is decided by the caller: the init script sets
// UIManager.put('autoRunScripts.bootstrap', Boolean.TRUE) before calling, the menu does not.
//
// INSTALLATION
// ------------
// 1. Put this file in any of your script directories, next to your other scripts. Running it
//    from the Scripts menu opens the configuration window.
// 2. Press "Install startup hook" in that window. Freeplane only runs, by itself, the scripts
//    in <user directory>/scripts/init, so a small bridge has to live there; the button writes
//    it. The window says so on its own when the hook is missing, and until it exists nothing
//    runs at the next start, however full the list looks.
// 3. Restart Freeplane once, so that both files are picked up.
//
// Configuration lives in <user directory>/autoRunScripts.txt: one line per script,
// "<TRIGGER><tab><path>", in execution order, with a third tab-separated field holding the
// interval in minutes for PERIODIC. Lines without a tab are read as STARTUP, the format
// written by earlier versions. '#' comments and blank lines are ignored.
//
// Triggers:
//   STARTUP       once, when Freeplane has finished starting
//   MAP_OPENED    for every map created or loaded, plus the maps already restored at
//                 startup (they load before init scripts run, so they would be missed)
//   MAP_CLOSED    when a map is closed, including on the way out of Freeplane
//   VIEW_CREATED  when a tab is created
//   VIEW_SELECTED when a tab becomes the current one
//   PERIODIC      every N minutes, first run one interval after installation
//   SHUTDOWN      when Freeplane is closing. Maps are still open at that point, but have
//                 already been saved: a script that changes one must save it itself.
//
// Scripts run through ScriptingEngine, so each gets the usual bindings, with 'node' bound
// to the map or tab that triggered it, and the user's script permissions. A script can ask
// which trigger called it with UIManager.get('autoRunScripts.trigger'), which is MANUAL when
// it was started from the buttons in the dialog.
//
// CHANGELOG
// ---------
//   1.2 (2026-07-30)
//       The window listed only the folders named in the scripting preferences, so it was
//       empty for everyone who keeps scripts where Freeplane puts them, in
//       <user directory>/scripts. It now searches exactly what Freeplane searches: that
//       folder and the built-in one as well, whatever the preference says. The startup
//       hook looked for this script in the same too-short list, so it could fail to find
//       it; it is now rewritten by the button, which offers the update when the installed
//       one is older. Folder lists are read with Freeplane's own separator, ':' on Linux
//       and Mac, instead of always ';'. Where a script comes from is now visible as well:
//       every row tells its full path in a tooltip, the status bar tooltip lists the
//       folders searched, and finding nothing is said instead of shown as an empty table.
//       Reported by aaa1386.
//   1.1 (2026-07-27)
//       The window now notices when the startup hook is missing and offers to install it.
//       Until now a fully configured list could look active while nothing at all would run
//       at the next start, with nothing on screen saying so.
//   1.0 (2026-07-27)
//       First public version.

class AutoRunEntry {
    File file
    String trigger
    int everyMinutes
    // Virtual UI-only group. It is deliberately not part of autoRunScripts.txt, so the
    // execution format and every trigger remain completely unchanged.
    String group
    // a disabled entry stays in the list, keeping its trigger, order and interval, so that
    // switching a script off to test something does not cost you its configuration
    boolean enabled

    AutoRunEntry(File file, String trigger, int everyMinutes, boolean enabled) {
        this(file, trigger, everyMinutes, 'Uncategorized', enabled)
    }

    AutoRunEntry(File file, String trigger, int everyMinutes, String group, boolean enabled) {
        this.file = file
        this.trigger = trigger
        this.everyMinutes = everyMinutes
        this.group = group ?: 'Uncategorized'
        this.enabled = enabled
    }
}

class AutoRunDispatcher {
    static final List<String> TRIGGERS = ['STARTUP', 'MAP_OPENED', 'MAP_CLOSED',
                                          'VIEW_CREATED', 'VIEW_SELECTED', 'PERIODIC', 'SHUTDOWN']
    static final int DEFAULT_MINUTES = 15
    // switched-off entries are written as comments, so every reader skips them for free
    static final String DISABLED_MARK = '#off '
    static final String PAUSED_KEY = 'autoRunScripts.paused'
    static final String GENERATION_KEY = 'autoRunScripts.generation'
    static final String MAP_LISTENER_KEY = 'autoRunScripts.mapListener'
    static final String VIEW_LISTENER_KEY = 'autoRunScripts.viewListener'
    static final String TICKER_KEY = 'autoRunScripts.ticker'
    // readable by the script being run, to find out which trigger called it
    static final String TRIGGER_KEY = 'autoRunScripts.trigger'
    static final String HISTORY_KEY = 'autoRunScripts.history'
    static final String NOTIFIED_KEY = 'autoRunScripts.failureShown'
    static final int HISTORY_LIMIT = 500

    // Kept in UIManager rather than in a field, so that it survives this script being
    // recompiled and reinstalled. Rows are maps of plain Strings on purpose: an object of a
    // class declared here would be unreadable after recompilation, since the class is a new
    // one -- and keys instead of positions mean an added field cannot break older rows.
    static List history() {
        def list = UIManager.get(HISTORY_KEY)
        if (!(list instanceof List)) {
            list = Collections.synchronizedList(new ArrayList())
            UIManager.put(HISTORY_KEY, list)
        }
        return (List) list
    }

    static void record(String trigger, File file, NodeModel node, String result, Long millis) {
        def list = history()
        synchronized (list) {
            list.add([time   : new Date().format('HH:mm:ss'),
                      trigger: trigger,
                      script : file.name.replaceFirst(/(?i)\.groovy$/, ''),
                      node   : node?.text ?: '',
                      // MMapModel.getTitle() is the short name, the one shown on the tab
                      map    : node?.map?.title ?: '',
                      millis : millis == null ? '' : String.valueOf(millis),
                      result : result])
            while (list.size() > HISTORY_LIMIT) list.remove(0)
        }
    }

    final File listFile
    Closure failureNotifier = null
    private long stamp = -1L
    private Map<String, List> byTrigger = [:]
    private final Map<String, Long> lastPeriodicRun = new HashMap<String, Long>()

    AutoRunDispatcher(File listFile) { this.listFile = listFile }

    static boolean isStale(int generation) {
        def current = UIManager.get(GENERATION_KEY)
        return current != null && ((Integer) current).intValue() != generation
    }

    // reread only when the file changed, so an event costs one stat and a map lookup
    synchronized List entriesFor(String trigger) {
        if (listFile.lastModified() != stamp) reload()
        return byTrigger[trigger] ?: new ArrayList()
    }

    private void reload() {
        stamp = listFile.lastModified()
        def loaded = [:]
        TRIGGERS.each { loaded[it] = new ArrayList() }
        if (listFile.isFile()) {
            listFile.readLines('UTF-8').each { String raw ->
                def line = raw.trim()
                if (!line || line.startsWith('#')) return
                def parts = line.split('\t')
                def hasTrigger = parts.length >= 2 && TRIGGERS.contains(parts[0].trim())
                def trigger = hasTrigger ? parts[0].trim() : 'STARTUP'
                def path = hasTrigger ? parts[1].trim() : line
                int minutes = DEFAULT_MINUTES
                if (parts.length >= 3 && parts[2].trim().isInteger()) {
                    minutes = Math.max(1, parts[2].trim() as int)
                }
                if (path) loaded[trigger] << [file: new File(path), every: minutes]
            }
        }
        byTrigger = loaded
    }

    private Map executeOne(String trigger, File file, NodeModel node) {
        if (!file.isFile()) {
            LogUtils.warn("auto-run script not found (${trigger}): ${file}")
            record(trigger, file, node, 'not found', null)
            return [ok: false, message: "${file.name} (not found)".toString()]
        }
        long started = System.nanoTime()
        try {
            LogUtils.info("running auto-run script (${trigger}): ${file}")
            ScriptingEngine.executeScript(node, file, null)
            record(trigger, file, node, 'ok', Math.round((System.nanoTime() - started) / 1000000d))
            return [ok: true, message: null]
        }
        catch (Throwable t) {
            // one broken script must not stop the ones listed after it
            LogUtils.warn("auto-run script failed (${trigger}): ${file}", t)
            def reason = "${t.class.simpleName}: ${t.message}".toString()
            record(trigger, file, node, reason, Math.round((System.nanoTime() - started) / 1000000d))
            return [ok: false, message: "${file.name} (${reason})".toString()]
        }
    }

    Map run(String trigger, NodeModel node) {
        return runAll(trigger, entriesFor(trigger).collect { it.file }, node)
    }

    // deliberately not persisted: pausing survives no restart, so it cannot be forgotten
    // in a way that silently disables everything tomorrow
    static boolean isPaused() { return UIManager.get(PAUSED_KEY) != null }

    Map runAll(String trigger, List<File> files, NodeModel node) {
        int ok = 0
        def failed = []
        // manual runs from the dialog do not come through here, so they still work while paused
        if (isPaused()) return [ok: 0, failed: []]
        // let the script know why it was called; restore in case one run nests inside another
        def previousTrigger = UIManager.get(TRIGGER_KEY)
        UIManager.put(TRIGGER_KEY, trigger)
        try {
            files.each { File file ->
                def outcome = executeOne(trigger, file, node)
                if (outcome.ok) ok++ else failed << outcome.message
            }
        }
        finally {
            UIManager.put(TRIGGER_KEY, previousTrigger)
        }
        if (failed && failureNotifier != null) failureNotifier(trigger, failed)
        return [ok: ok, failed: failed]
    }

    // called by the ticker: runs the periodic scripts whose interval has elapsed
    void runDuePeriodic(NodeModel node) {
        long now = System.currentTimeMillis()
        def due = []
        entriesFor('PERIODIC').each { entry ->
            String key = entry.file.absolutePath
            Long last = lastPeriodicRun[key]
            if (last == null) {
                lastPeriodicRun[key] = now
                return
            }
            if (now - last >= entry.every * 60000L) {
                lastPeriodicRun[key] = now
                due << entry.file
            }
        }
        if (due) runAll('PERIODIC', due, node)
    }
}

class AutoRunMapTrigger implements IMapLifeCycleListener {
    final AutoRunDispatcher dispatcher
    final int generation

    AutoRunMapTrigger(AutoRunDispatcher dispatcher, int generation) {
        this.dispatcher = dispatcher
        this.generation = generation
    }

    @Override
    void onCreate(MapModel map) { fire('MAP_OPENED', map) }

    @Override
    void onRemove(MapModel map) { fire('MAP_CLOSED', map) }

    private void fire(String trigger, MapModel map) {
        if (AutoRunDispatcher.isStale(generation)) {
            Controller.currentController.modeController.mapController.removeMapLifeCycleListener(this)
            return
        }
        if (map != null) dispatcher.run(trigger, map.rootNode)
    }
}

class AutoRunViewTrigger implements IMapViewChangeListener {
    final AutoRunDispatcher dispatcher
    final int generation
    // Freeplane calls afterViewCreated once per attempt, not once per view: mapViewCreated()
    // re-fires itself through a HierarchyListener until the view is showing and laid out
    // (MapViewChangeObserverCompound:95-127). Weak keys so closed views can be collected.
    private final Set<Component> alreadyCreated = Collections.newSetFromMap(new WeakHashMap<Component, Boolean>())

    AutoRunViewTrigger(AutoRunDispatcher dispatcher, int generation) {
        this.dispatcher = dispatcher
        this.generation = generation
    }

    @Override
    void afterViewCreated(Component newView) {
        if (newView == null) return
        synchronized (alreadyCreated) {
            if (!alreadyCreated.add(newView)) return
        }
        fire('VIEW_CREATED', newView)
    }

    @Override
    void afterViewChange(Component oldView, Component newView) { fire('VIEW_SELECTED', newView) }

    private void fire(String trigger, Component view) {
        def controller = Controller.currentController
        if (AutoRunDispatcher.isStale(generation)) {
            controller.mapViewManager.removeMapViewChangeListener(this)
            return
        }
        if (view == null) return
        def map = controller.mapViewManager.getMap(view)
        if (map == null) return
        def selected = controller.selection?.selected
        def node = (selected != null && selected.map.is(map)) ? selected : map.rootNode
        dispatcher.run(trigger, node)
    }
}

// Controller has addApplicationLifecycleListener but no remove, so a superseded instance
// cannot unregister -- it just turns into a no-op once its generation is stale.
class AutoRunShutdownTrigger implements ApplicationLifecycleListener {
    final AutoRunDispatcher dispatcher
    final int generation

    AutoRunShutdownTrigger(AutoRunDispatcher dispatcher, int generation) {
        this.dispatcher = dispatcher
        this.generation = generation
    }

    @Override
    void onStartupFinished() {}

    @Override
    void onApplicationStopped() {
        if (AutoRunDispatcher.isStale(generation)) return
        dispatcher.run('SHUTDOWN', Controller.currentController.selection?.selected)
    }
}

class AutoRunTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = ['On', '#', 'Trigger', 'Every', 'Script', 'Folder', 'Group'] as String[]
    final List<AutoRunEntry> active = new ArrayList<AutoRunEntry>()
    final List<File> inactive = new ArrayList<File>()
    List<String> triggerLabels
    List<String> groupLabels = ['Uncategorized']
    Closure onChange = {}
    Closure onGroupChange = {}

    int getRowCount() { active.size() + inactive.size() }

    int getColumnCount() { COLUMNS.length }

    String getColumnName(int column) { COLUMNS[column] }

    Class getColumnClass(int column) { column == 0 ? Boolean : String }

    boolean isCellEditable(int row, int column) {
        if (column == 0 || column == 2 || column == 6) return true
        // the interval only means anything for periodic scripts
        return column == 3 && row < active.size() && active[row].trigger == 'PERIODIC'
    }

    File fileAt(int row) {
        return row < active.size() ? active[row].file : inactive[row - active.size()]
    }

    Object getValueAt(int row, int column) {
        boolean configured = row < active.size()
        File file = fileAt(row)
        if (column == 0) return Boolean.valueOf(configured && active[row].enabled)
        if (column == 4) return file.name.replaceFirst(/(?i)\.groovy$/, '')
        if (column == 5) return file.isFile() ? file.parentFile.name : 'MISSING'
        if (column == 6) return configured ? (active[row].group ?: 'Uncategorized') : 'Uncategorized'
        if (!configured) return ''
        def entry = active[row]
        if (column == 2) return triggerLabels[AutoRunDispatcher.TRIGGERS.indexOf(entry.trigger)]
        if (column == 3) return entry.trigger == 'PERIODIC' ? "${entry.everyMinutes} min".toString() : ''
        if (!entry.enabled) return '--'
        return String.valueOf(active.take(row).count { it.trigger == entry.trigger && it.enabled } + 1)
    }

    void setValueAt(Object value, int row, int column) {
        File file = fileAt(row)
        if (column == 0) {
            // switching off keeps the entry, its trigger, its place and its interval
            if (row < active.size()) active[row].enabled = (Boolean.TRUE == value)
            else if (Boolean.TRUE == value) activate(file, 'STARTUP', AutoRunDispatcher.DEFAULT_MINUTES, true)
            else return
        }
        else if (column == 2) {
            int index = triggerLabels.indexOf(String.valueOf(value))
            if (index < 0) return
            int minutes = row < active.size() ? active[row].everyMinutes : AutoRunDispatcher.DEFAULT_MINUTES
            boolean enabled = row < active.size() ? active[row].enabled : true
            String group = row < active.size() ? active[row].group : 'Uncategorized'
            activate(file, AutoRunDispatcher.TRIGGERS[index], minutes, group, enabled)
        }
        else if (column == 3) {
            if (row >= active.size()) return
            def digits = String.valueOf(value).replaceAll(/\D/, '')
            if (!digits) return
            active[row].everyMinutes = Math.max(1, digits as int)
        }
        else if (column == 6) {
            if (row >= active.size()) {
                activate(file, 'STARTUP', AutoRunDispatcher.DEFAULT_MINUTES,
                         String.valueOf(value), true)
            }
            else {
                String group = String.valueOf(value).trim()
                active[row].group = group ?: 'Uncategorized'
                onGroupChange(file)
            }
            fireTableDataChanged()
            onChange(file)
            return
        }
        else return
        fireTableDataChanged()
        onChange(file)
    }

    void activate(File file, String trigger, int everyMinutes, boolean enabled) {
        activate(file, trigger, everyMinutes, 'Uncategorized', enabled)
    }

    void activate(File file, String trigger, int everyMinutes, String group, boolean enabled) {
        String oldGroup = active.find { it.file == file }?.group ?: group ?: 'Uncategorized'
        active.removeAll { it.file == file }
        inactive.remove(file)
        active.add(new AutoRunEntry(file, trigger, everyMinutes, oldGroup, enabled))
        regroup()
    }

    // takes the entry off the list for good, unlike unticking it
    void remove(File file) {
        active.removeAll { it.file == file }
        if (file.isFile() && !inactive.contains(file)) {
            inactive.add(file)
            sortInactive()
        }
    }

    // stable sort: entries keep their relative order inside a trigger, and one whose trigger
    // just changed lands at the end of its new group because it was re-added last
    void regroup() {
        Collections.sort(active, { AutoRunEntry a, AutoRunEntry b ->
            AutoRunDispatcher.TRIGGERS.indexOf(a.trigger) <=> AutoRunDispatcher.TRIGGERS.indexOf(b.trigger)
        } as Comparator)
    }

    void sortInactive() {
        inactive.sort { File a, File b ->
            (a.parentFile.name + '/' + a.name).compareToIgnoreCase(b.parentFile.name + '/' + b.name)
        }
    }
}

def TRIGGER_LABELS = ['Startup', 'Map opened', 'Map closed', 'Tab created', 'Tab selected',
                      'Periodic', 'Freeplane closing']
def labelOf = { String key -> TRIGGER_LABELS[AutoRunDispatcher.TRIGGERS.indexOf(key)] }

def userDir = new File(ResourceController.resourceController.freeplaneUserDirectory)
def listFile = new File(userDir, 'autoRunScripts.txt')
// UI-only virtual grouping. This file never participates in execution and is independent
// from autoRunScripts.txt, so older/newer AutoRun readers can still use the execution list.
def groupFile = new File(userDir, 'autoRunScriptsGroups.txt')
def DEFAULT_GROUP = 'Uncategorized'
def dialogName = 'autoRunScriptsDialog'

// Freeplane runs, on its own, only what is in <user directory>/scripts/init, so a bridge has
// to live there. Keeping its source here means the file the button writes and the file this
// script expects can never drift apart.
def initScriptsDir = ScriptResources.getInitScriptsDir()
def bridgeSource = '''// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2954

// Freeplane only runs, by itself, the scripts in <user directory>/scripts/init. This file
// lives there and hands over to autoRunScripts.groovy, which lives with the other scripts.
// It is found by name in every folder Freeplane searches for scripts, so this file needs
// no editing wherever autoRunScripts.groovy was put.

import org.freeplane.core.resources.ResourceController
import org.freeplane.core.util.ConfigurationUtils
import org.freeplane.core.util.FileUtils
import org.freeplane.core.util.LogUtils
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.ScriptResources
import org.freeplane.plugin.script.ScriptingEngine

def resourceController = ResourceController.resourceController
def userDirectory = resourceController.freeplaneUserDirectory
def dirs = new LinkedHashSet<File>()
def configured = resourceController.getProperty('script_directories')
if (configured) {
    ConfigurationUtils.decodeListValue(configured, false).each { String path ->
        def trimmed = path.trim()
        if (trimmed) dirs.add(FileUtils.getAbsoluteFile(userDirectory, trimmed))
    }
}
dirs.add(ScriptResources.getUserScriptsDir())
dirs.add(FileUtils.getAbsoluteFile(resourceController.installationBaseDir, 'scripts'))
def script = dirs.collect { new File(it, 'autoRunScripts.groovy') }.find { it.isFile() }

if (script == null) {
    LogUtils.warn('autoRunScripts.groovy not found in any of the configured script directories')
}
else {
    // the flag tells it to install the triggers instead of opening the configuration dialog
    javax.swing.UIManager.put('autoRunScripts.bootstrap', Boolean.TRUE)
    ScriptingEngine.executeScript(Controller.currentController.selection?.selected, script, null)
}
'''

// any .groovy in there that mentions this script counts, whatever the file is called
def installedBridge = {
    def candidates = initScriptsDir.listFiles({ File f ->
        f.isFile() && f.name.toLowerCase().endsWith('.groovy')
    } as FileFilter)
    return candidates?.find { File f ->
        try { return f.getText('UTF-8').contains('autoRunScripts') }
        catch (Throwable ignored) { return false }
    }
}

// The same folders Freeplane itself scans, in the same way (ScriptingGuiConfiguration:215).
// The scripting preference is a list "in addition to scripts", so <user directory>/scripts
// and the built-in folder count even when it is empty -- reading only the preference left
// this window empty for anyone who keeps scripts where Freeplane puts them.
// Relative paths are resolved against the user directory, and the separator comes from
// Freeplane's own decoder: it is File.pathSeparator, ':' on Linux and Mac, not always ';'.
def searchedDirs = {
    def resourceController = ResourceController.resourceController
    def dirs = new LinkedHashSet<File>()
    def configured = resourceController.getProperty('script_directories')
    if (configured) {
        ConfigurationUtils.decodeListValue(configured, false).each { String path ->
            def trimmed = path.trim()
            if (trimmed) dirs.add(FileUtils.getAbsoluteFile(userDir.absolutePath, trimmed))
        }
    }
    dirs.add(ScriptResources.getUserScriptsDir())
    dirs.add(FileUtils.getAbsoluteFile(resourceController.installationBaseDir, 'scripts'))
    return new ArrayList<File>(dirs)
}

def scriptDirs = { searchedDirs().findAll { it.isDirectory() } }

def sanitizeGroupName = { String raw ->
    String name = String.valueOf(raw ?: '').replaceAll(/[\t\r\n]/, ' ').trim()
    // '/' is the internal hierarchy separator. A literal full-width slash is kept visually
    // similar while preventing an accidental extra level in the tree.
    name = name.replace('/', '／')
    return name
}

def normalizeGroupPath = { String raw ->
    def parts = String.valueOf(raw ?: '').split('/')
            .collect { sanitizeGroupName(it) }
            .findAll { it }
    return parts.join('/')
}

def groupParent = { String path ->
    int slash = path.lastIndexOf('/')
    return slash > 0 ? path.substring(0, slash) : null
}

def groupBase = { String path ->
    int slash = path.lastIndexOf('/')
    return slash >= 0 ? path.substring(slash + 1) : path
}

def groupDepth = { String path ->
    path ? path.count('/') : 0
}

def groupDisplay = { String path ->
    path.replace('/', ' / ')
}

def ensureGroupPathInList = { List<String> names, String rawPath ->
    String path = normalizeGroupPath(rawPath)
    if (!path) return
    def parts = path.split('/')
    String current = ''
    parts.each { String part ->
        current = current ? current + '/' + part : part
        if (!names.contains(current)) {
            if (!current.contains('/')) names << current
            else {
                String parent = groupParent(current)
                int insertAt = names.size()
                int parentIndex = names.indexOf(parent)
                if (parentIndex >= 0) {
                    insertAt = parentIndex + 1
                    while (insertAt < names.size() && names[insertAt].startsWith(parent + '/')) insertAt++
                }
                names.add(insertAt, current)
            }
        }
    }
}

def readGroupData = {
    def names = [DEFAULT_GROUP]
    def assignments = [:]
    if (!groupFile.isFile()) return [names: names, assignments: assignments]

    groupFile.readLines('UTF-8').each { String raw ->
        def line = raw.trim()
        if (!line) return
        if (line.startsWith('#GROUP\t')) {
            def path = normalizeGroupPath(line.substring('#GROUP\t'.length()).trim())
            if (path && path != DEFAULT_GROUP) ensureGroupPathInList(names, path)
            return
        }
        if (line.startsWith('#')) return
        def parts = line.split('\t', 2)
        if (parts.length < 2) return
        def path = parts[0].trim()
        def group = normalizeGroupPath(parts[1].trim())
        if (path && group) {
            ensureGroupPathInList(names, group)
            assignments[new File(path).absolutePath] = group
        }
    }
    return [names: names, assignments: assignments]
}

def writeGroupData = { List<String> names, List<AutoRunEntry> entries ->
    def cleanNames = []
    // Keep order exactly as the tree order. This is what makes Alt+Up/Down persistent.
    names.each { String raw ->
        String path = normalizeGroupPath(raw)
        if (path && !cleanNames.contains(path)) ensureGroupPathInList(cleanNames, path)
    }
    if (!cleanNames.contains(DEFAULT_GROUP)) cleanNames << DEFAULT_GROUP

    def lines = [
            '# Virtual groups for autoRunScripts.groovy.',
            '# This file is UI-only; it does not affect execution or triggers.',
            '# Format: #GROUP\\t<path> and <absolute path>\\t<group path>.',
            '# Group paths use / internally; a literal / in a group name is stored as ／.'
    ]
    cleanNames.each { lines << '#GROUP\t' + it }
    entries.each { entry ->
        String group = normalizeGroupPath(entry.group ?: DEFAULT_GROUP)
        if (!group) group = DEFAULT_GROUP
        if (group != DEFAULT_GROUP) {
            lines << entry.file.absolutePath.replace('\\', '/') + '\t' + group
        }
    }
    groupFile.setText(lines.join('\n') + '\n', 'UTF-8')
}

def readEntries = {
    def entries = []
    def groupData = readGroupData()
    if (!listFile.isFile()) return entries
    listFile.readLines('UTF-8').each { String raw ->
        def line = raw.trim()
        if (!line) return
        // a disabled entry is written as a comment, so that whoever runs the list -- this
        // script, or an older version of it -- skips it without having to know the marker
        boolean enabled = true
        if (line.startsWith(AutoRunDispatcher.DISABLED_MARK)) {
            enabled = false
            line = line.substring(AutoRunDispatcher.DISABLED_MARK.length())
        }
        else if (line.startsWith('#')) return
        def parts = line.split('\t')
        def hasTrigger = parts.length >= 2 && AutoRunDispatcher.TRIGGERS.contains(parts[0].trim())
        def trigger = hasTrigger ? parts[0].trim() : 'STARTUP'
        def path = hasTrigger ? parts[1].trim() : line
        int minutes = AutoRunDispatcher.DEFAULT_MINUTES
        if (parts.length >= 3 && parts[2].trim().isInteger()) minutes = Math.max(1, parts[2].trim() as int)
        if (path) {
            def file = new File(path)
            def group = groupData.assignments[file.absolutePath] ?: DEFAULT_GROUP
            entries << new AutoRunEntry(file, trigger, minutes, group, enabled)
        }
    }
    return entries
}

def writeEntries = { List entries ->
    def lines = ['# Scripts run automatically, one "<TRIGGER><tab><path>" per line, in order.',
                 '# A third field holds the interval in minutes, for PERIODIC.',
                 '# Lines starting with "' + AutoRunDispatcher.DISABLED_MARK.trim() + '" are switched off but keep their settings.',
                 '# Maintained by autoRunScripts.groovy.']
    lines += entries.collect {
        def line = it.trigger + '\t' + it.file.absolutePath.replace('\\', '/')
        if (it.trigger == 'PERIODIC') line += '\t' + it.everyMinutes
        return it.enabled ? line : AutoRunDispatcher.DISABLED_MARK + line
    }
    listFile.setText(lines.join('\n') + '\n', 'UTF-8')
}

// Legacy migration: earlier versions marked startup scripts with a '//init' line inside
// the file itself. Seed the list from those marks, once.
if (!listFile.isFile()) {
    def marked = []
    scriptDirs().each { dir ->
        def groovyFiles = dir.listFiles({ File f ->
            f.isFile() && f.name.toLowerCase().endsWith('.groovy')
        } as FileFilter)
        groovyFiles?.sort { it.name.toLowerCase() }?.each { File f ->
            f.withReader('UTF-8') { reader ->
                for (int i = 0; i < 15; i++) {
                    def line = reader.readLine()
                    if (line == null) break
                    if (line.trim() == '//init') { marked << f; break }
                }
            }
        }
    }
    LogUtils.info("creating ${listFile} from ${marked.size()} script(s) marked with //init")
    writeEntries(marked.collect { new AutoRunEntry(it, 'STARTUP', AutoRunDispatcher.DEFAULT_MINUTES, true) })
}

// Column widths are measured from the font in use, never guessed in pixels: with a scaled
// UI a hardcoded 80px turns "12:20:07" into "12:2...".
def columnWidthFor = { JTable table, int index, String widest ->
    def column = table.columnModel.getColumn(index)
    int content = table.getFontMetrics(table.font).stringWidth(widest)
    int header = table.tableHeader.getFontMetrics(table.tableHeader.font)
            .stringWidth(String.valueOf(column.headerValue))
    return Math.max(content, header) + 18
}

def fixColumn = { JTable table, int index, String widest ->
    int width = columnWidthFor(table, index, widest)
    table.columnModel.getColumn(index).with {
        minWidth = width
        maxWidth = width
        preferredWidth = width
    }
}

def preferColumn = { JTable table, int index, String typical ->
    table.columnModel.getColumn(index).preferredWidth = columnWidthFor(table, index, typical)
}

// row height from the font too: a guessed 20px clips the descenders of g, p and j
def fitRowHeight = { JTable table ->
    table.rowHeight = Math.max(table.rowHeight, table.getFontMetrics(table.font).height + 8)
}

def sizeTableToColumns = { JTable table, int visibleRows ->
    int width = (0..<table.columnCount).sum { table.columnModel.getColumn(it).preferredWidth }
    table.preferredScrollableViewportSize = new Dimension((int) width, table.rowHeight * visibleRows)
}

// pack() gives the dialog the size its content asks for; this keeps that size, and the
// dialog itself, inside the usable screen area -- which is what went wrong before.
def placeInsideScreen = { JDialog dialog, int offset ->
    def frame = UITools.currentFrame
    def configuration = frame?.graphicsConfiguration ?:
            GraphicsEnvironment.localGraphicsEnvironment.defaultScreenDevice.defaultConfiguration
    Rectangle screen = configuration.bounds
    Insets insets = Toolkit.defaultToolkit.getScreenInsets(configuration)
    int usableX = screen.x + insets.left
    int usableY = screen.y + insets.top
    int usableWidth = screen.width - insets.left - insets.right
    int usableHeight = screen.height - insets.top - insets.bottom

    int width = Math.min(dialog.width, usableWidth)
    int height = Math.min(dialog.height, usableHeight)
    dialog.setSize(width, height)

    int x = (frame ? (int) (frame.x + (frame.width - width) / 2) : usableX) + offset
    int y = (frame ? (int) (frame.y + (frame.height - height) / 2) : usableY) + offset
    dialog.setLocation(Math.max(usableX, Math.min(x, usableX + usableWidth - width)),
            Math.max(usableY, Math.min(y, usableY + usableHeight - height)))
}

// What ran, when, why, and how long it took. Memory only -- empty on every Freeplane start.
def openHistory = {
    def historyName = 'autoRunHistoryDialog'
    Window.windows.findAll { it.name == historyName && it.displayable }.each { it.dispose() }

    def prettyTrigger = { String key ->
        int index = AutoRunDispatcher.TRIGGERS.indexOf(key)
        return index >= 0 ? TRIGGER_LABELS[index] : key.toLowerCase().capitalize()
    }

    def model = new DefaultTableModel(['Time', 'Trigger', 'Script', 'Node', 'Map', 'ms', 'Result'] as String[], 0)
    // a failure message is the one cell that never fits, so every cell tells its whole content
    def table = new JTable(model) {
        @Override
        String getToolTipText(MouseEvent event) {
            int viewRow = rowAtPoint(event.getPoint())
            int viewColumn = columnAtPoint(event.getPoint())
            if (viewRow < 0 || viewColumn < 0) return null
            def value = getValueAt(viewRow, viewColumn)
            return value ? value.toString() : null
        }
    }
    table.setDefaultEditor(Object, null)
    fitRowHeight(table)
    fixColumn(table, 0, '00:00:00')
    fixColumn(table, 1, 'Freeplane closing')
    preferColumn(table, 2, 'a script with a fairly long name')
    preferColumn(table, 3, 'a node with a fairly long text')
    preferColumn(table, 4, 'a map name')
    fixColumn(table, 5, '99999')
    preferColumn(table, 6, 'IllegalStateException: message')
    sizeTableToColumns(table, 14)

    def rightAligned = new DefaultTableCellRenderer()
    rightAligned.horizontalAlignment = SwingConstants.RIGHT
    table.columnModel.getColumn(5).cellRenderer = rightAligned

    def count = new JLabel(' ')
    def refresh = {
        def list = AutoRunDispatcher.history()
        def snapshot
        synchronized (list) { snapshot = new ArrayList(list) }
        // rows written before a recompilation may be in an older shape; skip those
        snapshot = snapshot.findAll { it instanceof Map }
        if (model.rowCount == snapshot.size()) return
        model.rowCount = 0
        snapshot.each { row ->
            model.addRow([row.time, prettyTrigger(row.trigger as String), row.script,
                          row.node, row.map, row.millis, row.result] as Object[])
        }
        int failures = snapshot.count { it.result != 'ok' }
        count.text = failures
                ? "${model.rowCount} run(s) since Freeplane started, ${failures} failed"
                : "${model.rowCount} run(s) since Freeplane started"
        if (model.rowCount > 0) table.scrollRectToVisible(table.getCellRect(model.rowCount - 1, 0, true))
    }
    refresh()

    def clearButton = new JButton('Clear')
    clearButton.addActionListener({
        def list = AutoRunDispatcher.history()
        synchronized (list) { list.clear() }
        model.rowCount = 0
        count.text = '0 run(s) since Freeplane started'
    } as ActionListener)
    def closeHistoryButton = new JButton('Close')

    def bar = new JPanel(new BorderLayout())
    def leftBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6))
    leftBar.add(count)
    def rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6))
    rightBar.add(clearButton)
    rightBar.add(closeHistoryButton)
    bar.add(leftBar, BorderLayout.WEST)
    bar.add(rightBar, BorderLayout.EAST)

    def content = new JPanel(new BorderLayout())
    content.add(new JScrollPane(table), BorderLayout.CENTER)
    content.add(bar, BorderLayout.SOUTH)

    def dialog = new JDialog(UITools.currentFrame, 'Auto-run history', false)
    dialog.name = historyName
    dialog.contentPane = content
    dialog.pack()
    // DISPOSE, not the default HIDE: otherwise windowClosed never fires and the timer lives on
    dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
    closeHistoryButton.addActionListener({ dialog.dispose() } as ActionListener)
    dialog.rootPane.registerKeyboardAction({ dialog.dispose() } as ActionListener,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)

    // keeps updating while open, so triggers can be watched as they happen
    // qualified: java.util.Timer is a Groovy default import and would clash with javax.swing.*
    def timer = new javax.swing.Timer(1000, { refresh() } as ActionListener)
    timer.start()
    dialog.addWindowListener(new WindowAdapter() {
        @Override
        void windowClosed(WindowEvent event) { timer.stop() }
    })

    // offset so it does not land exactly on top of the configuration dialog
    placeInsideScreen(dialog, 60)
    dialog.visible = true
    return dialog
}

// A script that silently stopped running is worse than one that never ran, so the first
// failure of the session opens the history by itself. Later ones only go to the status bar,
// otherwise a script broken on VIEW_SELECTED would pop a window on every tab change.
def notifyFailure = { String trigger, List failed ->
    def message = "Auto-run failed on ${labelOf(trigger) ?: trigger}: ${failed.join('; ')}"
    Controller.currentController?.viewController?.out(message)
    if (UIManager.get(AutoRunDispatcher.NOTIFIED_KEY) != null) return
    UIManager.put(AutoRunDispatcher.NOTIFIED_KEY, Boolean.TRUE)
    SwingUtilities.invokeLater({ openHistory() } as Runnable)
}

// Installs the triggers, replacing whatever a previous run of this script left behind.
// runStartupScripts is false when the dialog installs them just to make the window honest
// about what is armed -- opening a window must not execute anybody's startup scripts.
def install = { boolean runStartupScripts ->
    def controller = Controller.currentController
    def mapController = controller.modeController.mapController
    def mapViewManager = controller.mapViewManager

    def generation = ((UIManager.get(AutoRunDispatcher.GENERATION_KEY) ?: Integer.valueOf(0)) as int) + 1
    UIManager.put(AutoRunDispatcher.GENERATION_KEY, Integer.valueOf(generation))

    def previousView = UIManager.get(AutoRunDispatcher.VIEW_LISTENER_KEY)
    if (previousView instanceof IMapViewChangeListener) mapViewManager.removeMapViewChangeListener(previousView)
    def previousMap = UIManager.get(AutoRunDispatcher.MAP_LISTENER_KEY)
    if (previousMap instanceof IMapLifeCycleListener) mapController.removeMapLifeCycleListener(previousMap)
    new ArrayList(mapController.mapLifeCycleListeners)
            .findAll { it.class.simpleName == 'AutoRunMapTrigger' }
            .each { mapController.removeMapLifeCycleListener(it) }
    def previousTicker = UIManager.get(AutoRunDispatcher.TICKER_KEY)
    if (previousTicker instanceof javax.swing.Timer) previousTicker.stop()

    def dispatcher = new AutoRunDispatcher(listFile)
    dispatcher.failureNotifier = notifyFailure
    def mapListener = new AutoRunMapTrigger(dispatcher, generation)
    def viewListener = new AutoRunViewTrigger(dispatcher, generation)
    def shutdownListener = new AutoRunShutdownTrigger(dispatcher, generation)
    mapController.addMapLifeCycleListener(mapListener)
    mapViewManager.addMapViewChangeListener(viewListener)
    // Controller.fireStartupFinished() walks its listener list without copying it first
    // (Controller.java:308-312), and init scripts run inside that very loop -- registering
    // straight away throws ConcurrentModificationException and kills the rest of the startup.
    // The other two listener lists are safe: both fire over a toArray() copy.
    SwingUtilities.invokeLater({
        if (!AutoRunDispatcher.isStale(generation)) controller.addApplicationLifecycleListener(shutdownListener)
    } as Runnable)
    UIManager.put(AutoRunDispatcher.MAP_LISTENER_KEY, mapListener)
    UIManager.put(AutoRunDispatcher.VIEW_LISTENER_KEY, viewListener)
    // published for diagnostics: lets the pieces be inspected or driven without waiting
    // for the real event -- a periodic interval to elapse, or Freeplane to be closed
    UIManager.put('autoRunScripts.shutdownListener', shutdownListener)
    UIManager.put('autoRunScripts.dispatcher', dispatcher)

    // one ticker for every periodic script; it asks the dispatcher which ones are due
    def ticker = new javax.swing.Timer(30000, { event ->
        if (AutoRunDispatcher.isStale(generation)) {
            ((javax.swing.Timer) event.source).stop()
            return
        }
        dispatcher.runDuePeriodic(Controller.currentController?.selection?.selected)
    } as ActionListener)
    ticker.start()
    UIManager.put(AutoRunDispatcher.TICKER_KEY, ticker)

    if (!runStartupScripts) return 'Auto-run triggers installed.'

    def startup = dispatcher.run('STARTUP', controller.selection?.selected)

    // Maps restored at startup were loaded before init scripts run, so MAP_OPENED never
    // fired for them. Catch up, otherwise 'when a map opens' silently skips this session.
    int caughtUp = 0
    def failedCatchUp = []
    if (dispatcher.entriesFor('MAP_OPENED')) {
        // copy first: unique() mutates its receiver, and getMaps() hands out an unmodifiable view
        new ArrayList(mapViewManager.maps.values()).unique { System.identityHashCode(it) }.each { MapModel map ->
            def result = dispatcher.run('MAP_OPENED', map.rootNode)
            caughtUp += result.ok
            failedCatchUp += result.failed
        }
    }

    def armed = AutoRunDispatcher.TRIGGERS.findAll { it != 'STARTUP' }
            .collectEntries { [(it): dispatcher.entriesFor(it).size()] }.findAll { it.value }
    def failed = startup.failed + failedCatchUp
    def message = new StringBuilder("Auto-run scripts: ${startup.ok + caughtUp} executed")
    if (armed) message.append(', armed ').append(armed.collect { "${it.value} on ${labelOf(it.key)}" }.join(', '))
    if (failed) message.append(' -- failed: ').append(failed.join('; '))
    return message.toString()
}

def openDialog = {
    // getWindows() keeps listing disposed windows until they are collected, hence displayable
    Window.windows.findAll { it.name == dialogName && it.displayable }.each { it.dispose() }

    def model = new AutoRunTableModel()
    model.triggerLabels = TRIGGER_LABELS
    def groupData = readGroupData()
    model.groupLabels = new ArrayList<String>(groupData.names)
    model.active.addAll(readEntries())
    model.active.each { entry ->
        entry.group = groupData.assignments[entry.file.absolutePath] ?: (entry.group ?: DEFAULT_GROUP)
    }
    model.regroup()
    def activeFiles = model.active.collect { it.file }
    scriptDirs().each { dir ->
        def groovyFiles = dir.listFiles({ File f ->
            f.isFile() && f.name.toLowerCase().endsWith('.groovy')
        } as FileFilter)
        groovyFiles?.each { File f -> if (!activeFiles.contains(f)) model.inactive.add(f) }
    }
    model.sortInactive()

    // The folder column shows the folder name alone, and two folders can be called "scripts":
    // the one in the search path and the one Freeplane ships. Hence the full path in a tooltip.
    def table = new JTable(model) {
        @Override
        String getToolTipText(MouseEvent event) {
            int viewRow = rowAtPoint(event.getPoint())
            if (viewRow < 0) return null
            def file = ((AutoRunTableModel) getModel()).fileAt(convertRowIndexToModel(viewRow))
            return file.isFile() ? file.absolutePath : file.absolutePath + '  (missing)'
        }
    }
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    fitRowHeight(table)
    
    fixColumn(table, 0, 'On')
    fixColumn(table, 1, '99')
    // extra room: while editing, the combo box puts an arrow button inside this column
    fixColumn(table, 2, 'Freeplane closing     ')
    fixColumn(table, 3, '999 min')
    preferColumn(table, 4, 'a script with a fairly long name')
    preferColumn(table, 5, 'compartilhados')
    preferColumn(table, 6, 'Uncategorized')
    sizeTableToColumns(table, 16)
    table.columnModel.getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox(TRIGGER_LABELS as String[])))
    def makeGroupCombo = {
    def combo = new JComboBox(model.groupLabels as String[])
    combo.maximumRowCount = 15          // بعد از ۱۵ آیتم، اسکرول ظاهر می‌شود
    combo.setPrototypeDisplayValue('a group name of medium length')
    return combo
}
table.columnModel.getColumn(6).setCellEditor(new DefaultCellEditor(makeGroupCombo()))

    def sorter = new TableRowSorter<AutoRunTableModel>(model)
    // On و # غیرفعال، بقیه فعال
    sorter.setSortable(0, false)
    sorter.setSortable(1, false)
    sorter.setSortable(2, true)
    sorter.setSortable(3, true)
    sorter.setSortable(4, true)
    sorter.setSortable(5, true)
    sorter.setSortable(6, true)
    table.setRowSorter(sorter)
    table.dragEnabled = true
    table.transferHandler = new TransferHandler() {

        @Override
        int getSourceActions(JComponent component) {
            return MOVE
        }

        @Override
        Transferable createTransferable(JComponent component) {
            int viewRow = table.selectedRow

            if (viewRow < 0)
                return null

            int modelRow =
                    table.convertRowIndexToModel(viewRow)

            if (modelRow < 0 ||
                    modelRow >= model.rowCount)
                return null

            File file = model.fileAt(modelRow)

            if (file == null)
                return null

            return new StringSelection(
                    'SCRIPT:' + file.absolutePath
            )
        }

        @Override
        boolean canImport(TransferSupport support) {
            return false
        }

    }

    def status = new JLabel(' ')
    def filterField = new JTextField(18)

    def describe = {
        def counts = AutoRunDispatcher.TRIGGERS.collectEntries { key ->
            [(key): model.active.count { it.trigger == key && it.enabled }]
        }.findAll { it.value }
        def text = counts ? counts.collect { "${it.value} on ${labelOf(it.key)}" }.join(', ') : 'nothing runs automatically'
        int off = model.active.count { !it.enabled }
        if (off) text += ", ${off} switched off"
        if (AutoRunDispatcher.isPaused()) text = "PAUSED -- ${text}"
        return text
    }

    def selectFile = { File file ->
        int modelRow = (0..<model.rowCount).find { model.fileAt(it) == file }
        if (modelRow == null) return
        int viewRow = table.convertRowIndexToView(modelRow)
        if (viewRow < 0) return
        table.setRowSelectionInterval(viewRow, viewRow)
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
    }

    model.onChange = { File file ->
        writeEntries(model.active)
        writeGroupData(model.groupLabels, model.active)
        status.text = "Saved: ${describe()}."
        selectFile(file)
    }
    model.onGroupChange = { File file ->
        writeGroupData(model.groupLabels, model.active)
    }

    def refreshGroupEditor = {
        def combo = new JComboBox(model.groupLabels as String[])
        combo.maximumRowCount = 20
        combo.setPrototypeDisplayValue('a group name of medium length')
        table.columnModel.getColumn(6).setCellEditor(new DefaultCellEditor(combo))
    }

    // ------------------------------------------------------------
    // NESTED GROUP TREE
    // ------------------------------------------------------------

    def activeGroupId = null
    def groupsPopup = null
    def groupManagerDialog = null
    def groupsButton = null
    def openGroupManager = null
        def uiParent = {
            return (Object) UITools.currentFrame
        }

    def directChildren = { String parent ->
        String prefix = parent ? parent + '/' : ''
        model.groupLabels.findAll { String path ->
            if (parent == null) return !path.contains('/')
            return path.startsWith(prefix) && !path.substring(prefix.length()).contains('/')
        }
    }

    def subtree = { String group ->
        String prefix = group + '/'
        model.groupLabels.findAll { it == group || it.startsWith(prefix) }
    }

    def siblingGroups = { String group ->
        String parent = groupParent(group)
        directChildren(parent)
    }

    def remapGroupPrefix = { String oldPrefix, String newPrefix ->
        model.groupLabels.replaceAll { String path ->
            if (path == oldPrefix || path.startsWith(oldPrefix + '/')) {
                return newPrefix + path.substring(oldPrefix.length())
            }
            return path
        }
        model.active.each { entry ->
            String g = entry.group ?: DEFAULT_GROUP
            if (g == oldPrefix || g.startsWith(oldPrefix + '/')) {
                entry.group = newPrefix + g.substring(oldPrefix.length())
            }
        }
    }

    def insertGroupAt = { String path, int index ->
        model.groupLabels.remove(path)
        int safe = Math.max(0, Math.min(index, model.groupLabels.size()))
        model.groupLabels.add(safe, path)
    }

    def refreshGroupUI = {
        model.groupLabels = new ArrayList<String>(model.groupLabels.unique())
        refreshGroupEditor()
        model.active.each { entry ->
            entry.group = entry.group ?: DEFAULT_GROUP
        }
        model.fireTableDataChanged()
        writeGroupData(model.groupLabels, model.active)
        table.repaint()
    }

    def createGroup = { String parent, boolean assignSelected ->
        // CHANGED: uiParent() instead of table
        def raw = JOptionPane.showInputDialog(
                uiParent(),
                parent ? "Child group of '${groupDisplay(parent)}':" : 'Group name:',
                parent ? 'New child group' : 'New virtual group',
                JOptionPane.PLAIN_MESSAGE
        )
        if (raw == null) return null
        String name = sanitizeGroupName(raw)
        if (!name) return null

        String path = parent ? parent + '/' + name : name
        if (model.groupLabels.contains(path)) {
            // CHANGED: uiParent() instead of table
            JOptionPane.showMessageDialog(uiParent(), 'That group already exists.',
                    'Virtual script groups', JOptionPane.WARNING_MESSAGE)
            return null
        }
        if (path == DEFAULT_GROUP || path.startsWith(DEFAULT_GROUP + '/') && name == DEFAULT_GROUP) {
            // CHANGED: uiParent() instead of table
            JOptionPane.showMessageDialog(uiParent(), 'That group name is reserved.',
                    'Virtual script groups', JOptionPane.WARNING_MESSAGE)
            return null
        }

        if (parent) {
            def children = directChildren(parent)
            int parentIndex = model.groupLabels.indexOf(parent)
            int insertAt = parentIndex + 1
            while (insertAt < model.groupLabels.size() &&
                    model.groupLabels[insertAt].startsWith(parent + '/')) insertAt++
            model.groupLabels.add(insertAt, path)
        }
        else {
            int insertAt = model.groupLabels.size()
            int uncategorizedIndex = model.groupLabels.indexOf(DEFAULT_GROUP)
            if (uncategorizedIndex >= 0) insertAt = uncategorizedIndex
            model.groupLabels.add(insertAt, path)
        }

        if (assignSelected) {
            def rows = table.selectedRows.collect { table.convertRowIndexToModel(it) }.unique()
            rows.each { int row ->
                if (row >= 0 && row < model.active.size()) model.active[row].group = path
            }
        }

        activeGroupId = path
        refreshGroupUI()
        status.text = assignSelected && table.selectedRows
                ? "Group created: ${groupDisplay(path)}."
                : "Group created: ${groupDisplay(path)}."
        return path
    }

    def renameGroup = { String oldPath ->
        if (!oldPath || oldPath == DEFAULT_GROUP) {
            // CHANGED: uiParent() instead of table
            JOptionPane.showMessageDialog(uiParent(), 'The Uncategorized group cannot be renamed.')
            return false
        }
        String parent = groupParent(oldPath)
        // CHANGED: uiParent() instead of groupManagerDialog
        String raw = JOptionPane.showInputDialog(uiParent(), 'New group name:', groupBase(oldPath))
        if (raw == null) return false
        String name = sanitizeGroupName(raw)
        if (!name || name == groupBase(oldPath)) return false
        String newPath = parent ? parent + '/' + name : name
        if (model.groupLabels.contains(newPath)) {
            // CHANGED: uiParent() instead of table
            JOptionPane.showMessageDialog(uiParent(), 'A sibling with that name already exists.',
                    'Virtual script groups', JOptionPane.WARNING_MESSAGE)
            return false
        }
        remapGroupPrefix(oldPath, newPath)
        activeGroupId = newPath
        refreshGroupUI()
        status.text = "Group renamed: ${groupDisplay(oldPath)} → ${groupDisplay(newPath)}."
        return true
    }

    def deleteGroup = { String path ->
        if (!path || path == DEFAULT_GROUP) {
            // CHANGED: uiParent() instead of table
            JOptionPane.showMessageDialog(uiParent(), 'The Uncategorized group cannot be deleted.')
            return false
        }
        def descendants = subtree(path)
        String parent = groupParent(path)
        String destination = DEFAULT_GROUP
        String warning = descendants.size() > 1
                ? "Delete '${groupDisplay(path)}' and its ${descendants.size() - 1} child group(s)?\nScripts in them will move to '${groupDisplay(destination)}'."
                : "Delete '${groupDisplay(path)}'?\nIts scripts will move to '${groupDisplay(destination)}'."
        // CHANGED: uiParent() instead of table
        int answer = JOptionPane.showConfirmDialog(uiParent(), warning,
                'Delete virtual group', JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        if (answer != JOptionPane.YES_OPTION) return false

        model.active.each { entry ->
            String g = entry.group ?: DEFAULT_GROUP
            if (g == path || g.startsWith(path + '/')) entry.group = destination
        }
        model.groupLabels.removeAll(descendants)
        activeGroupId = destination
        refreshGroupUI()
        status.text = "Group deleted: ${groupDisplay(path)}."
        return true
    }

    def groupIcon = UIManager.getIcon('FileView.directoryIcon')
    def scriptIcon = UIManager.getIcon('FileView.fileIcon')

    if (groupIcon == null)
        groupIcon = UIManager.getIcon('FileView.directory')

    if (scriptIcon == null)
        scriptIcon = UIManager.getIcon('FileView.file')

    def moveGroupInto = { String sourceGroup, String targetGroup ->
        if (!sourceGroup || !targetGroup) return false
        if (sourceGroup == DEFAULT_GROUP) return false
        if (sourceGroup == targetGroup) return false

        // A group cannot be dropped onto itself or one of its descendants.
        if (targetGroup.startsWith(sourceGroup + '/')) {
            status.text = "Cannot move '${groupDisplay(sourceGroup)}' into itself."
            return false
        }

        String newPath = targetGroup + '/' + groupBase(sourceGroup)

        // Do not overwrite an existing sibling.
        if (model.groupLabels.contains(newPath)) {
            status.text = "A group named '${groupDisplay(newPath)}' already exists."
            return false
        }

        def block = subtree(sourceGroup)

        // Remove the complete subtree first.
        model.groupLabels.removeAll(block)

        // Rebuild every path below the new parent.
        def movedBlock = block.collect { String path ->
            newPath + path.substring(sourceGroup.length())
        }

        // Insert immediately after the target's complete subtree.
        int targetIndex = model.groupLabels.indexOf(targetGroup)
        if (targetIndex < 0) return false

        int insertAt = targetIndex + 1
        while (insertAt < model.groupLabels.size() &&
                model.groupLabels[insertAt].startsWith(targetGroup + '/')) {
            insertAt++
        }

        model.groupLabels.addAll(insertAt, movedBlock)

        // Move all scripts belonging to the subtree.
        model.active.each { entry ->
            String g = entry.group ?: DEFAULT_GROUP
            if (g == sourceGroup || g.startsWith(sourceGroup + '/')) {
                entry.group = newPath + g.substring(sourceGroup.length())
            }
        }

        activeGroupId = newPath
        refreshGroupUI()

        status.text =
                "Moved '${groupDisplay(sourceGroup)}' into '${groupDisplay(targetGroup)}'."

        return true
    }

    def assignScriptToGroup = { File file, String group ->
        if (!file || !group) return false

        def entry = model.active.find { it.file == file }

        if (entry == null) {
            status.text = "${file.name} is not an active script."
            return false
        }

        entry.group = group
        activeGroupId = group

        refreshGroupUI()
        writeGroupData(model.groupLabels, model.active)
        selectFile(file)

        status.text =
                "Moved '${file.name}' to '${groupDisplay(group)}'."

        return true
    }

    def lastDragMenu = null

    def installGroupDropTarget
    def installGroupDragSource

    installGroupDropTarget = { JMenu menu, String group ->

        Closure activateMenu = {
            String previousGroup = activeGroupId

            if (lastDragMenu != null && lastDragMenu != menu) {
                try {
                    boolean movingIntoChild =
                            previousGroup &&
                            group.startsWith(previousGroup + '/')

                    if (!movingIntoChild) {
                        lastDragMenu.setSelected(false)
                        lastDragMenu.getModel().setArmed(false)
                        lastDragMenu.getModel().setRollover(false)

                        if (lastDragMenu.isPopupMenuVisible()) {
                            lastDragMenu.setPopupMenuVisible(false)
                        }
                    }
                }
                catch (Throwable ignored) {
                }
            }

            lastDragMenu = menu
            activeGroupId = group

            try {
                menu.setSelected(true)
                menu.getModel().setArmed(true)
                menu.getModel().setRollover(true)

                if (menu.menuComponentCount > 0 &&
                        !menu.isPopupMenuVisible()) {
                    menu.setPopupMenuVisible(true)
                }
            }
            catch (Throwable ignored) {
            }
        }

        new DropTarget(
                menu,
                DnDConstants.ACTION_MOVE,
                new DropTargetAdapter() {

                    @Override
                    void dragEnter(DropTargetDragEvent event) {
                        if (!event.currentDataFlavors.any {
                            it.equals(DataFlavor.stringFlavor)
                        }) {
                            event.rejectDrag()
                            return
                        }

                        event.acceptDrag(DnDConstants.ACTION_MOVE)

                        SwingUtilities.invokeLater({
                            activateMenu()
                        } as Runnable)
                    }

                    @Override
                    void dragOver(DropTargetDragEvent event) {
                        if (!event.currentDataFlavors.any {
                            it.equals(DataFlavor.stringFlavor)
                        }) {
                            event.rejectDrag()
                            return
                        }

                        event.acceptDrag(DnDConstants.ACTION_MOVE)

                        SwingUtilities.invokeLater({
                            activateMenu()
                        } as Runnable)
                    }

                    @Override
                    void dragExit(DropTargetEvent event) {
                        // عمداً چیزی را خاموش نمی‌کنیم.
                        // وقتی درگ مستقیماً وارد گروه بعدی شود،
                        // activateMenu() گروه قبلی را خاموش می‌کند.
                    }

                    @Override
                    void drop(DropTargetDropEvent event) {
                        boolean accepted = false

                        try {
                            if (!event.transferable.isDataFlavorSupported(
                                    DataFlavor.stringFlavor)) {
                                event.rejectDrop()
                                return
                            }

                            String value = String.valueOf(
                                    event.transferable.getTransferData(
                                            DataFlavor.stringFlavor))

                            event.acceptDrop(DnDConstants.ACTION_MOVE)

                            if (value.startsWith('SCRIPT:')) {
                                String path =
                                        value.substring('SCRIPT:'.length())

                                File file = new File(path)

                                accepted =
                                        assignScriptToGroup(file, group)
                            }
                            else if (value.startsWith('GROUP:')) {
                                String sourceGroup =
                                        value.substring('GROUP:'.length())

                                accepted =
                                        moveGroupInto(sourceGroup, group)
                            }

                            event.dropComplete(accepted)

                            if (accepted && groupsPopup != null)
                                groupsPopup.setVisible(false)

                            if (accepted) {
                                lastDragMenu = null
                            }
                        }
                        catch (Throwable t) {
                            LogUtils.warn(
                                    'AutoRun Scripts: group drop failed.',
                                    t
                            )

                            status.text =
                                    "Drop failed: ${t.message}"

                            try {
                                event.dropComplete(false)
                            }
                            catch (Throwable ignored) {
                            }
                        }
                    }
                },
                true
        )
    }

    installGroupDragSource = { JMenu menu, String group ->
        DragSource.getDefaultDragSource()
                .createDefaultDragGestureRecognizer(
                        menu,
                        DnDConstants.ACTION_MOVE,
                        new DragGestureListener() {

                            @Override
                            void dragGestureRecognized(DragGestureEvent event) {
                                if (!group ||
                                        group == DEFAULT_GROUP ||
                                        !model.groupLabels.contains(group)) {
                                    return
                                }

                                try {
                                    event.startDrag(
                                            DragSource.DefaultMoveDrop,
                                            new StringSelection(
                                                    'GROUP:' + group
                                            ),
                                            new DragSourceAdapter() {
                                                @Override
                                                void dragDropEnd(
                                                        java.awt.dnd.DragSourceDropEvent e) {
                                                }
                                            }
                                    )
                                }
                                catch (Throwable t) {
                                    LogUtils.warn(
                                            'AutoRun Scripts: could not start group drag.',
                                            t
                                    )
                                }
                            }
                        }
                )
    }

    def showGroupsFlyout = {
        if (groupsPopup?.visible)
            groupsPopup.setVisible(false)

        groupsPopup = new JPopupMenu()

        groupsPopup.border =
                BorderFactory.createLineBorder(
                        UIManager.getColor('Separator.foreground')
                )

        Closure buildMenu

        buildMenu = { String group ->
            def menu = new JMenu(groupBase(group))
            menu.toolTipText = groupDisplay(group)

            if (groupIcon != null)
                menu.icon = groupIcon

            menu.addMenuListener(new MenuListener() {

                @Override
                void menuSelected(MenuEvent e) {
                    activeGroupId = group
                }

                @Override
                void menuDeselected(MenuEvent e) {
                }

                @Override
                void menuCanceled(MenuEvent e) {
                }
            })

            installGroupDropTarget(menu, group)
            installGroupDragSource(menu, group)

            def children = directChildren(group)

            children.each { String child ->
                menu.add(buildMenu(child))
            }

            def scripts = model.active.findAll {
                (it.group ?: DEFAULT_GROUP) == group
            }

            if (children && scripts)
                menu.addSeparator()

            scripts.each { entry ->
                def item = new JMenuItem(
                        entry.file.name.replaceFirst(
                                /(?i)\.groovy$/,
                                ''
                        )
                )

                if (scriptIcon != null)
                    item.icon = scriptIcon

                item.toolTipText = entry.file.absolutePath

                item.addActionListener({
                    selectFile(entry.file)

                    if (groupsPopup != null)
                        groupsPopup.setVisible(false)
                } as ActionListener)

                menu.add(item)
            }

            if (children || scripts)
                menu.addSeparator()

            def newChild =
                    new JMenuItem('New child group...')

            newChild.addActionListener({
                if (groupsPopup != null)
                    groupsPopup.setVisible(false)

                SwingUtilities.invokeLater({
                    String created =
                            createGroup(group, false)

                    if (created)
                        showGroupsFlyout()
                } as Runnable)
            } as ActionListener)

            menu.add(newChild)
            // ---------- Rename و Delete داخل منوی گروه ----------
            if (group != DEFAULT_GROUP) {
                menu.addSeparator()
                def renameMi = new JMenuItem('Rename...')
                renameMi.addActionListener({
                    if (groupsPopup != null) groupsPopup.setVisible(false)
                    SwingUtilities.invokeLater({
                        if (renameGroup(group)) showGroupsFlyout()
                    } as Runnable)
                } as ActionListener)
                menu.add(renameMi)
            
                def deleteMi = new JMenuItem('Delete')
                deleteMi.addActionListener({
                    if (groupsPopup != null) groupsPopup.setVisible(false)
                    SwingUtilities.invokeLater({
                        if (deleteGroup(group)) showGroupsFlyout()
                    } as Runnable)
                } as ActionListener)
                menu.add(deleteMi)
            }
            return menu
        }

        directChildren(null).each { String rootGroup ->
            groupsPopup.add(buildMenu(rootGroup))
        }

        if (groupsPopup.componentCount > 0)
            groupsPopup.addSeparator()

        def newRoot =
                new JMenuItem('New root group...')

        newRoot.addActionListener({
            if (groupsPopup != null)
                groupsPopup.setVisible(false)

            SwingUtilities.invokeLater({
                String created =
                        createGroup(null, false)

                if (created)
                    showGroupsFlyout()
            } as Runnable)
        } as ActionListener)

        groupsPopup.add(newRoot)

        def manage =
                new JMenuItem('Manage groups...')

        manage.addActionListener({
            if (groupsPopup != null)
                groupsPopup.setVisible(false)

            SwingUtilities.invokeLater({
                openGroupManager()
            } as Runnable)
        } as ActionListener)

        groupsPopup.add(manage)

        groupsPopup.show(
                groupsButton,
                0,
                groupsButton.height
        )
    }

    openGroupManager = {
        def managerName = 'autoRunScriptsGroupManager'
        Window.windows.findAll { it.name == managerName && it.displayable }.each { it.dispose() }

        def listModel = new DefaultListModel<String>()
        def groupList = new JList<String>(listModel)
        groupList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        groupList.visibleRowCount = 12
        groupList.cellRenderer = new DefaultListCellRenderer() {
            @Override
            Component getListCellRendererComponent(JList list, Object value, int index,
                                                   boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused)
                int depth = groupDepth(String.valueOf(value))
                text = ('    ' * depth) + groupBase(String.valueOf(value))
                toolTipText = groupDisplay(String.valueOf(value))
                return this
            }
        }
        //groupList.dragEnabled = true
        groupList.dropMode = DropMode.INSERT

        groupList.transferHandler = new TransferHandler() {

            @Override
            int getSourceActions(JComponent component) {
                return MOVE
            }

            @Override
            Transferable createTransferable(JComponent component) {
                def list = (JList) component
                int index = list.selectedIndex
                if (index < 0 || index >= model.groupLabels.size()) return null

                String path = model.groupLabels[index]

                if (!path || path == DEFAULT_GROUP) return null

                return new StringSelection('GROUP:' + path)
            }

            @Override
            boolean canImport(TransferSupport support) {
                if (!support.isDataFlavorSupported(DataFlavor.stringFlavor))
                    return false

                if (!(support.dropLocation instanceof JList.DropLocation))
                    return false

                return true
            }

            @Override
            boolean importData(TransferSupport support) {
                if (!canImport(support))
                    return false

                try {
                    String value = String.valueOf(
                            support.transferable.getTransferData(
                                    DataFlavor.stringFlavor))

                    if (!value.startsWith('GROUP:'))
                        return false

                    String sourceGroup = value.substring(6)

                    if (!sourceGroup ||
                            sourceGroup == DEFAULT_GROUP ||
                            !model.groupLabels.contains(sourceGroup))
                        return false

                    def dl = (JList.DropLocation) support.dropLocation
                    int targetIndex = dl.index

                    if (targetIndex < 0)
                        targetIndex = model.groupLabels.size()

                    int sourceIndex = model.groupLabels.indexOf(sourceGroup)

                    if (sourceIndex < 0)
                        return false

                    /*
                     * یک گروه نمی‌تواند داخل خودش قرار بگیرد.
                     * در JList فقط ترتیب خطی را جابه‌جا می‌کنیم؛
                     * ساختار parent/child از مسیرهای / حفظ می‌شود.
                     */

                    def block = subtree(sourceGroup)

                    /*
                     * اگر مقصد داخل خود block باشد، عملیات بی‌معناست.
                     */
                    if (targetIndex >= sourceIndex &&
                            targetIndex <= sourceIndex + block.size()) {
                        return false
                    }

                    /*
                     * کل زیرشاخه را یک‌جا خارج می‌کنیم.
                     */
                    model.groupLabels.removeAll(block)

                    /*
                     * محل واقعی درج را بعد از حذف محاسبه می‌کنیم.
                     */
                    int adjustedIndex = targetIndex

                    if (sourceIndex < targetIndex)
                        adjustedIndex -= block.size()

                    adjustedIndex = Math.max(
                            0,
                            Math.min(
                                    adjustedIndex,
                                    model.groupLabels.size()
                            )
                    )

                    /*
                     * گروه و تمام فرزندانش باید با هم جابه‌جا شوند.
                     */
                    model.groupLabels.addAll(
                            adjustedIndex,
                            block
                    )

                    refreshGroupUI()

                    activeGroupId = sourceGroup

                    refresh(sourceGroup)

                    status.text =
                            "Group order changed: ${groupDisplay(sourceGroup)}."

                    return true
                }
                catch (Throwable t) {
                    LogUtils.warn(
                            'Could not reorder groups by drag and drop.',
                            t
                    )

                    status.text =
                            "Could not move group: ${t.message}"

                    return false
                }
            }
        }
        def refresh = { String selectedPath = activeGroupId ->
            listModel.clear()
            model.groupLabels.each { listModel.addElement(it) }
            if (selectedPath && model.groupLabels.contains(selectedPath)) {
                groupList.selectedIndex = model.groupLabels.indexOf(selectedPath)
            }
        }
        refresh()

        // -----------------------------------------------------------------
        // CHANGED: mouse handling
        //   - Left click on a group opens the Rename dialog.
        //   - Right click opens a small popup menu with Rename / Delete.
        //   - The Uncategorized group is protected (Rename/Delete disabled).
        // -----------------------------------------------------------------
        // ---------- Right-click: select item under cursor ----------
        groupList.addMouseListener(new MouseAdapter() {
            @Override
            void mousePressed(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    int idx = groupList.locationToIndex(event.point)
                    if (idx < 0) return
                    Rectangle r = groupList.getCellBounds(idx, idx)
                    if (r == null || !r.contains(event.point)) return
                    groupList.setSelectedIndex(idx)
                    activeGroupId = model.groupLabels[idx]
                }
            }
        
            @Override
            void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                if (event.clickCount != 1) return
                int idx = groupList.locationToIndex(event.point)
                if (idx < 0) return
                Rectangle r = groupList.getCellBounds(idx, idx)
                if (r == null || !r.contains(event.point)) return
                String sel = model.groupLabels[idx]
                if (sel == DEFAULT_GROUP) return
                activeGroupId = sel
                if (renameGroup(sel)) refresh(activeGroupId)
            }
        })
        
        // ---------- Popup via setComponentPopupMenu (proven to work) ----------
        def listRenameItem = new JMenuItem('Rename...')
        def listDeleteItem = new JMenuItem('Delete')
        def listPopup = new JPopupMenu()
        listPopup.add(listRenameItem)
        listPopup.add(listDeleteItem)
        
        listPopup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                int idx = groupList.selectedIndex
                boolean isDefault = (idx < 0) ||
                        (idx < model.groupLabels.size() && model.groupLabels[idx] == DEFAULT_GROUP)
                listRenameItem.enabled = !isDefault
                listDeleteItem.enabled = !isDefault
            }
            @Override
            void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            void popupMenuCanceled(PopupMenuEvent e) {}
        })
        
        listRenameItem.addActionListener({
            int idx = groupList.selectedIndex
            if (idx < 0) return
            String sel = model.groupLabels[idx]
            if (sel == DEFAULT_GROUP) return
            activeGroupId = sel
            if (renameGroup(sel)) refresh(activeGroupId)
        } as ActionListener)
        
        listDeleteItem.addActionListener({
            int idx = groupList.selectedIndex
            if (idx < 0) return
            String sel = model.groupLabels[idx]
            if (sel == DEFAULT_GROUP) return
            activeGroupId = sel
            if (deleteGroup(sel)) refresh(activeGroupId)
        } as ActionListener)
        
        groupList.setComponentPopupMenu(listPopup)
        
        

        def groupDeleteAction = new AbstractAction() {

            @Override
            void actionPerformed(ActionEvent event) {

                int index = groupList.selectedIndex

                if (index < 0)
                    return

                String selected = model.groupLabels[index]

                if (selected == DEFAULT_GROUP)
                    return

                activeGroupId = selected

                if (deleteGroup(selected))
                    refresh(activeGroupId)
            }

        }

        groupList.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),
                'deleteSelectedGroup'
        )

        groupList.getActionMap().put(
                'deleteSelectedGroup',
                groupDeleteAction
        )

        def addButton = new JButton('New root group')
        def childButton = new JButton('New child group')
        def renameButton = new JButton('Rename')
        def deleteButton = new JButton('Delete')
        def closeButton = new JButton('Close')

        def content = new JPanel(new BorderLayout(8, 8))
        content.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        content.add(new JScrollPane(groupList), BorderLayout.CENTER)

        // CHANGED: non-modal (false) so that JOptionPane dialogs stay usable
        groupManagerDialog = new JDialog(dialog, 'Virtual script groups', false)
        groupManagerDialog.name = managerName
        groupManagerDialog.contentPane = content
        groupManagerDialog.setSize(520, 420)
        groupManagerDialog.setLocationRelativeTo(dialog)

        groupList.addListSelectionListener({
            if (!it.valueIsAdjusting && groupList.selectedIndex >= 0) {

                activeGroupId =
                        model.groupLabels[groupList.selectedIndex]

                boolean isDefault =
                        activeGroupId == DEFAULT_GROUP

                renameButton.enabled = !isDefault
                deleteButton.enabled = !isDefault
            }

        } as ListSelectionListener)

        addButton.addActionListener({
            String created = createGroup(null, false)
            if (created) refresh(created)
        } as ActionListener)
        childButton.addActionListener({
            String parent = groupList.selectedIndex >= 0 ? model.groupLabels[groupList.selectedIndex] : null
            if (!parent) {
                JOptionPane.showMessageDialog(groupManagerDialog, 'Select a parent group first.')
                return
            }
            String created = createGroup(parent, false)
            if (created) refresh(created)
        } as ActionListener)
        renameButton.addActionListener({
            if (groupList.selectedIndex < 0) return
            String selected = model.groupLabels[groupList.selectedIndex]
            if (renameGroup(selected)) refresh(activeGroupId)
        } as ActionListener)
        deleteButton.addActionListener({
            if (groupList.selectedIndex < 0) return
            String selected = model.groupLabels[groupList.selectedIndex]
            if (deleteGroup(selected)) refresh(activeGroupId)
        } as ActionListener)

        def buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6))
        [addButton, childButton, renameButton, deleteButton].each { buttons.add(it) }
        content.add(buttons, BorderLayout.NORTH)
        def bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6))
        bottom.add(closeButton)
        content.add(bottom, BorderLayout.SOUTH)

        closeButton.addActionListener({ groupManagerDialog.dispose() } as ActionListener)
        groupManagerDialog.addWindowListener(new WindowAdapter() {
            @Override void windowClosed(WindowEvent e) { groupManagerDialog = null }
        })
        groupManagerDialog.rootPane.registerKeyboardAction({ groupManagerDialog.dispose() } as ActionListener,
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        groupManagerDialog.visible = true
    }

    def moveSelected = { int delta ->
        int viewRow = table.selectedRow
        if (viewRow < 0) return
        int modelRow = table.convertRowIndexToModel(viewRow)
        int target = modelRow + delta
        if (modelRow >= model.active.size() || target < 0 || target >= model.active.size()) return
        if (model.active[modelRow].trigger != model.active[target].trigger) return
        Collections.swap(model.active, modelRow, target)
        model.fireTableDataChanged()
        writeEntries(model.active)
        writeGroupData(model.groupLabels, model.active)
        status.text = "Saved: ${describe()}."
        selectFile(model.active[target].file)
    }

    def runFiles = { List<File> files ->
        if (!files) { status.text = 'Nothing to run.'; return }
        int ok = 0
        def failed = []
        def node = Controller.currentController.selection?.selected
        UIManager.put(AutoRunDispatcher.TRIGGER_KEY, 'MANUAL')
        files.each { File file ->
            if (!file.isFile()) {
                failed << "${file.name} (not found)"
                AutoRunDispatcher.record('MANUAL', file, node, 'not found', null)
                return
            }
            long started = System.nanoTime()
            try {
                LogUtils.info("running script on demand: ${file}")
                ScriptingEngine.executeScript(node, file, null)
                ok++
                AutoRunDispatcher.record('MANUAL', file, node, 'ok',
                        Math.round((System.nanoTime() - started) / 1000000d))
            }
            catch (Throwable t) {
                LogUtils.warn("script failed: ${file}", t)
                def reason = "${t.class.simpleName}: ${t.message}".toString()
                failed << "${file.name} (${reason})"
                AutoRunDispatcher.record('MANUAL', file, node, reason,
                        Math.round((System.nanoTime() - started) / 1000000d))
            }
        }
        UIManager.put(AutoRunDispatcher.TRIGGER_KEY, null)
        status.text = failed ? "Ran ${ok}, failed: ${failed.join('; ')}" : "Ran ${ok} script(s)."
    }

    // double click on the script or folder cell opens the file, to save hunting for it
    table.addMouseListener(new MouseAdapter() {
        @Override
        void mouseClicked(MouseEvent event) {
            if (event.clickCount != 2) return
            int viewColumn = table.columnAtPoint(event.point)
            int viewRow = table.rowAtPoint(event.point)
            if (viewRow < 0 || viewColumn < 4) return
            def file = model.fileAt(table.convertRowIndexToModel(viewRow))
            if (!file.isFile()) { status.text = "${file.name} no longer exists."; return }
            try {
                Desktop.desktop.open(file)
                status.text = "Opened ${file.name}."
            }
            catch (Throwable t) {
                LogUtils.warn("could not open ${file}", t)
                status.text = "Could not open ${file.name}: ${t.message}"
            }
        }
    })

    def applyFilter = {
        def text = filterField.text.trim()
        sorter.setRowFilter(text ? RowFilter.regexFilter('(?i)' + Pattern.quote(text), 4, 5, 6) : null)
    }
    filterField.document.addDocumentListener([
            insertUpdate : { applyFilter() },
            removeUpdate : { applyFilter() },
            changedUpdate: { applyFilter() }
    ] as DocumentListener)

    def triggerHelp = '<html>Startup: once, when Freeplane has started.<br>' +
            'Map opened: for every map loaded or created, including those restored at startup.<br>' +
            'Map closed: when a map is closed, also on the way out.<br>' +
            'Tab created: when a tab is opened.<br>' +
            'Tab selected: every time a tab becomes the current one -- keep these fast.<br>' +
            'Periodic: every N minutes, counted from when Freeplane started.<br>' +
            'Freeplane closing: on the way out, with the maps still open but already saved.</html>'

    def filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6))
    filterRow.add(new JLabel('Filter:'))
    filterRow.add(filterField)

    // its own row: on a wide filter row this label was pushed off the dialog entirely
    def hint = new JLabel('Tick a script and choose when it runs. Group is virtual: it never moves the file. Double click opens it.')
    hint.toolTipText = triggerHelp
    hint.font = hint.font.deriveFont((float) (hint.font.size2D - 1f))
    def hintRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0))
    hintRow.add(hint)

    def filterBar = new JPanel(new BorderLayout())
    filterBar.add(filterRow, BorderLayout.NORTH)
    filterBar.add(hintRow, BorderLayout.SOUTH)

    def upButton = new JButton('▲')
    def downButton = new JButton('▼')

    def removeButton = new JButton('Remove')
    removeButton.toolTipText = 'Take the selected script off the list. Unticking only switches it off, keeping its settings.'
    removeButton.addActionListener({
        int viewRow = table.selectedRow
        if (viewRow < 0) return
        int modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow >= model.active.size()) return
        def file = model.fileAt(modelRow)
        model.remove(file)
        model.fireTableDataChanged()
        writeEntries(model.active)
        writeGroupData(model.groupLabels, model.active)
        status.text = "Removed ${file.name} from the list. Now: ${describe()}."
    } as ActionListener)

    // Without the bridge nothing runs at the next start, however full this list looks -- and
    // there is no way to notice from inside Freeplane. Hence saying so, and offering the fix.
    def hookButton = new JButton('Install startup hook')
    // a hook written by an earlier version keeps working, but may look for this script in
    // too few folders, so offer to rewrite it -- one written by hand is left alone
    def outdatedBridge = {
        def bridge = installedBridge()
        if (bridge == null || !bridge.name.equalsIgnoreCase('autoRunScriptsBridge.groovy')) return null
        try { return bridge.getText('UTF-8') == bridgeSource ? null : bridge }
        catch (Throwable ignored) { return null }
    }
    def updateHookButton = {
        def bridge = installedBridge()
        def outdated = outdatedBridge()
        hookButton.text = bridge == null ? 'Install startup hook' : 'Update startup hook'
        hookButton.enabled = (bridge == null || outdated != null)
        hookButton.toolTipText = bridge == null
                ? "Write ${new File(initScriptsDir, 'autoRunScriptsBridge.groovy')}, so that this list is applied at every start"
                : (outdated ? "Rewrite ${outdated}: it was written by an earlier version of this script"
                            : "Already installed: ${bridge}")
    }
    hookButton.addActionListener({
        def target = new File(initScriptsDir, 'autoRunScriptsBridge.groovy')
        try {
            initScriptsDir.mkdirs()
            target.setText(bridgeSource, 'UTF-8')
            LogUtils.info("startup hook written to ${target}")
            updateHookButton()
            status.text = "Startup hook written. It takes effect the next time Freeplane starts."
        }
        catch (Throwable t) {
            LogUtils.warn("could not write ${target}", t)
            status.text = "Could not write ${target}: ${t.message}"
        }
    } as ActionListener)
    updateHookButton()

    def pauseButton = new JButton()
    def updatePauseButton = {
        boolean paused = AutoRunDispatcher.isPaused()
        pauseButton.text = paused ? 'Resume' : 'Pause all'
        pauseButton.toolTipText = paused
                ? 'Triggers are suspended. Manual runs still work, and a restart resumes them anyway.'
                : 'Suspend every trigger without touching the configuration'
    }
    pauseButton.addActionListener({
        boolean paused = AutoRunDispatcher.isPaused()
        UIManager.put(AutoRunDispatcher.PAUSED_KEY, paused ? null : Boolean.TRUE)
        updatePauseButton()
        def message = paused ? 'Auto-run triggers resumed.' : 'Auto-run triggers paused until you resume or restart.'
        Controller.currentController?.viewController?.out(message)
        status.text = "${message} Now: ${describe()}."
    } as ActionListener)
    updatePauseButton()

    def runSelectedButton = new JButton('Run selected')
    def runAllButton = new JButton('Run all ticked')

    def historyButton = new JButton('History')
    historyButton.toolTipText = 'Show the auto-run history.'
    historyButton.addActionListener({
        openHistory()
    } as ActionListener)

    groupsButton = new JButton('Groups')
    groupsButton.toolTipText = 'Open the nested script-group tree.'

    groupsButton.addActionListener({
        try {
            showGroupsFlyout()
        }
        catch (Throwable ex) {
            LogUtils.warn(
                    'AutoRun Scripts: cannot open Groups window.',
                    ex
            )

            ex.printStackTrace()

            JOptionPane.showMessageDialog(
                    dialog,
                    'Could not open Groups window.\n\n' +
                            ex.toString(),
                    'Virtual script groups',
                    JOptionPane.ERROR_MESSAGE
            )
        }

    } as ActionListener)

    new DropTarget(
            groupsButton,
            DnDConstants.ACTION_MOVE,
            new DropTargetAdapter() {

                @Override
                void dragEnter(DropTargetDragEvent event) {
                    if (!event.currentDataFlavors.any {
                        it.equals(DataFlavor.stringFlavor)
                    }) {
                        event.rejectDrag()
                        return
                    }

                    event.acceptDrag(DnDConstants.ACTION_MOVE)

                    SwingUtilities.invokeLater({
                        try {
                            if (groupsPopup == null ||
                                    !groupsPopup.visible) {
                                showGroupsFlyout()
                            }
                        }
                        catch (Throwable t) {
                            LogUtils.warn(
                                    'AutoRun Scripts: could not open Groups during drag.',
                                    t
                            )
                        }
                    })
                }

                @Override
                void dragOver(DropTargetDragEvent event) {
                    if (event.currentDataFlavors.any {
                        it.equals(DataFlavor.stringFlavor)
                    }) {
                        event.acceptDrag(DnDConstants.ACTION_MOVE)
                    }
                    else {
                        event.rejectDrag()
                    }
                }

                @Override
                void drop(DropTargetDropEvent event) {
                    // The actual destination is a JMenu.
                    // Dropping directly on the button is intentionally rejected.
                    event.rejectDrop()
                }
            },
            true
    )

    def closeButton = new JButton('Close')

    upButton.addActionListener({ moveSelected(-1) } as ActionListener)
    downButton.addActionListener({ moveSelected(1) } as ActionListener)
    // وقتی سورت فعاله، ▲▼ غیرفعال؛ وقتی سورت غیرفعاله، ▲▼ فعال
    sorter.addRowSorterListener({ event ->
        boolean sorted = sorter.sortKeys != null && !sorter.sortKeys.isEmpty()
        upButton.enabled = !sorted
        downButton.enabled = !sorted
    } as RowSorterListener)
    runSelectedButton.addActionListener({
        int viewRow = table.selectedRow
        runFiles(viewRow < 0 ? [] : [model.fileAt(table.convertRowIndexToModel(viewRow))])
    } as ActionListener)
    runAllButton.addActionListener({ runFiles(model.active.collect { it.file }) } as ActionListener)

    def buttonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6))
    [groupsButton, upButton, downButton, removeButton, runSelectedButton, runAllButton, historyButton,
     pauseButton, hookButton].each { buttonBar.add(it) }
    def rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6))
    rightBar.add(closeButton)

    def footer = new JPanel(new BorderLayout())
    footer.add(buttonBar, BorderLayout.WEST)
    footer.add(rightBar, BorderLayout.EAST)

    def statusBar = new JPanel(new BorderLayout(6, 0))
    statusBar.border = BorderFactory.createEmptyBorder(0, 8, 6, 8)
    statusBar.add(status, BorderLayout.CENTER)
    def pathLabel = new JLabel(listFile.absolutePath)
    // cast needed: Groovy widens float arithmetic to Double, which deriveFont rejects
    pathLabel.font = pathLabel.font.deriveFont((float) (pathLabel.font.size2D - 1f))
    pathLabel.enabled = false
    pathLabel.toolTipText = listFile.absolutePath + '\nVirtual groups: ' + groupFile.absolutePath
    // long texts must not decide how wide the dialog gets: let them be clipped instead.
    // the cast is needed: in Groovy dimension.height reaches getHeight(), which is a double
    [status, pathLabel].each { it.preferredSize = new Dimension(0, (int) it.preferredSize.height) }
    statusBar.add(pathLabel, BorderLayout.SOUTH)
    // where the scripts came from, for when the list is shorter than expected
    status.toolTipText = '<html>Folders searched for scripts:<br>' +
            searchedDirs().collect { it.absolutePath + (it.isDirectory() ? '' : ' (does not exist)') }.join('<br>') +
            '</html>'

    def content = new JPanel(new BorderLayout())
    content.add(filterBar, BorderLayout.NORTH)
    content.add(new JScrollPane(table), BorderLayout.CENTER)
    def south = new JPanel(new BorderLayout())
    south.add(footer, BorderLayout.NORTH)
    south.add(statusBar, BorderLayout.SOUTH)
    content.add(south, BorderLayout.SOUTH)

    def dialog = new JDialog(UITools.currentFrame, 'Scripts that run automatically', false)
    dialog.name = dialogName
    dialog.contentPane = content
    dialog.pack()
    closeButton.addActionListener({ dialog.dispose() } as ActionListener)
    dialog.rootPane.defaultButton = closeButton
    dialog.rootPane.registerKeyboardAction({ dialog.dispose() } as ActionListener,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
    dialog.addWindowListener(new WindowAdapter() {
        @Override
        void windowClosed(WindowEvent event) {
            if (groupsPopup?.visible)
                groupsPopup.setVisible(false)

            if (groupManagerDialog?.displayable)
                groupManagerDialog.dispose()
        }
    })

    def summary = model.rowCount
            ? "${describe()}, out of ${model.rowCount} script(s) found."
            : ("No script found in the ${searchedDirs().size()} folder(s) searched -- the status bar tooltip lists them. " +
               'Further folders are added in Preferences > Plugins > Scripting.').toString()
    status.text = installedBridge() == null
            ? "Startup hook missing: nothing will run at the next start. ${summary}"
            : (outdatedBridge() ? "Startup hook is from an earlier version: press Update startup hook. ${summary}"
                                : summary)
    placeInsideScreen(dialog, 0)
    dialog.visible = true
    return dialog
}

// The init one-liner sets the flag; the menu does not. Clear it either way, so that running
// this script again from the menu opens the dialog instead of booting a second time.
def bootstrap = Boolean.TRUE == UIManager.get('autoRunScripts.bootstrap')
UIManager.put('autoRunScripts.bootstrap', null)

if (bootstrap) {
    def message = install(true)
    LogUtils.info(message)
    Controller.currentController.viewController?.out(message)
    return message
}

// Opened from the menu. If the init one-liner is missing or failed, nothing would be armed
// and the window would be lying about what runs, so arm the triggers without running anything.
if (UIManager.get(AutoRunDispatcher.GENERATION_KEY) == null) {
    LogUtils.info(install(false))
}
openDialog()
'Auto-run scripts dialog opened.'
