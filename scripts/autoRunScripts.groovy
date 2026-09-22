// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2954
// Version: 1.3

import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.*
import javax.swing.table.*
import java.util.List // after java.awt.*, whose own List is not generic
import java.util.regex.Pattern

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

    AutoRunDispatcher(File listFile) {
        this.listFile = listFile
    }

    static boolean isStale(int generation) {
        def current = UIManager.get(GENERATION_KEY)
        return current != null && ((Integer) current).intValue() != generation
    }

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

                def hasTrigger = parts.length >= 2 &&
                        TRIGGERS.contains(parts[0].trim())

                def trigger = hasTrigger ? parts[0].trim() : 'STARTUP'
                def path = hasTrigger ? parts[1].trim() : line

                int minutes = DEFAULT_MINUTES

                if (parts.length >= 3 && parts[2].trim().isInteger()) {
                    minutes = Math.max(1, parts[2].trim() as int)
                }

                if (path) {
                    loaded[trigger] << [
                            file: new File(path),
                            every: minutes
                    ]
                }
            }
        }

        byTrigger = loaded
    }

    private Map executeOne(String trigger, File file, NodeModel node) {
        if (!file.isFile()) {
            LogUtils.warn("auto-run script not found (${trigger}): ${file}")
            record(trigger, file, node, 'not found', null)
            return [
                    ok: false,
                    message: "${file.name} (not found)".toString()
            ]
        }

        long started = System.nanoTime()

        try {
            LogUtils.info("running auto-run script (${trigger}): ${file}")
            ScriptingEngine.executeScript(node, file, null)

            record(trigger, file, node, 'ok',
                    Math.round((System.nanoTime() - started) / 1000000d))

            return [
                    ok: true,
                    message: null
            ]
        }
        catch (Throwable t) {
            LogUtils.warn("auto-run script failed (${trigger}): ${file}", t)

            def reason = "${t.class.simpleName}: ${t.message}".toString()

            record(trigger, file, node, reason,
                    Math.round((System.nanoTime() - started) / 1000000d))

            return [
                    ok: false,
                    message: "${file.name} (${reason})".toString()
            ]
        }
    }

    Map run(String trigger, NodeModel node) {
        return runAll(
                trigger,
                entriesFor(trigger).collect { it.file },
                node
        )
    }

    static boolean isPaused() {
        return UIManager.get(PAUSED_KEY) != null
    }

    Map runAll(String trigger, List<File> files, NodeModel node) {
        int ok = 0
        def failed = []

        if (isPaused()) return [ok: 0, failed: []]

        def previousTrigger = UIManager.get(TRIGGER_KEY)
        UIManager.put(TRIGGER_KEY, trigger)

        try {
            files.each { File file ->
                def outcome = executeOne(trigger, file, node)

                if (outcome.ok) ok++
                else failed << outcome.message
            }
        }
        finally {
            UIManager.put(TRIGGER_KEY, previousTrigger)
        }

        if (failed && failureNotifier != null) {
            failureNotifier(trigger, failed)
        }

        return [
                ok: ok,
                failed: failed
        ]
    }

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
    void onCreate(MapModel map) {
        fire('MAP_OPENED', map)
    }

    @Override
    void onRemove(MapModel map) {
        fire('MAP_CLOSED', map)
    }

    private void fire(String trigger, MapModel map) {
        if (AutoRunDispatcher.isStale(generation)) {
            Controller.currentController.modeController.mapController
                    .removeMapLifeCycleListener(this)
            return
        }

        if (map != null) {
            dispatcher.run(trigger, map.rootNode)
        }
    }
}

class AutoRunViewTrigger implements IMapViewChangeListener {
    final AutoRunDispatcher dispatcher
    final int generation

    private final Set<Component> alreadyCreated =
            Collections.newSetFromMap(
                    new WeakHashMap<Component, Boolean>()
            )

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
    void afterViewChange(Component oldView, Component newView) {
        fire('VIEW_SELECTED', newView)
    }

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

        def node = (selected != null && selected.map.is(map))
                ? selected
                : map.rootNode

        dispatcher.run(trigger, node)
    }
}

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

        dispatcher.run(
                'SHUTDOWN',
                Controller.currentController.selection?.selected
        )
    }
}

class AutoRunTableModel extends AbstractTableModel {
    private static final String[] COLUMNS =
            ['On', '#', 'Trigger', 'Every', 'Script', 'Folder', 'Group'] as String[]

    final List<AutoRunEntry> active = new ArrayList<AutoRunEntry>()
    final List<File> inactive = new ArrayList<File>()

    List<String> triggerLabels
    List<String> groupLabels = ['Uncategorized']

    // Virtual group assignments for ALL discovered scripts, including scripts that are not
    // active in AutoRun. This is deliberately independent from autoRunScripts.txt.
    Map<String, String> virtualGroups =
            new LinkedHashMap<String, String>()

    Closure onChange = {}
    Closure onGroupChange = {}

    int getRowCount() {
        return active.size() + inactive.size()
    }

    int getColumnCount() {
        return COLUMNS.length
    }

    String getColumnName(int column) {
        return COLUMNS[column]
    }

    Class getColumnClass(int column) {
        return column == 0 ? Boolean : String
    }

    boolean isCellEditable(int row, int column) {
        if (column == 0 || column == 2 || column == 6) return true

        return column == 3 &&
                row < active.size() &&
                active[row].trigger == 'PERIODIC'
    }

    File fileAt(int row) {
        return row < active.size()
                ? active[row].file
                : inactive[row - active.size()]
    }

    String groupOf(File file) {
        if (file == null) return 'Uncategorized'

        String key = file.absolutePath

        def assigned = virtualGroups[key]

        if (assigned) return assigned

        def entry = active.find {
            it.file == file
        }

        return entry?.group ?: 'Uncategorized'
    }

    void setGroup(File file, String group) {
        if (file == null) return

        String clean = String.valueOf(group ?: '').trim()

        if (!clean) {
            clean = 'Uncategorized'
        }

        virtualGroups[file.absolutePath] = clean

        def entry = active.find {
            it.file == file
        }

        if (entry != null) {
            entry.group = clean
        }
    }

    Object getValueAt(int row, int column) {
        boolean configured = row < active.size()
        File file = fileAt(row)

        if (column == 0) {
            return Boolean.valueOf(
                    configured && active[row].enabled
            )
        }

        if (column == 4) {
            return file.name.replaceFirst(
                    /(?i)\.groovy$/,
                    ''
            )
        }

        if (column == 5) {
            return file.isFile()
                    ? file.parentFile.name
                    : 'MISSING'
        }

        if (column == 6) {
            return groupOf(file)
        }

        if (!configured) return ''

        def entry = active[row]

        if (column == 2) {
            return triggerLabels[
                    AutoRunDispatcher.TRIGGERS.indexOf(entry.trigger)
            ]
        }

        if (column == 3) {
            return entry.trigger == 'PERIODIC'
                    ? "${entry.everyMinutes} min".toString()
                    : ''
        }

        if (!entry.enabled) return '--'

        return String.valueOf(
                active.take(row).count {
                    it.trigger == entry.trigger &&
                    it.enabled
                } + 1
        )
    }

    void setValueAt(Object value, int row, int column) {
        File file = fileAt(row)

        if (column == 0) {
            if (row < active.size()) {
                active[row].enabled = (Boolean.TRUE == value)
            }
            else if (Boolean.TRUE == value) {
                activate(
                        file,
                        'STARTUP',
                        AutoRunDispatcher.DEFAULT_MINUTES,
                        true
                )
            }
            else {
                return
            }
        }
        else if (column == 2) {
            int index = triggerLabels.indexOf(
                    String.valueOf(value)
            )

            if (index < 0) return

            int minutes = row < active.size()
                    ? active[row].everyMinutes
                    : AutoRunDispatcher.DEFAULT_MINUTES

            boolean enabled = row < active.size()
                    ? active[row].enabled
                    : true

            String group = groupOf(file)

            activate(
                    file,
                    AutoRunDispatcher.TRIGGERS[index],
                    minutes,
                    group,
                    enabled
            )
        }
        else if (column == 3) {
            if (row >= active.size()) return

            def digits = String.valueOf(value)
                    .replaceAll(/\D/, '')

            if (!digits) return

            active[row].everyMinutes =
                    Math.max(1, digits as int)
        }
        else if (column == 6) {
            String group = String.valueOf(value).trim()

            if (!group) {
                group = 'Uncategorized'
            }

            // IMPORTANT:
            // Changing the group does NOT activate an inactive script.
            setGroup(file, group)

            onGroupChange(file)

            fireTableDataChanged()
            onChange(file)
            return
        }
        else {
            return
        }

        fireTableDataChanged()
        onChange(file)
    }

    void activate(
            File file,
            String trigger,
            int everyMinutes,
            boolean enabled
    ) {
        activate(
                file,
                trigger,
                everyMinutes,
                groupOf(file),
                enabled
        )
    }

    void activate(
            File file,
            String trigger,
            int everyMinutes,
            String group,
            boolean enabled
    ) {
        String selectedGroup =
                group ?: groupOf(file)

        active.removeAll {
            it.file == file
        }

        inactive.remove(file)

        active.add(
                new AutoRunEntry(
                        file,
                        trigger,
                        everyMinutes,
                        selectedGroup,
                        enabled
                )
        )

        setGroup(file, selectedGroup)

        regroup()
    }

    void remove(File file) {
        active.removeAll {
            it.file == file
        }

        if (file.isFile() && !inactive.contains(file)) {
            inactive.add(file)
            sortInactive()
        }
    }

    void regroup() {
        Collections.sort(
                active,
                { AutoRunEntry a, AutoRunEntry b ->
                    AutoRunDispatcher.TRIGGERS.indexOf(a.trigger) <=>
                            AutoRunDispatcher.TRIGGERS.indexOf(b.trigger)
                } as Comparator
        )
    }

    void sortInactive() {
        inactive.sort { File a, File b ->
            (a.parentFile.name + '/' + a.name)
                    .compareToIgnoreCase(
                            b.parentFile.name + '/' + b.name
                    )
        }
    }
}

def TRIGGER_LABELS = [
        'Startup',
        'Map opened',
        'Map closed',
        'Tab created',
        'Tab selected',
        'Periodic',
        'Freeplane closing'
]

def labelOf = { String key ->
    TRIGGER_LABELS[
            AutoRunDispatcher.TRIGGERS.indexOf(key)
    ]
}

def userDir =
        new File(
                ResourceController.resourceController
                        .freeplaneUserDirectory
        )

def listFile =
        new File(
                userDir,
                'autoRunScripts.txt'
        )

def groupFile =
        new File(
                userDir,
                'autoRunScriptsGroups.txt'
        )

def DEFAULT_GROUP = 'Uncategorized'
def dialogName = 'autoRunScriptsDialog'

def initScriptsDir =
        ScriptResources.getInitScriptsDir()

def bridgeSource = '''// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2954

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
    ConfigurationUtils.decodeListValue(
            configured,
            false
    ).each { String path ->
        def trimmed = path.trim()

        if (trimmed) {
            dirs.add(
                    FileUtils.getAbsoluteFile(
                            userDirectory,
                            trimmed
                    )
            )
        }
    }
}

dirs.add(
        ScriptResources.getUserScriptsDir()
)

dirs.add(
        FileUtils.getAbsoluteFile(
                resourceController.installationBaseDir,
                'scripts'
        )
)

def script = dirs.collect {
    new File(
            it,
            'autoRunScripts.groovy'
    )
}.find {
    it.isFile()
}

if (script == null) {
    LogUtils.warn(
            'autoRunScripts.groovy not found in any of the configured script directories'
    )
}
else {
    javax.swing.UIManager.put(
            'autoRunScripts.bootstrap',
            Boolean.TRUE
    )

    ScriptingEngine.executeScript(
            Controller.currentController.selection?.selected,
            script,
            null
    )
}
'''

def installedBridge = {
    def candidates =
            initScriptsDir.listFiles({
                File f ->
                    f.isFile() &&
                            f.name.toLowerCase()
                                    .endsWith('.groovy')
            } as FileFilter)

    return candidates?.find {
        File f ->
            try {
                return f.getText('UTF-8')
                        .contains('autoRunScripts')
            }
            catch (Throwable ignored) {
                return false
            }
    }
}

def searchedDirs = {
    def resourceController =
            ResourceController.resourceController

    def dirs = new LinkedHashSet<File>()

    def configured =
            resourceController.getProperty(
                    'script_directories'
            )

    if (configured) {
        ConfigurationUtils.decodeListValue(
                configured,
                false
        ).each { String path ->
            def trimmed = path.trim()

            if (trimmed) {
                dirs.add(
                        FileUtils.getAbsoluteFile(
                                userDir.absolutePath,
                                trimmed
                        )
                )
            }
        }
    }

    dirs.add(
            ScriptResources.getUserScriptsDir()
    )

    dirs.add(
            FileUtils.getAbsoluteFile(
                    resourceController.installationBaseDir,
                    'scripts'
            )
    )

    return new ArrayList<File>(dirs)
}

def scriptDirs = {
    searchedDirs().findAll {
        it.isDirectory()
    }
}

def readGroupData = {
    def names = [DEFAULT_GROUP]
    def assignments = [:]

    if (!groupFile.isFile()) {
        return [
                names: names,
                assignments: assignments
        ]
    }

    groupFile.readLines('UTF-8').each { String raw ->
        def line = raw.trim()

        if (!line) return

        if (line.startsWith('#GROUP\t')) {
            def name =
                    line.substring(
                            '#GROUP\t'.length()
                    ).trim()

            if (name && !names.contains(name)) {
                names << name
            }

            return
        }

        if (line.startsWith('#')) return

        def parts = line.split('\t', 2)

        if (parts.length < 2) return

        def path = parts[0].trim()
        def group = parts[1].trim()

        if (path && group) {
            assignments[
                    new File(path).absolutePath
            ] = group

            if (!names.contains(group)) {
                names << group
            }
        }
    }

    return [
            names: names,
            assignments: assignments
    ]
}

def writeGroupData = {
        List<String> names,
        Map<String, String> assignments ->

    def cleanNames = []

    ([DEFAULT_GROUP] + names).each {
        String raw ->

        def name =
                String.valueOf(raw ?: '')
                        .replaceAll(
                                /[\t\r\n]/,
                                ' '
                        )
                        .trim()

        if (name && !cleanNames.contains(name)) {
            cleanNames << name
        }
    }

    def lines = [
            '# Virtual groups for autoRunScripts.groovy.',
            '# This file is UI-only; it does not affect execution or triggers.',
            '# Format: #GROUP\t<group name> and <absolute path>\t<group name>.'
    ]

    cleanNames.each {
        lines << '#GROUP\t' + it
    }

    assignments.each {
        String path,
        String group ->

        String cleanGroup =
                group ?: DEFAULT_GROUP

        if (cleanGroup != DEFAULT_GROUP) {
            lines << path.replace('\\', '/') +
                    '\t' +
                    cleanGroup
        }
    }

    groupFile.setText(
            lines.join('\n') + '\n',
            'UTF-8'
    )
}

def readEntries = {
    def entries = []
    def groupData = readGroupData()

    if (!listFile.isFile()) {
        return entries
    }

    listFile.readLines('UTF-8').each { String raw ->
        def line = raw.trim()

        if (!line) return

        boolean enabled = true

        if (line.startsWith(
                AutoRunDispatcher.DISABLED_MARK
        )) {
            enabled = false

            line = line.substring(
                    AutoRunDispatcher.DISABLED_MARK.length()
            )
        }
        else if (line.startsWith('#')) {
            return
        }

        def parts = line.split('\t')

        def hasTrigger =
                parts.length >= 2 &&
                AutoRunDispatcher.TRIGGERS.contains(
                        parts[0].trim()
                )

        def trigger =
                hasTrigger
                        ? parts[0].trim()
                        : 'STARTUP'

        def path =
                hasTrigger
                        ? parts[1].trim()
                        : line

        int minutes =
                AutoRunDispatcher.DEFAULT_MINUTES

        if (parts.length >= 3 &&
                parts[2].trim().isInteger()) {

            minutes =
                    Math.max(
                            1,
                            parts[2].trim() as int
                    )
        }

        if (path) {
            def file = new File(path)

            def group =
                    groupData.assignments[
                            file.absolutePath
                    ] ?: DEFAULT_GROUP

            entries << new AutoRunEntry(
                    file,
                    trigger,
                    minutes,
                    group,
                    enabled
            )
        }
    }

    return entries
}

def writeEntries = {
        List entries ->

    def lines = [
            '# Scripts run automatically, one "<TRIGGER><tab><path>" per line, in order.',
            '# A third field holds the interval in minutes, for PERIODIC.',
            '# Lines starting with "' +
                    AutoRunDispatcher.DISABLED_MARK.trim() +
                    '" are switched off but keep their settings.',
            '# Maintained by autoRunScripts.groovy.'
    ]

    lines += entries.collect {
        def line =
                it.trigger +
                        '\t' +
                        it.file.absolutePath.replace('\\', '/')

        if (it.trigger == 'PERIODIC') {
            line += '\t' + it.everyMinutes
        }

        return it.enabled
                ? line
                : AutoRunDispatcher.DISABLED_MARK + line
    }

    listFile.setText(
            lines.join('\n') + '\n',
            'UTF-8'
    )
}

if (!listFile.isFile()) {
    def marked = []

    scriptDirs().each { dir ->
        def groovyFiles =
                dir.listFiles({
                    File f ->
                        f.isFile() &&
                                f.name.toLowerCase()
                                        .endsWith('.groovy')
                } as FileFilter)

        groovyFiles?.sort {
            it.name.toLowerCase()
        }?.each { File f ->

            f.withReader('UTF-8') { reader ->
                for (int i = 0; i < 15; i++) {
                    def line = reader.readLine()

                    if (line == null) break

                    if (line.trim() == '//init') {
                        marked << f
                        break
                    }
                }
            }
        }
    }

    LogUtils.info(
            "creating ${listFile} from ${marked.size()} script(s) marked with //init"
    )

    writeEntries(
            marked.collect {
                new AutoRunEntry(
                        it,
                        'STARTUP',
                        AutoRunDispatcher.DEFAULT_MINUTES,
                        true
                )
            }
    )
}

def columnWidthFor = {
        JTable table,
        int index,
        String widest ->

    def column =
            table.columnModel.getColumn(index)

    int content =
            table.getFontMetrics(
                    table.font
            ).stringWidth(widest)

    int header =
            table.tableHeader.getFontMetrics(
                    table.tableHeader.font
            ).stringWidth(
                    String.valueOf(
                            column.headerValue
                    )
            )

    return Math.max(
            content,
            header
    ) + 18
}

def fixColumn = {
        JTable table,
        int index,
        String widest ->

    int width =
            columnWidthFor(
                    table,
                    index,
                    widest
            )

    table.columnModel.getColumn(index).with {
        minWidth = width
        maxWidth = width
        preferredWidth = width
    }
}

def preferColumn = {
        JTable table,
        int index,
        String typical ->

    table.columnModel.getColumn(index).preferredWidth =
            columnWidthFor(
                    table,
                    index,
                    typical
            )
}

def fitRowHeight = {
        JTable table ->

    table.rowHeight =
            Math.max(
                    table.rowHeight,
                    table.getFontMetrics(
                            table.font
                    ).height + 8
            )
}

def sizeTableToColumns = {
        JTable table,
        int visibleRows ->

    int width =
            (0..<table.columnCount).sum {
                table.columnModel
                        .getColumn(it)
                        .preferredWidth
            }

    table.preferredScrollableViewportSize =
            new Dimension(
                    (int) width,
                    table.rowHeight * visibleRows
            )
}

def placeInsideScreen = {
        JDialog dialog,
        int offset ->

    def frame = UITools.currentFrame

    def configuration =
            frame?.graphicsConfiguration ?:
            GraphicsEnvironment
                    .localGraphicsEnvironment
                    .defaultScreenDevice
                    .defaultConfiguration

    Rectangle screen = configuration.bounds

    Insets insets =
            Toolkit.defaultToolkit
                    .getScreenInsets(configuration)

    int usableX =
            screen.x + insets.left

    int usableY =
            screen.y + insets.top

    int usableWidth =
            screen.width -
                    insets.left -
                    insets.right

    int usableHeight =
            screen.height -
                    insets.top -
                    insets.bottom

    int width =
            Math.min(
                    dialog.width,
                    usableWidth
            )

    int height =
            Math.min(
                    dialog.height,
                    usableHeight
            )

    dialog.setSize(
            width,
            height
    )

    int x =
            (frame
                    ? (int) (
                            frame.x +
                                    (frame.width - width) / 2
                    )
                    : usableX) + offset

    int y =
            (frame
                    ? (int) (
                            frame.y +
                                    (frame.height - height) / 2
                    )
                    : usableY) + offset

    dialog.setLocation(
            Math.max(
                    usableX,
                    Math.min(
                            x,
                            usableX +
                                    usableWidth -
                                    width
                    )
            ),
            Math.max(
                    usableY,
                    Math.min(
                            y,
                            usableY +
                                    usableHeight -
                                    height
                    )
            )
    )
}

def openHistory = {
    def historyName =
            'autoRunHistoryDialog'

    Window.windows.findAll {
        it.name == historyName &&
                it.displayable
    }.each {
        it.dispose()
    }

    def prettyTrigger = {
            String key ->

        int index =
                AutoRunDispatcher.TRIGGERS.indexOf(key)

        return index >= 0
                ? TRIGGER_LABELS[index]
                : key.toLowerCase().capitalize()
    }

    def model =
            new DefaultTableModel(
                    [
                            'Time',
                            'Trigger',
                            'Script',
                            'Node',
                            'Map',
                            'ms',
                            'Result'
                    ] as String[],
                    0
            )

    def table = new JTable(model) {
        @Override
        String getToolTipText(MouseEvent event) {
            int viewRow =
                    rowAtPoint(
                            event.getPoint()
                    )

            int viewColumn =
                    columnAtPoint(
                            event.getPoint()
                    )

            if (viewRow < 0 ||
                    viewColumn < 0) {
                return null
            }

            def value =
                    getValueAt(
                            viewRow,
                            viewColumn
                    )

            return value
                    ? value.toString()
                    : null
        }
    }

    table.setDefaultEditor(
            Object,
            null
    )

    fitRowHeight(table)

    fixColumn(table, 0, '00:00:00')
    fixColumn(table, 1, 'Freeplane closing')
    preferColumn(table, 2, 'a script with a fairly long name')
    preferColumn(table, 3, 'a node with a fairly long text')
    preferColumn(table, 4, 'a map name')
    fixColumn(table, 5, '99999')
    preferColumn(table, 6, 'IllegalStateException: message')

    sizeTableToColumns(
            table,
            14
    )

    def rightAligned =
            new DefaultTableCellRenderer()

    rightAligned.horizontalAlignment =
            SwingConstants.RIGHT

    table.columnModel.getColumn(5)
            .cellRenderer =
            rightAligned

    def count =
            new JLabel(' ')

    def refresh = {
        def list =
                AutoRunDispatcher.history()

        def snapshot

        synchronized (list) {
            snapshot =
                    new ArrayList(list)
        }

        snapshot =
                snapshot.findAll {
                    it instanceof Map
                }

        if (model.rowCount ==
                snapshot.size()) {
            return
        }

        model.rowCount = 0

        snapshot.each { row ->
            model.addRow(
                    [
                            row.time,
                            prettyTrigger(
                                    row.trigger as String
                            ),
                            row.script,
                            row.node,
                            row.map,
                            row.millis,
                            row.result
                    ] as Object[]
            )
        }

        int failures =
                snapshot.count {
                    it.result != 'ok'
                }

        count.text =
                failures
                        ? "${model.rowCount} run(s) since Freeplane started, ${failures} failed"
                        : "${model.rowCount} run(s) since Freeplane started"

        if (model.rowCount > 0) {
            table.scrollRectToVisible(
                    table.getCellRect(
                            model.rowCount - 1,
                            0,
                            true
                    )
            )
        }
    }

    refresh()

    def clearButton =
            new JButton('Clear')

    clearButton.addActionListener({
        def list =
                AutoRunDispatcher.history()

        synchronized (list) {
            list.clear()
        }

        model.rowCount = 0
        count.text =
                '0 run(s) since Freeplane started'
    } as ActionListener)

    def closeHistoryButton =
            new JButton('Close')

    def bar =
            new JPanel(
                    new BorderLayout()
            )

    def leftBar =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.LEFT,
                            6,
                            6
                    )
            )

    leftBar.add(count)

    def rightBar =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.RIGHT,
                            6,
                            6
                    )
            )

    rightBar.add(clearButton)
    rightBar.add(closeHistoryButton)

    bar.add(
            leftBar,
            BorderLayout.WEST
    )

    bar.add(
            rightBar,
            BorderLayout.EAST
    )

    def content =
            new JPanel(
                    new BorderLayout()
            )

    content.add(
            new JScrollPane(table),
            BorderLayout.CENTER
    )

    content.add(
            bar,
            BorderLayout.SOUTH
    )

    def dialog =
            new JDialog(
                    UITools.currentFrame,
                    'Auto-run history',
                    false
            )

    dialog.name = historyName
    dialog.contentPane = content
    dialog.pack()

    dialog.defaultCloseOperation =
            JDialog.DISPOSE_ON_CLOSE

    closeHistoryButton.addActionListener({
        dialog.dispose()
    } as ActionListener)

    dialog.rootPane.registerKeyboardAction({
        dialog.dispose()
    } as ActionListener,
            KeyStroke.getKeyStroke(
                    KeyEvent.VK_ESCAPE,
                    0
            ),
            JComponent.WHEN_IN_FOCUSED_WINDOW
    )

    def timer =
            new javax.swing.Timer(
                    1000,
                    {
                        refresh()
                    } as ActionListener
            )

    timer.start()

    dialog.addWindowListener(
            new WindowAdapter() {
                @Override
                void windowClosed(WindowEvent event) {
                    timer.stop()
                }
            }
    )

    placeInsideScreen(
            dialog,
            60
    )

    dialog.visible = true

    return dialog
}

def notifyFailure = {
        String trigger,
        List failed ->

    def message =
            "Auto-run failed on ${labelOf(trigger) ?: trigger}: ${failed.join('; ')}"

    Controller.currentController
            ?.viewController
            ?.out(message)

    if (UIManager.get(
            AutoRunDispatcher.NOTIFIED_KEY
    ) != null) {
        return
    }

    UIManager.put(
            AutoRunDispatcher.NOTIFIED_KEY,
            Boolean.TRUE
    )

    SwingUtilities.invokeLater({
        openHistory()
    } as Runnable)
}

def install = {
        boolean runStartupScripts ->

    def controller =
            Controller.currentController

    def mapController =
            controller.modeController.mapController

    def mapViewManager =
            controller.mapViewManager

    def generation =
            ((UIManager.get(
                    AutoRunDispatcher.GENERATION_KEY
            ) ?: Integer.valueOf(0)) as int) + 1

    UIManager.put(
            AutoRunDispatcher.GENERATION_KEY,
            Integer.valueOf(generation)
    )

    def previousView =
            UIManager.get(
                    AutoRunDispatcher.VIEW_LISTENER_KEY
            )

    if (previousView instanceof IMapViewChangeListener) {
        mapViewManager.removeMapViewChangeListener(
                previousView
        )
    }

    def previousMap =
            UIManager.get(
                    AutoRunDispatcher.MAP_LISTENER_KEY
            )

    if (previousMap instanceof IMapLifeCycleListener) {
        mapController.removeMapLifeCycleListener(
                previousMap
        )
    }

    new ArrayList(
            mapController.mapLifeCycleListeners
    )
            .findAll {
                it.class.simpleName ==
                        'AutoRunMapTrigger'
            }
            .each {
                mapController.removeMapLifeCycleListener(
                        it
                )
            }

    def previousTicker =
            UIManager.get(
                    AutoRunDispatcher.TICKER_KEY
            )

    if (previousTicker instanceof javax.swing.Timer) {
        previousTicker.stop()
    }

    def dispatcher =
            new AutoRunDispatcher(listFile)

    dispatcher.failureNotifier =
            notifyFailure

    def mapListener =
            new AutoRunMapTrigger(
                    dispatcher,
                    generation
            )

    def viewListener =
            new AutoRunViewTrigger(
                    dispatcher,
                    generation
            )

    def shutdownListener =
            new AutoRunShutdownTrigger(
                    dispatcher,
                    generation
            )

    mapController.addMapLifeCycleListener(
            mapListener
    )

    mapViewManager.addMapViewChangeListener(
            viewListener
    )

    SwingUtilities.invokeLater({
        if (!AutoRunDispatcher.isStale(
                generation
        )) {
            controller.addApplicationLifecycleListener(
                    shutdownListener
            )
        }
    } as Runnable)

    UIManager.put(
            AutoRunDispatcher.MAP_LISTENER_KEY,
            mapListener
    )

    UIManager.put(
            AutoRunDispatcher.VIEW_LISTENER_KEY,
            viewListener
    )

    UIManager.put(
            'autoRunScripts.shutdownListener',
            shutdownListener
    )

    UIManager.put(
            'autoRunScripts.dispatcher',
            dispatcher
    )

    def ticker =
            new javax.swing.Timer(
                    30000,
                    { event ->

                        if (AutoRunDispatcher.isStale(
                                generation
                        )) {
                            ((javax.swing.Timer)
                                    event.source).stop()
                            return
                        }

                        dispatcher.runDuePeriodic(
                                Controller.currentController
                                        ?.selection
                                        ?.selected
                        )
                    } as ActionListener
            )

    ticker.start()

    UIManager.put(
            AutoRunDispatcher.TICKER_KEY,
            ticker
    )

    if (!runStartupScripts) {
        return 'Auto-run triggers installed.'
    }

    def startup =
            dispatcher.run(
                    'STARTUP',
                    controller.selection?.selected
            )

    int caughtUp = 0
    def failedCatchUp = []

    if (dispatcher.entriesFor(
            'MAP_OPENED'
    )) {

        new ArrayList(
                mapViewManager.maps.values()
        )
                .unique {
                    System.identityHashCode(it)
                }
                .each { MapModel map ->

                    def result =
                            dispatcher.run(
                                    'MAP_OPENED',
                                    map.rootNode
                            )

                    caughtUp += result.ok
                    failedCatchUp += result.failed
                }
    }

    def armed =
            AutoRunDispatcher.TRIGGERS
                    .findAll {
                        it != 'STARTUP'
                    }
                    .collectEntries {
                        [
                                (it):
                                        dispatcher
                                                .entriesFor(it)
                                                .size()
                        ]
                    }
                    .findAll {
                        it.value
                    }

    def failed =
            startup.failed +
                    failedCatchUp

    def message =
            new StringBuilder(
                    "Auto-run scripts: ${startup.ok + caughtUp} executed"
            )

    if (armed) {
        message.append(', armed ')
                .append(
                        armed.collect {
                            "${it.value} on ${labelOf(it.key)}"
                        }.join(', ')
                )
    }

    if (failed) {
        message.append(' -- failed: ')
                .append(
                        failed.join('; ')
                )
    }

    return message.toString()
}

def openDialog = {

    Window.windows.findAll {
        it.name == dialogName &&
                it.displayable
    }.each {
        it.dispose()
    }

    def model =
            new AutoRunTableModel()

    model.triggerLabels =
            TRIGGER_LABELS

    def groupData =
            readGroupData()

    model.groupLabels =
            new ArrayList<String>(
                    groupData.names
            )

    model.virtualGroups.putAll(
            groupData.assignments
    )

    model.active.addAll(
            readEntries()
    )

    model.active.each { entry ->
        entry.group =
                model.groupOf(
                        entry.file
                )
    }

    model.regroup()

    def activeFiles =
            model.active.collect {
                it.file
            }

    scriptDirs().each { dir ->

        def groovyFiles =
                dir.listFiles({
                    File f ->
                        f.isFile() &&
                                f.name.toLowerCase()
                                        .endsWith('.groovy')
                } as FileFilter)

        groovyFiles?.each { File f ->

            if (!activeFiles.contains(f)) {
                model.inactive.add(f)
            }
        }
    }

    model.inactive.each { File file ->

        if (!model.virtualGroups.containsKey(
                file.absolutePath
        )) {
            model.virtualGroups[
                    file.absolutePath
            ] = DEFAULT_GROUP
        }
    }

    model.sortInactive()

    def table =
            new JTable(model) {

                @Override
                String getToolTipText(
                        MouseEvent event
                ) {

                    int viewRow =
                            rowAtPoint(
                                    event.getPoint()
                            )

                    if (viewRow < 0) {
                        return null
                    }

                    def file =
                            ((AutoRunTableModel)
                                    getModel())
                                    .fileAt(
                                            convertRowIndexToModel(
                                                    viewRow
                                            )
                                    )

                    return file.isFile()
                            ? file.absolutePath
                            : file.absolutePath +
                                    '  (missing)'
                }
            }

    table.setSelectionMode(
            ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    )

    fitRowHeight(table)

    table.setAutoCreateRowSorter(false)

    fixColumn(table, 0, 'On')
    fixColumn(table, 1, '99')
    fixColumn(table, 2, 'Freeplane closing     ')
    fixColumn(table, 3, '999 min')
    preferColumn(
            table,
            4,
            'a script with a fairly long name'
    )
    preferColumn(
            table,
            5,
            'compartilhados'
    )
    preferColumn(
            table,
            6,
            'Uncategorized'
    )

    sizeTableToColumns(
            table,
            16
    )

    table.columnModel.getColumn(2)
            .setCellEditor(
                    new DefaultCellEditor(
                            new JComboBox(
                                    TRIGGER_LABELS as String[]
                            )
                    )
            )

    table.columnModel.getColumn(6)
            .setCellEditor(
                    new DefaultCellEditor(
                            new JComboBox(
                                    model.groupLabels as String[]
                            )
                    )
            )

    def sorter = new TableRowSorter<AutoRunTableModel>(model)
    table.setRowSorter(sorter)

    def status =
            new JLabel(' ')

    def filterField =
            new JTextField(18)

    def describe = {

        def counts =
                AutoRunDispatcher.TRIGGERS
                        .collectEntries { key ->
                            [
                                    (key):
                                            model.active.count {
                                                it.trigger == key &&
                                                        it.enabled
                                            }
                            ]
                        }
                        .findAll {
                            it.value
                        }

        def text =
                counts
                        ? counts.collect {
                            "${it.value} on ${labelOf(it.key)}"
                        }.join(', ')
                        : 'nothing runs automatically'

        int off =
                model.active.count {
                    !it.enabled
                }

        if (off) {
            text += ", ${off} switched off"
        }

        if (AutoRunDispatcher.isPaused()) {
            text =
                    "PAUSED -- ${text}"
        }

        return text
    }

    def selectFile = {
            File file ->

        int modelRow =
                (0..<model.rowCount).find {
                    model.fileAt(it) == file
                }

        if (modelRow == null) return

        int viewRow =
                table.convertRowIndexToView(
                        modelRow
                )

        if (viewRow < 0) return

        table.setRowSelectionInterval(
                viewRow,
                viewRow
        )

        table.scrollRectToVisible(
                table.getCellRect(
                        viewRow,
                        0,
                        true
                )
        )
    }

    model.onChange = {
            File file ->

        writeEntries(
                model.active
        )

        writeGroupData(
                model.groupLabels,
                model.virtualGroups
        )

        status.text =
                "Saved: ${describe()}."

        selectFile(file)
    }

    model.onGroupChange = {
            File file ->

        writeGroupData(
                model.groupLabels,
                model.virtualGroups
        )
    }

    def refreshGroupEditor = {

        table.columnModel
                .getColumn(6)
                .setCellEditor(
                        new DefaultCellEditor(
                                new JComboBox(
                                        model.groupLabels as String[]
                                )
                        )
                )
    }

    def saveGroups = {

        writeGroupData(
                model.groupLabels,
                model.virtualGroups
        )

        refreshGroupEditor()

        model.fireTableDataChanged()
    }

    def selectedFiles = {

        def files =
                new ArrayList<File>()

        table.selectedRows.each {
                int viewRow ->

            int modelRow =
                    table.convertRowIndexToModel(
                            viewRow
                    )

            if (modelRow >= 0 &&
                    modelRow < model.rowCount) {

                File file =
                        model.fileAt(
                                modelRow
                        )

                if (file != null &&
                        !files.contains(file)) {

                    files.add(file)
                }
            }
        }

        return files
    }

    /*
    ============================================================
    CREATE GROUP FROM RIGHT CLICK
    ============================================================
    */

    def createGroupFromSelection = {

        def files =
                selectedFiles()

        if (!files) {
            status.text =
                    'Select at least one script first.'
            return
        }

        /*
         * IMPORTANT:
         * Do not use "dialog" here.
         *
         * The dialog is created later in this method, so the input
         * dialog is deliberately parented to the table instead.
         */
        def name =
                JOptionPane.showInputDialog(
                        table,
                        'Group name:',
                        'Create new virtual group',
                        JOptionPane.PLAIN_MESSAGE
                )

        if (name == null) {
            return
        }

        name =
                name.replaceAll(
                        /[\t\r\n]/,
                        ' '
                ).trim()

        if (!name) {
            return
        }

        if (model.groupLabels.contains(name)) {

            JOptionPane.showMessageDialog(
                    table,
                    'That group already exists.',
                    'Virtual script groups',
                    JOptionPane.WARNING_MESSAGE
            )

            return
        }

        /*
         * Create the group first.
         */
        model.groupLabels << name

        model.groupLabels.sort {
            a, b ->
                a.compareToIgnoreCase(b)
        }

        /*
         * Then assign EVERY selected script.
         * This works for both active and inactive scripts.
         */
        files.each { File file ->
            model.setGroup(
                    file,
                    name
            )
        }

        /*
         * Save only the virtual group file.
         * autoRunScripts.txt is NOT changed here.
         */
        saveGroups()

        /*
         * Make sure the selected rows are immediately redrawn.
         */
        table.repaint()

        status.text =
                "${files.size()} script(s) added to group '${name}'."
    }

    def addSelectionToGroup = {
            String group ->

        def files =
                selectedFiles()

        if (!files) {
            return
        }

        files.each { File file ->
            model.setGroup(
                    file,
                    group
            )
        }

        saveGroups()

        table.repaint()

        status.text =
                "${files.size()} script(s) added to group '${group}'."
    }

    def removeSelectionFromGroup = {

        def files =
                selectedFiles()

        if (!files) {
            return
        }

        files.each { File file ->
            model.setGroup(
                    file,
                    DEFAULT_GROUP
            )
        }

        saveGroups()

        table.repaint()

        status.text =
                "${files.size()} script(s) moved to '${DEFAULT_GROUP}'."
    }

    /*
    ============================================================
    RIGHT CLICK GROUP MENU
    ============================================================
    */

    table.addMouseListener(
            new MouseAdapter() {

                @Override
                void mousePressed(
                        MouseEvent event
                ) {
                    showPopup(event)
                }

                @Override
                void mouseReleased(
                        MouseEvent event
                ) {
                    showPopup(event)
                }

                private void showPopup(
                        MouseEvent event
                ) {

                    if (!event.isPopupTrigger()) {
                        return
                    }

                    int row =
                            table.rowAtPoint(
                                    event.point
                            )

                    if (row < 0) {
                        return
                    }

                    /*
                     * If right-clicking an already selected row,
                     * keep the whole multi-selection.
                     *
                     * If right-clicking an unselected row,
                     * select only that row.
                     */
                    if (!table.isRowSelected(row)) {

                        table.setRowSelectionInterval(
                                row,
                                row
                        )
                    }

                    def files =
                            selectedFiles()

                    if (!files) {
                        return
                    }

                    def popup =
                            new JPopupMenu()

                    /*
                    ------------------------------------------------
                    ADD TO GROUP
                    ------------------------------------------------
                    */

                    def addMenu =
                            new JMenu(
                                    'Add to group'
                            )

                    model.groupLabels.each {
                            String group ->

                        if (group == DEFAULT_GROUP) {
                            return
                        }

                        def item =
                                new JMenuItem(
                                        group
                                )

                        item.addActionListener({
                            addSelectionToGroup(
                                    group
                            )
                        } as ActionListener)

                        addMenu.add(item)
                    }

                    if (addMenu.itemCount == 0) {
                        addMenu.enabled = false
                    }

                    popup.add(addMenu)

                    /*
                    ------------------------------------------------
                    CREATE NEW GROUP
                    ------------------------------------------------
                    */

                    def createItem =
                            new JMenuItem(
                                    'Create new group...'
                            )

                    createItem.addActionListener({

                        /*
                         * Capture selection at the moment the
                         * menu command is executed.
                         */
                        createGroupFromSelection()

                    } as ActionListener)

                    popup.add(createItem)

                    popup.addSeparator()

                    /*
                    ------------------------------------------------
                    REMOVE FROM GROUP
                    ------------------------------------------------
                    */

                    def removeItem =
                            new JMenuItem(
                                    'Remove from group'
                            )

                    removeItem.addActionListener({
                        removeSelectionFromGroup()
                    } as ActionListener)

                    popup.add(removeItem)

                    popup.show(
                            table,
                            event.x,
                            event.y
                    )
                }
            }
    )

    /*
    ============================================================
    GROUP MANAGER
    ============================================================
    */

    def openGroupManager = {

        def managerName =
                'autoRunScriptsGroupManager'

        Window.windows.findAll {
            it.name == managerName &&
                    it.displayable
        }.each {
            it.dispose()
        }

        def listModel =
                new DefaultListModel<String>()

        model.groupLabels.each {
            listModel.addElement(it)
        }

        def groupList =
                new JList<String>(
                        listModel
                )

        groupList.selectionMode =
                ListSelectionModel.SINGLE_SELECTION

        groupList.visibleRowCount = 10

        def addButton =
                new JButton('New group')

        def renameButton =
                new JButton('Rename')

        def deleteButton =
                new JButton('Delete')

        def closeButton =
                new JButton('Close')

        def content =
                new JPanel(
                        new BorderLayout(
                                8,
                                8
                        )
                )

        content.border =
                BorderFactory.createEmptyBorder(
                        8,
                        8,
                        8,
                        8
                )

        content.add(
                new JScrollPane(groupList),
                BorderLayout.CENTER
        )

        def groupDialog =
                new JDialog(
                        dialog,
                        'Virtual script groups',
                        true
                )

        groupDialog.name =
                managerName

        groupDialog.contentPane =
                content

        groupDialog.setSize(
                420,
                360
        )

        groupDialog.setLocationRelativeTo(
                dialog
        )

        def refresh = {

            listModel.clear()

            model.groupLabels.each {
                listModel.addElement(it)
            }
        }

        /*
        ----------------------------------------------------------
        NEW GROUP
        ----------------------------------------------------------
        */

        addButton.addActionListener({

            def name =
                    JOptionPane.showInputDialog(
                            groupDialog,
                            'Group name:',
                            'New virtual group',
                            JOptionPane.PLAIN_MESSAGE
                    )

            if (name == null) {
                return
            }

            name =
                    name.replaceAll(
                            /[\t\r\n]/,
                            ' '
                    ).trim()

            if (!name) {
                return
            }

            if (model.groupLabels.contains(name)) {

                JOptionPane.showMessageDialog(
                        groupDialog,
                        'That group already exists.'
                )

                return
            }

            model.groupLabels << name

            model.groupLabels.sort {
                a, b ->
                    a.compareToIgnoreCase(b)
            }

            saveGroups()

            refresh()

            status.text =
                    "Group created: ${name}."
        } as ActionListener)

        /*
        ----------------------------------------------------------
        RENAME GROUP
        ----------------------------------------------------------
        */

        renameButton.addActionListener({

            int index =
                    groupList.selectedIndex

            if (index < 0) {
                return
            }

            String oldName =
                    model.groupLabels[index]

            if (oldName == DEFAULT_GROUP) {

                JOptionPane.showMessageDialog(
                        groupDialog,
                        'The Uncategorized group cannot be renamed.'
                )

                return
            }

            def name =
                    JOptionPane.showInputDialog(
                            groupDialog,
                            'New group name:',
                            oldName
                    )

            if (name == null) {
                return
            }

            name =
                    name.replaceAll(
                            /[\t\r\n]/,
                            ' '
                    ).trim()

            if (!name ||
                    name == oldName) {
                return
            }

            if (model.groupLabels.contains(name)) {

                JOptionPane.showMessageDialog(
                        groupDialog,
                        'That group already exists.'
                )

                return
            }

            /*
             * IMPORTANT:
             * Rename virtual assignments for ALL scripts,
             * including inactive scripts.
             */
            model.virtualGroups.each {
                    String path,
                    String group ->

                if (group == oldName) {
                    model.virtualGroups[path] =
                            name
                }
            }

            /*
             * Keep active AutoRunEntry objects synchronized
             * with the virtual map.
             */
            model.active.each { entry ->

                if (entry.group == oldName) {
                    entry.group = name
                }
            }

            model.groupLabels[index] =
                    name

            model.groupLabels.sort {
                a, b ->
                    a.compareToIgnoreCase(b)
            }

            saveGroups()

            model.fireTableDataChanged()

            refresh()

            status.text =
                    "Group renamed: ${oldName} → ${name}."
        } as ActionListener)

        /*
        ----------------------------------------------------------
        DELETE GROUP
        ----------------------------------------------------------
        */

        deleteButton.addActionListener({

            int index =
                    groupList.selectedIndex

            if (index < 0) {
                return
            }

            String name =
                    model.groupLabels[index]

            if (name == DEFAULT_GROUP) {

                JOptionPane.showMessageDialog(
                        groupDialog,
                        'The Uncategorized group cannot be deleted.'
                )

                return
            }

            int answer =
                    JOptionPane.showConfirmDialog(
                            groupDialog,
                            "Delete group '${name}'? Its scripts will move to '${DEFAULT_GROUP}'.",
                            'Delete virtual group',
                            JOptionPane.YES_NO_OPTION
                    )

            if (answer != JOptionPane.YES_OPTION) {
                return
            }

            /*
             * IMPORTANT:
             * Move ALL virtual assignments to Uncategorized,
             * including inactive scripts.
             */
            model.virtualGroups.each {
                    String path,
                    String group ->

                if (group == name) {
                    model.virtualGroups[path] =
                            DEFAULT_GROUP
                }
            }

            model.active.each { entry ->

                if (entry.group == name) {
                    entry.group =
                            DEFAULT_GROUP
                }
            }

            model.groupLabels.remove(name)

            saveGroups()

            model.fireTableDataChanged()

            refresh()

            status.text =
                    "Group deleted: ${name}."
        } as ActionListener)

        def buttons =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                6,
                                6
                        )
                )

        [
                addButton,
                renameButton,
                deleteButton
        ].each {
            buttons.add(it)
        }

        content.add(
                buttons,
                BorderLayout.NORTH
        )

        def bottom =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.RIGHT,
                                6,
                                6
                        )
                )

        bottom.add(closeButton)

        content.add(
                bottom,
                BorderLayout.SOUTH
        )

        closeButton.addActionListener({
            groupDialog.dispose()
        } as ActionListener)

        groupDialog.rootPane.registerKeyboardAction({
            groupDialog.dispose()
        } as ActionListener,
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ESCAPE,
                        0
                ),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        groupDialog.visible = true
    }

    def moveSelected = {
            int delta ->

        int viewRow =
                table.selectedRow

        if (viewRow < 0) {
            return
        }

        int modelRow =
                table.convertRowIndexToModel(
                        viewRow
                )

        int target =
                modelRow + delta

        if (modelRow >= model.active.size() ||
                target < 0 ||
                target >= model.active.size()) {
            return
        }

        if (model.active[modelRow].trigger !=
                model.active[target].trigger) {
            return
        }

        Collections.swap(
                model.active,
                modelRow,
                target
        )

        model.fireTableDataChanged()

        writeEntries(
                model.active
        )

        writeGroupData(
                model.groupLabels,
                model.virtualGroups
        )

        status.text =
                "Saved: ${describe()}."

        selectFile(
                model.active[target].file
        )
    }

    def runFiles = {
            List<File> files ->

        if (!files) {
            status.text =
                    'Nothing to run.'
            return
        }

        int ok = 0
        def failed = []

        def node =
                Controller.currentController
                        .selection
                        ?.selected

        UIManager.put(
                AutoRunDispatcher.TRIGGER_KEY,
                'MANUAL'
        )

        files.each { File file ->

            if (!file.isFile()) {

                failed << "${file.name} (not found)"

                AutoRunDispatcher.record(
                        'MANUAL',
                        file,
                        node,
                        'not found',
                        null
                )

                return
            }

            long started =
                    System.nanoTime()

            try {

                LogUtils.info(
                        "running script on demand: ${file}"
                )

                ScriptingEngine.executeScript(
                        node,
                        file,
                        null
                )

                ok++

                AutoRunDispatcher.record(
                        'MANUAL',
                        file,
                        node,
                        'ok',
                        Math.round(
                                (System.nanoTime() -
                                        started) /
                                        1000000d
                        )
                )
            }
            catch (Throwable t) {

                LogUtils.warn(
                        "script failed: ${file}",
                        t
                )

                def reason =
                        "${t.class.simpleName}: ${t.message}".toString()

                failed <<
                        "${file.name} (${reason})"

                AutoRunDispatcher.record(
                        'MANUAL',
                        file,
                        node,
                        reason,
                        Math.round(
                                (System.nanoTime() -
                                        started) /
                                        1000000d
                        )
                )
            }
        }

        UIManager.put(
                AutoRunDispatcher.TRIGGER_KEY,
                null
        )

        status.text =
                failed
                        ? "Ran ${ok}, failed: ${failed.join('; ')}"
                        : "Ran ${ok} script(s)."
    }

    table.addMouseListener(
            new MouseAdapter() {

                @Override
                void mouseClicked(
                        MouseEvent event
                ) {

                    if (event.clickCount != 2) {
                        return
                    }

                    int viewColumn =
                            table.columnAtPoint(
                                    event.point
                            )

                    int viewRow =
                            table.rowAtPoint(
                                    event.point
                            )

                    if (viewRow < 0 ||
                            viewColumn < 4) {
                        return
                    }

                    def file =
                            model.fileAt(
                                    table.convertRowIndexToModel(
                                            viewRow
                                    )
                            )

                    if (!file.isFile()) {
                        status.text =
                                "${file.name} no longer exists."
                        return
                    }

                    try {

                        Desktop.desktop.open(
                                file
                        )

                        status.text =
                                "Opened ${file.name}."
                    }
                    catch (Throwable t) {

                        LogUtils.warn(
                                "could not open ${file}",
                                t
                        )

                        status.text =
                                "Could not open ${file.name}: ${t.message}"
                    }
                }
            }
    )

    def applyFilter = {

        def text =
                filterField.text.trim()

        sorter.setRowFilter(
                text
                        ? RowFilter.regexFilter(
                                '(?i)' +
                                        Pattern.quote(text),
                                4,
                                5,
                                6
                        )
                        : null
        )
    }

    filterField.document.addDocumentListener([
            insertUpdate : {
                applyFilter()
            },
            removeUpdate : {
                applyFilter()
            },
            changedUpdate: {
                applyFilter()
            }
    ] as DocumentListener)

    def triggerHelp =
            '<html>Startup: once, when Freeplane has started.<br>' +
            'Map opened: for every map loaded or created, including those restored at startup.<br>' +
            'Map closed: when a map is closed, also on the way out.<br>' +
            'Tab created: when a tab is opened.<br>' +
            'Tab selected: every time a tab becomes the current one -- keep these fast.<br>' +
            'Periodic: every N minutes, counted from when Freeplane started.<br>' +
            'Freeplane closing: on the way out, with the maps still open but already saved.</html>'

    def filterRow =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.LEFT,
                            6,
                            6
                    )
            )

    filterRow.add(
            new JLabel('Filter:')
    )

    filterRow.add(
            filterField
    )

    def hint =
            new JLabel(
                    'Tick a script and choose when it runs. Group is virtual: it never moves the file. Double click opens it.'
            )

    hint.toolTipText =
            triggerHelp

    hint.font =
            hint.font.deriveFont(
                    (float) (
                            hint.font.size2D -
                                    1f
                    )
            )

    def hintRow =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.LEFT,
                            8,
                            0
                    )
            )

    hintRow.add(hint)

    def filterBar =
            new JPanel(
                    new BorderLayout()
            )

    filterBar.add(
            filterRow,
            BorderLayout.NORTH
    )

    filterBar.add(
            hintRow,
            BorderLayout.SOUTH
    )

    def upButton =
            new JButton('▲')

    def downButton =
            new JButton('▼')

    def removeButton =
            new JButton('Remove')

    removeButton.toolTipText =
            'Take the selected script off the list. Unticking only switches it off, keeping its settings.'

    removeButton.addActionListener({

        int viewRow =
                table.selectedRow

        if (viewRow < 0) {
            return
        }

        int modelRow =
                table.convertRowIndexToModel(
                        viewRow
                )

        if (modelRow >= model.active.size()) {
            return
        }

        def file =
                model.fileAt(
                        modelRow
                )

        model.remove(file)

        model.fireTableDataChanged()

        writeEntries(
                model.active
        )

        writeGroupData(
                model.groupLabels,
                model.virtualGroups
        )

        status.text =
                "Removed ${file.name} from the list. Now: ${describe()}."
    } as ActionListener)

    def hookButton =
            new JButton(
                    'Install startup hook'
            )

    def outdatedBridge = {

        def bridge =
                installedBridge()

        if (bridge == null ||
                !bridge.name.equalsIgnoreCase(
                        'autoRunScriptsBridge.groovy'
                )) {
            return null
        }

        try {
            return bridge.getText('UTF-8') ==
                    bridgeSource
                    ? null
                    : bridge
        }
        catch (Throwable ignored) {
            return null
        }
    }

    def updateHookButton = {

        def bridge =
                installedBridge()

        def outdated =
                outdatedBridge()

        hookButton.text =
                bridge == null
                        ? 'Install startup hook'
                        : 'Update startup hook'

        hookButton.enabled =
                bridge == null ||
                        outdated != null

        hookButton.toolTipText =
                bridge == null
                        ? "Write ${new File(initScriptsDir, 'autoRunScriptsBridge.groovy')}, so that this list is applied at every start"
                        : (outdated
                                ? "Rewrite ${outdated}: it was written by an earlier version of this script"
                                : "Already installed: ${bridge}")
    }

    hookButton.addActionListener({

        def target =
                new File(
                        initScriptsDir,
                        'autoRunScriptsBridge.groovy'
                )

        try {

            initScriptsDir.mkdirs()

            target.setText(
                    bridgeSource,
                    'UTF-8'
            )

            LogUtils.info(
                    "startup hook written to ${target}"
            )

            updateHookButton()

            status.text =
                    "Startup hook written. It takes effect the next time Freeplane starts."
        }
        catch (Throwable t) {

            LogUtils.warn(
                    "could not write ${target}",
                    t
            )

            status.text =
                    "Could not write ${target}: ${t.message}"
        }
    } as ActionListener)

    updateHookButton()

    def pauseButton =
            new JButton()

    def updatePauseButton = {

        boolean paused =
                AutoRunDispatcher.isPaused()

        pauseButton.text =
                paused
                        ? 'Resume'
                        : 'Pause all'

        pauseButton.toolTipText =
                paused
                        ? 'Triggers are suspended. Manual runs still work, and a restart resumes them anyway.'
                        : 'Suspend every trigger without touching the configuration'
    }

    pauseButton.addActionListener({

        boolean paused =
                AutoRunDispatcher.isPaused()

        UIManager.put(
                AutoRunDispatcher.PAUSED_KEY,
                paused
                        ? null
                        : Boolean.TRUE
        )

        updatePauseButton()

        def message =
                paused
                        ? 'Auto-run triggers resumed.'
                        : 'Auto-run triggers paused until you resume or restart.'

        Controller.currentController
                ?.viewController
                ?.out(message)

        status.text =
                "${message} Now: ${describe()}."
    } as ActionListener)

    updatePauseButton()

    def runSelectedButton =
            new JButton(
                    'Run selected'
            )

    def runAllButton =
            new JButton(
                    'Run all ticked'
            )

    def groupsButton =
            new JButton(
                    'Groups...'
            )

    groupsButton.toolTipText =
            'Create, rename, delete and manage virtual groups. This never moves script files.'

    groupsButton.addActionListener({
        openGroupManager()
    } as ActionListener)

    def historyButton =
            new JButton(
                    'History...'
            )

    historyButton.toolTipText =
            'What ran automatically since Freeplane started, and how long it took'

    historyButton.addActionListener({
        openHistory()
    } as ActionListener)

    def closeButton =
            new JButton(
                    'Close'
            )

    upButton.addActionListener({
        moveSelected(-1)
    } as ActionListener)

    downButton.addActionListener({
        moveSelected(1)
    } as ActionListener)

    runSelectedButton.addActionListener({

        int viewRow =
                table.selectedRow

        runFiles(
                viewRow < 0
                        ? []
                        : [
                                model.fileAt(
                                        table.convertRowIndexToModel(
                                                viewRow
                                        )
                                )
                        ]
        )
    } as ActionListener)

    runAllButton.addActionListener({
        runFiles(
                model.active.collect {
                    it.file
                }
        )
    } as ActionListener)

    def buttonBar =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.LEFT,
                            6,
                            6
                    )
            )

    [
            upButton,
            downButton,
            removeButton,
            runSelectedButton,
            runAllButton,
            groupsButton,
            historyButton,
            pauseButton,
            hookButton
    ].each {
        buttonBar.add(it)
    }

    def rightBar =
            new JPanel(
                    new FlowLayout(
                            FlowLayout.RIGHT,
                            6,
                            6
                    )
            )

    rightBar.add(closeButton)

    def footer =
            new JPanel(
                    new BorderLayout()
            )

    footer.add(
            buttonBar,
            BorderLayout.WEST
    )

    footer.add(
            rightBar,
            BorderLayout.EAST
    )

    def statusBar =
            new JPanel(
                    new BorderLayout(
                            6,
                            0
                    )
            )

    statusBar.border =
            BorderFactory.createEmptyBorder(
                    0,
                    8,
                    6,
                    8
            )

    statusBar.add(
            status,
            BorderLayout.CENTER
    )

    def pathLabel =
            new JLabel(
                    listFile.absolutePath
            )

    pathLabel.font =
            pathLabel.font.deriveFont(
                    (float) (
                            pathLabel.font.size2D -
                                    1f
                    )
            )

    pathLabel.enabled = false

    pathLabel.toolTipText =
            listFile.absolutePath +
                    '\nVirtual groups: ' +
                    groupFile.absolutePath

    [
            status,
            pathLabel
    ].each {
        it.preferredSize =
                new Dimension(
                        0,
                        (int) it.preferredSize.height
                )
    }

    statusBar.add(
            pathLabel,
            BorderLayout.SOUTH
    )

    status.toolTipText =
            '<html>Folders searched for scripts:<br>' +
            searchedDirs().collect {
                it.absolutePath +
                        (it.isDirectory()
                                ? ''
                                : ' (does not exist)')
            }.join('<br>') +
            '</html>'

    def content =
            new JPanel(
                    new BorderLayout()
            )

    content.add(
            filterBar,
            BorderLayout.NORTH
    )

    content.add(
            new JScrollPane(table),
            BorderLayout.CENTER
    )

    def south =
            new JPanel(
                    new BorderLayout()
            )

    south.add(
            footer,
            BorderLayout.NORTH
    )

    south.add(
            statusBar,
            BorderLayout.SOUTH
    )

    content.add(
            south,
            BorderLayout.SOUTH
    )

    def dialog =
            new JDialog(
                    UITools.currentFrame,
                    'Scripts that run automatically',
                    false
            )

    dialog.name =
            dialogName

    dialog.contentPane =
            content

    dialog.pack()

    closeButton.addActionListener({
        dialog.dispose()
    } as ActionListener)

    dialog.rootPane.defaultButton =
            closeButton

    dialog.rootPane.registerKeyboardAction({
        dialog.dispose()
    } as ActionListener,
            KeyStroke.getKeyStroke(
                    KeyEvent.VK_ESCAPE,
                    0
            ),
            JComponent.WHEN_IN_FOCUSED_WINDOW
    )

    def summary =
            model.rowCount
                    ? "${describe()}, out of ${model.rowCount} script(s) found."
                    : (
                            "No script found in the ${searchedDirs().size()} folder(s) searched -- the status bar tooltip lists them. " +
                            'Further folders are added in Preferences > Plugins > Scripting.'
                    ).toString()

    status.text =
            installedBridge() == null
                    ? "Startup hook missing: nothing will run at the next start. ${summary}"
                    : (
                            outdatedBridge()
                                    ? "Startup hook is from an earlier version: press Update startup hook. ${summary}"
                                    : summary
                    )

    placeInsideScreen(
            dialog,
            0
    )

    dialog.visible = true

    return dialog
}

def bootstrap =
        Boolean.TRUE ==
                UIManager.get(
                        'autoRunScripts.bootstrap'
                )

UIManager.put(
        'autoRunScripts.bootstrap',
        null
)

if (bootstrap) {

    def message =
            install(true)

    LogUtils.info(message)

    Controller.currentController
            .viewController
            ?.out(message)

    return message
}

if (UIManager.get(
        AutoRunDispatcher.GENERATION_KEY
) == null) {

    LogUtils.info(
            install(false)
    )
}

openDialog()

'Auto-run scripts dialog opened.'
