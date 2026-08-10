// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Version: 1.0

/**
 * Paste special — one dialog that combines the clipboard formats of "Edit -> Paste as..."
 * with control over WHERE the pasted nodes land, plus the paste-adjacent operations that
 * live elsewhere in the menus (clone, link, connector).
 *
 * Freeplane always pastes into a fixed slot: the new nodes become the first or the last
 * children of the selected node, depending on the "New child nodes" preference. This
 * script lets you choose the slot instead, and it also offers "as sibling before/after"
 * and "as parent", which so far needed the mouse or a different command.
 *
 * Everything is one undo step: the paste and the repositioning happen in the same event
 * dispatch, and Freeplane composes those into a single undoable action.
 *
 * Keyboard (the point of the dialog — it is meant to be operated without the mouse):
 *
 *     1..9, 0, a..z   pick the option with that key and run it right away; the numbering
 *                     runs continuously through the groups and falls back to letters, so
 *                     one keystroke is the whole operation. Two kinds of option do not run
 *                     on their own: the two "at position" slots focus their text field with
 *                     the number selected (typing overwrites it, Enter finishes), and the
 *                     Folding options only qualify the paste, so they wait for Enter too.
 *     Alt + that key  make that option the default, without pasting
 *     Esc             leaves the position field / closes the dialog
 *     Enter           runs with whatever is selected
 *
 * Clicking an option with the mouse only selects it. That is what keeps a non-default
 * format reachable together with a non-default slot: pick the format with the mouse (or
 * the arrow keys), then press the key of the slot to paste.
 *
 * The first two groups always hold the same eleven options, so their keys never move. The
 * formats come last because how many of them exist depends on the clipboard.
 *
 * THE "default" COLUMN sets what a plain paste does from then on. Every option can be the
 * default, because the script keeps its own preferences; where Freeplane happens to have an
 * equivalent one, the choice is mirrored into it as well, so the built-in Ctrl+V follows
 * along as far as it can. Clicking a radio button there (or Alt + the option key) stores it
 * immediately, without pasting. The companion script PasteWithDefaults.groovy is what obeys
 * the rest — see the comment above vanillaPreferencesForSlot() for the split.
 *
 * The dialog opens on the stored defaults, falling back to what Ctrl+V would have done.
 *
 * Suggested shortcut: control shift V (replacing the built-in "Paste as...").
 */

import groovy.transform.Field

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Image
import java.io.StringReader
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.InputEvent
import java.awt.event.ItemEvent
import java.awt.event.ItemListener
import java.awt.event.KeyEvent
import java.lang.reflect.Method
import java.util.List

import javax.swing.AbstractAction
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ImageIcon
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTextField
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreePath

import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

import org.freeplane.core.resources.ResourceController
import org.freeplane.core.ui.components.UITools
import org.freeplane.core.util.HtmlUtils
import org.freeplane.core.util.TextUtils
import org.freeplane.features.filter.Filter
import org.freeplane.features.link.LinkController
import org.freeplane.features.link.mindmapmode.MLinkController
import org.freeplane.features.map.MapController
import org.freeplane.features.map.NodeModel
import org.freeplane.features.map.NodeModel.Side
import org.freeplane.features.map.clipboard.MapClipboardController
import org.freeplane.features.map.clipboard.MindMapNodesSelection
import org.freeplane.features.map.mindmapmode.MMapController
import org.freeplane.features.map.mindmapmode.clipboard.MMapClipboardController
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.proxy.ScriptUtils

final String DIALOG_NAME = 'pasteSpecialDialog'

// Slot kinds, in the order the dialog lists them.
final String FIRST_CHILD = 'firstChild'
final String AT_POSITION = 'atPosition'
final String LAST_CHILD = 'lastChild'
final String SIBLING_BEFORE = 'siblingBefore'
final String SIBLING_AFTER = 'siblingAfter'
final String SIBLING_AT = 'siblingAt'
final String AS_PARENT = 'asParent'
final String LINK_FROM_COPIED = 'linkFromCopied'
final String LINK_TO_COPIED = 'linkToCopied'
final String CONNECTOR_FROM_COPIED = 'connectorFromCopied'
final String CONNECTOR_TO_COPIED = 'connectorToCopied'

@Field JDialog dialog
@Field JTextField positionField
@Field JTextField siblingPositionField
@Field JRadioButton atPositionButton
@Field JRadioButton atSiblingPositionButton
@Field JRadioButton cloneButton
@Field Closure pasteAction
@Field Map<String, JRadioButton> slotButtons = [:]
@Field List<JRadioButton> formatButtons = []
@Field List<Object> flavorHandlers = []
@Field List<AbstractButton> numberedButtons = []
@Field Map<Integer, JRadioButton> defaultRadios = [:]
@Field Map<Integer, String> defaultSlotValues = [:]
@Field Map<Integer, String> defaultFormatValues = [:]
@Field Map<Integer, String> defaultFoldingValues = [:]
@Field Map<Integer, String> defaultUnfoldTargetValues = [:]
// Options that only adjust how the paste behaves. Their key selects them and stops there,
// because running the paste on the spot would defeat the point of setting a modifier.
@Field Set<Integer> modifierIndexes = new HashSet<Integer>()
@Field JRadioButton foldPastedButton
@Field JRadioButton unfoldPastedButton
@Field JRadioButton unfoldTargetButton
@Field JPanel previewHost
@Field JLabel previewTitle
@Field Closure mapReleaser

@Field int PREVIEW_LINE_LIMIT = 60
@Field int PREVIEW_TEXT_LIMIT = 70
@Field int PREVIEW_HEIGHT = 150
@Field int PREVIEW_IMAGE_MAX = 420

@Field NodeModel anchorNode
@Field int anchorChildCount
@Field List<NodeModel> copiedNodes = []
@Field Transferable clipboardContents
@Field MMapClipboardController clipboardController
@Field Method pasteWithHandlerMethod

// ---------------------------------------------------------------- reflection bridge
//
// getFlavorHandlers(), getFlavorHandler(Transferable) and paste(Transferable,
// IDataFlavorHandler, NodeModel, Side) are package private, and IDataFlavorHandler is a
// package private interface, so there is no public way to reach the individual clipboard
// formats. The public API only offers paste(Transferable, NodeModel, Side), which always
// picks the default format. When reflection is unavailable the script degrades to that
// single "Default" format instead of failing.

List<Object> readFlavorHandlers(MMapClipboardController controller) {
    try {
        Method method = MMapClipboardController.class.getDeclaredMethod('getFlavorHandlers')
        method.setAccessible(true)
        return new ArrayList<Object>((Collection) method.invoke(controller))
    }
    catch (Throwable ignored) {
        return []
    }
}

Object readDefaultFlavorHandler(MMapClipboardController controller, Transferable transferable) {
    try {
        Method method = MMapClipboardController.class.getDeclaredMethod('getFlavorHandler', Transferable)
        method.setAccessible(true)
        return method.invoke(controller, transferable)
    }
    catch (Throwable ignored) {
        return null
    }
}

Method findPasteWithHandlerMethod() {
    try {
        Method method = MMapClipboardController.class.getDeclaredMethods().find {
            it.name == 'paste' && it.parameterTypes.length == 4 &&
                    it.parameterTypes[1].simpleName == 'IDataFlavorHandler'
        }
        method?.setAccessible(true)
        return method
    }
    catch (Throwable ignored) {
        return null
    }
}

/**
 * The live NodeModels behind a Freeplane copy. They exist only while the nodes that were
 * copied are still in an open map, which is exactly the condition for cloning, linking and
 * connecting — so an empty list here is what disables those options.
 */
List<NodeModel> readCopiedNodes(Transferable transferable) {
    List<DataFlavor> flavors = [MindMapNodesSelection.mindMapNodeSingleObjectsFlavor,
                                MindMapNodesSelection.mindMapNodeObjectsFlavor]
    for (DataFlavor flavor : flavors) {
        if (transferable.isDataFlavorSupported(flavor)) {
            try {
                return new ArrayList<NodeModel>((Collection<NodeModel>) transferable.getTransferData(flavor))
            }
            catch (Throwable ignored) {
            }
        }
    }
    return []
}

// ---------------------------------------------------------------- preview
//
// The preview answers "what will THIS format make of the clipboard", not "what is in the
// clipboard" — the same bytes become one node or twenty depending on the chosen format.
//
// To avoid a preview that disagrees with the paste, it runs the Freeplane parsers rather
// than reimplementing them: every text and HTML handler turns its content into
// TextFragment[] (text + indentation depth) through a private split(String), and that is
// exactly what the paste itself consumes. Freeplane's own node format is the one case with
// no parser to borrow — it is XML, read here with StAX.
//
// Everything below degrades to null on any surprise: a dialog without a preview is fine,
// a dialog that fails to open is not.

String readHandlerString(Object handler, String fieldName) {
    try {
        java.lang.reflect.Field field = handler.getClass().getDeclaredField(fieldName)
        field.setAccessible(true)
        return String.valueOf(field.get(handler))
    }
    catch (Throwable ignored) {
        return null
    }
}

Object readHandlerObject(Object handler, String fieldName) {
    try {
        java.lang.reflect.Field field = handler.getClass().getDeclaredField(fieldName)
        field.setAccessible(true)
        return field.get(handler)
    }
    catch (Throwable ignored) {
        return null
    }
}

String cleanHtmlLikeFreeplane(String content) {
    try {
        Method method = MMapClipboardController.class.getDeclaredMethod('cleanHtml', String)
        method.setAccessible(true)
        return (String) method.invoke(clipboardController, content)
    }
    catch (Throwable ignored) {
        return content
    }
}

/**
 * Runs the handler's own split() and turns the flat list of (text, depth) fragments into
 * indented preview lines, the same way pasteStringWithoutRedisplay nests them: a fragment
 * deeper than the one before becomes its child.
 */
List<List> previewFromFragments(Object handler, String className) {
    String raw = readHandlerString(handler, 'textFromClipboard')
    if (raw == null) {
        return null
    }
    try {
        Method split = handler.getClass().getDeclaredMethod('split', String)
        split.setAccessible(true)
        String input = (className == 'StringFlavorHandler') ? raw : cleanHtmlLikeFreeplane(raw)
        Object[] fragments = (Object[]) split.invoke(handler, input)
        List<List> lines = []
        List<Integer> openDepths = []
        fragments.each { Object fragment ->
            java.lang.reflect.Field textField = fragment.getClass().getDeclaredField('text')
            java.lang.reflect.Field depthField = fragment.getClass().getDeclaredField('depth')
            textField.setAccessible(true)
            depthField.setAccessible(true)
            String text = String.valueOf(textField.get(fragment))
            int depth = (int) depthField.get(fragment)
            if (depth == -2) {
                // an attribute line, which belongs to the node above it
                lines.add([openDepths.size(), '= ' + HtmlUtils.htmlToPlain(text), true])
                return
            }
            while (!openDepths.isEmpty() && depth <= openDepths[-1]) {
                openDepths.remove(openDepths.size() - 1)
            }
            lines.add([openDepths.size(), HtmlUtils.htmlToPlain(text), false])
            openDepths.add(depth)
        }
        return lines
    }
    catch (Throwable ignored) {
        return null
    }
}

/** Freeplane's own node format: XML pieces separated by NODESEPARATOR. */
List<List> previewFromNodeXml(String xml) {
    List<List> lines = []
    try {
        XMLInputFactory factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE)
        xml.split(MapClipboardController.NODESEPARATOR).each { String piece ->
            if (!piece?.trim()) {
                return
            }
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(piece))
            int depth = 0
            int pendingRichTextLine = -1
            while (reader.hasNext()) {
                int event = reader.next()
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (reader.localName == 'node') {
                        String text = reader.getAttributeValue(null, 'TEXT')
                        // A formatted node carries no TEXT: its content is in a richcontent
                        // child, picked up by the CHARACTERS branch below.
                        lines.add([depth, text == null ? '' : text, false])
                        pendingRichTextLine = (text == null) ? lines.size() - 1 : -1
                        depth++
                    }
                }
                else if (event == XMLStreamConstants.CHARACTERS && pendingRichTextLine >= 0) {
                    String content = reader.text?.trim()
                    if (content && content.contains('<')) {
                        lines[pendingRichTextLine][1] = HtmlUtils.htmlToPlain(content)
                        pendingRichTextLine = -1
                    }
                }
                else if (event == XMLStreamConstants.END_ELEMENT && reader.localName == 'node') {
                    depth--
                }
            }
            reader.close()
        }
        return lines
    }
    catch (Throwable ignored) {
        return lines.isEmpty() ? null : lines
    }
}

/** The lines to show for a given format, or null when the format shows something else. */
List<List> previewLines(Object handler, String className) {
    if (className == 'MindMapNodesFlavorHandler') {
        String raw = readHandlerString(handler, 'textFromClipboard')
        return raw == null ? null : previewFromNodeXml(raw)
    }
    if (className == 'DirectHtmlFlavorHandler') {
        String raw = readHandlerString(handler, 'textFromClipboard')
        return raw == null ? null : [[0, HtmlUtils.htmlToPlain(cleanHtmlLikeFreeplane(raw)), false]]
    }
    if (className == 'FileListFlavorHandler') {
        Object files = readHandlerObject(handler, 'fileList')
        return files == null ? null : ((Collection) files).collect { [0, String.valueOf(((File) it).name), false] }
    }
    if (className in ['StringFlavorHandler', 'StructuredHtmlFlavorHandler', 'StructuredTextFromHtmlFlavorHandler']) {
        return previewFromFragments(handler, className)
    }
    return null
}

/** Cloning copies live nodes, so the preview is simply those nodes. */
List<List> previewFromCopiedNodes() {
    List<List> lines = []
    Closure walk
    walk = { NodeModel node, int depth ->
        lines.add([depth, node.text == null ? '' : HtmlUtils.htmlToPlain(node.text), false])
        node.children.each { walk(it, depth + 1) }
    }
    copiedNodes.each { walk(it, 0) }
    return lines
}

Component buildPreviewTree(List<List> lines) {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode()
    List<DefaultMutableTreeNode> openNodes = [root]
    int shown = 0
    for (List line : lines) {
        if (shown >= PREVIEW_LINE_LIMIT) {
            root.add(new DefaultMutableTreeNode('… and ' + (lines.size() - shown) + ' more'))
            break
        }
        int depth = (int) line[0]
        // One line per node: a format that folds a whole outline into a single node brings
        // its line breaks along, and they would otherwise wreck the tree layout.
        String text = String.valueOf(line[1]).replaceAll(/\s+/, ' ').trim()
        if (text.length() > PREVIEW_TEXT_LIMIT) {
            text = text.substring(0, PREVIEW_TEXT_LIMIT - 1) + '…'
        }
        if (text.isEmpty()) {
            text = '(empty node)'
        }
        while (openNodes.size() > depth + 1) {
            openNodes.remove(openNodes.size() - 1)
        }
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(text)
        openNodes[-1].add(treeNode)
        openNodes.add(treeNode)
        shown++
    }

    JTree tree = new JTree(root)
    tree.rootVisible = false
    tree.showsRootHandles = false
    // Row height 0 makes the tree ask the renderer how tall each row is. The default is a
    // fixed 16px, which clips the text on any setup with a larger font.
    tree.rowHeight = 0
    DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer()
    renderer.leafIcon = null
    renderer.openIcon = null
    renderer.closedIcon = null
    tree.cellRenderer = renderer
    // Expand everything: a collapsed preview would hide the very structure it is showing.
    int expanded = 0
    while (expanded < tree.rowCount) {
        tree.expandRow(expanded++)
    }
    return tree
}

Component buildPreviewImage(Object handler) {
    Image image = (Image) readHandlerObject(handler, 'image')
    if (image == null) {
        return null
    }
    int width = image.getWidth(null)
    int height = image.getHeight(null)
    double scale = Math.min(1.0d, Math.min(PREVIEW_IMAGE_MAX / (double) Math.max(1, width),
            (double) PREVIEW_HEIGHT / (double) Math.max(1, height)))
    JLabel label = new JLabel(new ImageIcon(image.getScaledInstance(
            Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), Image.SCALE_SMOOTH)))
    label.horizontalAlignment = SwingConstants.LEADING
    return label
}

/** Rebuilds the preview for whichever format is selected right now. */
void updatePreview() {
    if (previewHost == null) {
        return
    }
    String title
    Component body
    if (usesClone()) {
        List<List> lines = previewFromCopiedNodes()
        title = 'Preview — ' + lines.size() + (lines.size() == 1 ? ' node, cloned' : ' nodes, cloned')
        body = buildPreviewTree(lines)
    }
    else {
        Object handler = selectedHandler()
        String className = handler?.getClass()?.simpleName
        if (className == 'ImageFlavorHandler') {
            Image image = (Image) readHandlerObject(handler, 'image')
            body = buildPreviewImage(handler)
            title = 'Preview — image' + (image == null ? '' : ' ' + image.getWidth(null) + '×' + image.getHeight(null))
        }
        else {
            List<List> lines = (handler == null) ? null : previewLines(handler, className)
            if (lines == null) {
                body = null
                title = 'Preview'
            }
            else {
                int nodeCount = lines.count { !(boolean) it[2] }
                title = 'Preview — ' + nodeCount + (nodeCount == 1 ? ' node' : ' nodes')
                body = buildPreviewTree(lines)
            }
        }
    }
    if (body == null) {
        body = new JLabel('(nothing to preview for this format)')
        body.enabled = false
    }
    previewTitle.text = title
    previewHost.removeAll()
    previewHost.add(body, BorderLayout.CENTER)
    previewHost.revalidate()
    previewHost.repaint()
}
//
// Every option can be made the default, because the script keeps its own preferences:
//
//   pasteSpecial.defaultSlot      the option in the first two groups (a position, or a
//                                 link/connector relation)
//   pasteSpecial.defaultPosition  the number, for the two "at position" slots
//   pasteSpecial.defaultFormat    the clipboard format, or "clone"
//
// Those are what the companion script PasteWithDefaults.groovy reads, and what this dialog
// opens with. Where vanilla Freeplane happens to have an equivalent preference of its own,
// the choice is MIRRORED into it as well, so the built-in Ctrl+V improves too:
//
//   placenewbranches                   first | last   -> "First child" / "Last child"
//   remind_use_rich_text_in_new_nodes  true | false    -> HTML formats vs plain text
//   structured_html_import             true | false    -> which of the two HTML formats
//
// Everything else has no vanilla equivalent — Freeplane cannot express "paste as sibling"
// or "paste as a clone" as a default at all — so only the companion follows those.

Map<String, String> vanillaPreferencesForSlot(String slot) {
    switch (slot) {
        case 'firstChild':
            return ['placenewbranches': 'first']
        case 'lastChild':
            return ['placenewbranches': 'last']
        default:
            return null
    }
}

Map<String, String> vanillaPreferencesForFormat(String formatName) {
    switch (formatName) {
        case 'StructuredHtmlFlavorHandler':
            return ['remind_use_rich_text_in_new_nodes': 'true', 'structured_html_import': 'true']
        case 'DirectHtmlFlavorHandler':
            return ['remind_use_rich_text_in_new_nodes': 'true', 'structured_html_import': 'false']
        case 'StringFlavorHandler':
            return ['remind_use_rich_text_in_new_nodes': 'false']
        default:
            return null
    }
}

/** The stored slot, or the one vanilla Freeplane would use when nothing was stored yet. */
String storedDefaultSlot() {
    ResourceController resourceController = ResourceController.getResourceController()
    String stored = resourceController.getProperty('pasteSpecial.defaultSlot')
    if (stored) {
        return stored
    }
    return 'first' == resourceController.getProperty('placenewbranches') ? 'firstChild' : 'lastChild'
}

/** The stored format, or the one the vanilla preferences point at. */
String storedDefaultFormat() {
    ResourceController resourceController = ResourceController.getResourceController()
    String stored = resourceController.getProperty('pasteSpecial.defaultFormat')
    if (stored) {
        return stored
    }
    if ('false' == resourceController.getProperty('remind_use_rich_text_in_new_nodes')) {
        return 'StringFlavorHandler'
    }
    if ('true' == resourceController.getProperty('remind_use_rich_text_in_new_nodes')) {
        return 'true' == resourceController.getProperty('structured_html_import')
                ? 'StructuredHtmlFlavorHandler' : 'DirectHtmlFlavorHandler'
    }
    return null
}

/** How the pasted nodes are folded: as they came, all folded, or all unfolded. */
String storedDefaultFolding() {
    return ResourceController.getResourceController().getProperty('pasteSpecial.defaultFolding') ?: 'keep'
}

/** Whether a folded target node gets unfolded — this one Freeplane already has. */
boolean storedDefaultUnfoldTarget() {
    return ResourceController.getResourceController().getBooleanProperty('unfold_on_paste')
}

void storeDefault(int index) {
    ResourceController resourceController = ResourceController.getResourceController()
    String slot = defaultSlotValues[index]
    String format = defaultFormatValues[index]
    String folding = defaultFoldingValues[index]
    String unfoldTarget = defaultUnfoldTargetValues[index]
    Map<String, String> mirror
    if (slot != null) {
        resourceController.setProperty('pasteSpecial.defaultSlot', slot)
        if (slot == 'atPosition' || slot == 'siblingAt') {
            JTextField field = (slot == 'atPosition') ? positionField : siblingPositionField
            resourceController.setProperty('pasteSpecial.defaultPosition', String.valueOf(requestedPosition(field)))
        }
        mirror = vanillaPreferencesForSlot(slot)
    }
    else if (format != null) {
        resourceController.setProperty('pasteSpecial.defaultFormat', format)
        mirror = vanillaPreferencesForFormat(format)
    }
    else if (folding != null) {
        resourceController.setProperty('pasteSpecial.defaultFolding', folding)
    }
    else if (unfoldTarget != null) {
        // Freeplane's own preference is the only storage this one needs.
        resourceController.setProperty('unfold_on_paste', unfoldTarget)
        mirror = ['unfold_on_paste': unfoldTarget]
    }
    else {
        return
    }
    mirror?.each { String key, String value -> resourceController.setProperty(key, value) }
    defaultRadios[index]?.selected = true
    String label = numberedButtons[index].text.replaceFirst(/^\S+\s+/, '')
    ScriptUtils.c().statusInfo = ('Paste special: “' + label + '” is now the default' +
            (mirror == null ? ' (followed by the companion script).' : '.')).toString()
}

// ---------------------------------------------------------------- dialog construction

String shortcutKeyAt(int index) {
    if (index < 9) {
        return String.valueOf(index + 1)
    }
    if (index == 9) {
        return '0'
    }
    // Groovy widens char arithmetic to int, so the cast has to wrap the whole sum.
    int letter = index - 10
    return letter < 26 ? String.valueOf((char) (((int) ('a' as char)) + letter)) : ' '
}

int keyCodeFor(String key) {
    return key.charAt(0).isDigit()
            ? KeyEvent.VK_0 + Integer.parseInt(key)
            : KeyEvent.VK_A + ((int) key.charAt(0)) - ((int) ('a' as char))
}

String numberedLabel(String label) {
    // An em space: two plain spaces nearly vanish next to the radio button.
    return shortcutKeyAt(numberedButtons.size()) + ' ' + label
}

JLabel sectionLabel(String text) {
    JLabel label = new JLabel(text)
    label.font = label.font.deriveFont(Font.BOLD)
    return label
}

boolean editingPosition() {
    return (positionField != null && positionField.hasFocus()) ||
            (siblingPositionField != null && siblingPositionField.hasFocus())
}

/**
 * A key is the whole operation: pick the option and run it. The two slots that take a
 * position are the exception — there the key only hands the keyboard to the field, and
 * Enter finishes.
 */
void selectByIndex(int index) {
    if (editingPosition() || index < 0 || index >= numberedButtons.size()) {
        return
    }
    AbstractButton button = numberedButtons[index]
    if (!button.enabled) {
        return
    }
    button.selected = true
    if (button.is(atPositionButton)) {
        focusField(positionField)
    }
    else if (button.is(atSiblingPositionButton)) {
        focusField(siblingPositionField)
    }
    else if (!modifierIndexes.contains(index)) {
        pasteAction.call()
    }
}

void focusField(JTextField field) {
    field.requestFocusInWindow()
    field.selectAll()
}

String selectedSlot() {
    return slotButtons.find { key, button -> button.selected }?.key
}

Object selectedHandler() {
    int index = formatButtons.findIndexOf { it.selected }
    return (index >= 0 && index < flavorHandlers.size()) ? flavorHandlers[index] : null
}

boolean usesClone() {
    return cloneButton != null && cloneButton.selected
}

String selectedFolding() {
    if (foldPastedButton?.selected) {
        return 'folded'
    }
    return unfoldPastedButton?.selected ? 'unfolded' : 'keep'
}

boolean unfoldsTarget() {
    return unfoldTargetButton != null && unfoldTargetButton.selected
}

int requestedPosition(JTextField field) {
    String text = field.text?.trim()
    if (!text) {
        return 1
    }
    try {
        return Integer.parseInt(text)
    }
    catch (NumberFormatException ignored) {
        return 1
    }
}

// ---------------------------------------------------------------- paste and placement

boolean containsSame(List<NodeModel> nodes, NodeModel candidate) {
    return nodes.any { it.is(candidate) }
}

/**
 * Rearranges the children of parent so that the pasted nodes sit right after the first
 * targetIndex nodes that were already there — wherever the paste itself happened to drop
 * them (first or last, per the "New child nodes" preference).
 *
 * The order is repaired one node at a time against the wanted order. Sweeping from the
 * left keeps everything already placed to the left of i untouched, so the node wanted at i
 * is guaranteed to sit at some index > i; sweeping from the right gives the mirror
 * guarantee. Picking the direction the pasted block has to travel makes the sweep move
 * only the pasted nodes in both of the cases that actually occur.
 *
 * MMapController.moveNodes() resolves its index against the list as it is *before* the
 * node leaves it, so a node travelling forward has to be given i + 1 to land on i.
 *
 * Choosing the direction is also what keeps summary nodes safe, so do not "simplify" it
 * away. A summary group is held together by position: an empty marker node before the
 * group and the summary node after it, both ordinary children of the same parent. On top
 * of that, moveNodes() silently expands a move through SummaryGroupEdgeListAdder — moving
 * the only node a summary covers drags the marker and the summary along, three nodes for
 * the price of one, which would wreck the arithmetic below. Sweeping in the direction the
 * pasted block travels means only the pasted nodes are ever moved, so neither trap is
 * sprung. Verified on maps with summary groups of one and of two nodes, in every slot.
 *
 * Pasting *inside* a group (a position between the marker and the summary node) makes the
 * group grow to include the new nodes. That is the same thing dragging a node in there
 * would do, so it is left alone.
 */
void arrangeNodes(MMapController mapController, List<NodeModel> pasted, NodeModel parent, int targetIndex) {
    List<NodeModel> foreign = pasted.findAll { !it.parentNode.is(parent) }
    if (!foreign.isEmpty()) {
        mapController.moveNodes(foreign, parent, parent.childCount)
    }
    List<NodeModel> current = new ArrayList<NodeModel>(parent.children)
    List<NodeModel> originals = current.findAll { NodeModel candidate -> !containsSame(pasted, candidate) }
    int index = Math.max(0, Math.min(originals.size(), targetIndex))

    List<NodeModel> wantedOrder = new ArrayList<NodeModel>()
    wantedOrder.addAll(originals.subList(0, index))
    wantedOrder.addAll(pasted)
    wantedOrder.addAll(originals.subList(index, originals.size()))

    int firstPastedPosition = current.findIndexOf { NodeModel candidate -> containsSame(pasted, candidate) }
    boolean movingRight = firstPastedPosition >= 0 && firstPastedPosition < index
    if (movingRight) {
        for (int i = wantedOrder.size() - 1; i >= 0; i--) {
            NodeModel wanted = wantedOrder[i]
            if (parent.getIndex(wanted) != i) {
                mapController.moveNodes([wanted], parent, i + 1)
            }
        }
    }
    else {
        for (int i = 0; i < wantedOrder.size(); i++) {
            NodeModel wanted = wantedOrder[i]
            if (parent.getIndex(wanted) != i) {
                mapController.moveNodes([wanted], parent, i)
            }
        }
    }
}

List<NodeModel> newChildrenOf(NodeModel parent, Set<NodeModel> before) {
    return parent.children.findAll { !before.contains(it) }
}

/**
 * Folds or unfolds what was just pasted. "Folded" only closes the nodes that were pasted at
 * top level — closing those already hides everything below. "Unfolded" has to walk the whole
 * subtree instead, because branches can arrive closed from the clipboard and would stay so.
 */
void applyFoldingToPasted(MMapController mapController, List<NodeModel> pasted, String folding) {
    if (folding == 'keep') {
        return
    }
    Filter filter = Controller.currentController.selection.filter
    boolean fold = (folding == 'folded')
    if (fold) {
        pasted.findAll { it.childCount > 0 }.each { mapController.setFolded(it, true, filter) }
        return
    }
    List<NodeModel> pending = new ArrayList<NodeModel>(pasted)
    while (!pending.isEmpty()) {
        NodeModel current = pending.remove(0)
        if (current.childCount > 0) {
            mapController.setFolded(current, false, filter)
            pending.addAll(current.children)
        }
    }
}

/**
 * Link and connector are not a paste at all: they record a relation between the node that
 * was copied and the node the caret is on, in the direction the option names.
 */
void performRelation(String slot) {
    if (copiedNodes.isEmpty()) {
        return
    }
    MLinkController linkController = (MLinkController) LinkController.getController()
    boolean fromCopied = slot.contains('FromCopied')
    boolean isLink = slot.startsWith('link')
    // A node holds one hyperlink, so linking several copied nodes into this one would keep
    // only the last. Connectors accumulate, and so does the other direction.
    List<NodeModel> others = (isLink && !fromCopied) ? copiedNodes.subList(0, 1) : copiedNodes
    others.each { NodeModel other ->
        NodeModel source = fromCopied ? other : anchorNode
        NodeModel target = fromCopied ? anchorNode : other
        if (isLink) {
            linkController.setLinkTypeDependantLink(source, '#' + target.createID())
        }
        else {
            linkController.addConnector(source, target)
        }
    }
    if (isLink && !fromCopied && copiedNodes.size() > 1) {
        ScriptUtils.c().statusInfo = 'Paste special: a node holds one link, so only the first copied node was used.'
    }
    else {
        String what = isLink ? 'Link' : 'Connector'
        ScriptUtils.c().statusInfo = ('Paste special: ' + what + ' created (' + others.size() + ').').toString()
    }
}

void performPaste() {
    MMapController mapController = (MMapController) Controller.currentModeController.mapController
    String slot = selectedSlot()
    if (slot.startsWith('link') || slot.startsWith('connector')) {
        performRelation(slot)
        return
    }

    boolean asParent = (slot == 'asParent')
    boolean sibling = asParent || slot == 'siblingBefore' || slot == 'siblingAfter' || slot == 'siblingAt'
    NodeModel parent = sibling ? anchorNode.parentNode : anchorNode
    if (parent == null) {
        ScriptUtils.c().statusInfo = 'Paste special: the root node has no siblings.'
        return
    }

    int targetIndex
    if (asParent) {
        targetIndex = parent.getIndex(anchorNode)
    }
    else if (slot == 'siblingAt') {
        targetIndex = Math.max(0, Math.min(parent.childCount, requestedPosition(siblingPositionField) - 1))
    }
    else if (sibling) {
        int anchorIndex = parent.getIndex(anchorNode)
        targetIndex = (slot == 'siblingAfter') ? anchorIndex + 1 : anchorIndex
    }
    else if (slot == 'firstChild') {
        targetIndex = 0
    }
    else if (slot == 'lastChild') {
        targetIndex = anchorChildCount
    }
    else {
        targetIndex = Math.max(0, Math.min(anchorChildCount, requestedPosition(positionField) - 1))
    }

    Side side
    if (slot == 'siblingBefore' || asParent) {
        side = Side.AS_SIBLING_BEFORE
    }
    else if (sibling) {
        // "At sibling position" lands next to the anchor first and is moved afterwards;
        // pasting with a sibling side is what gets the nodes into the right parent.
        side = Side.AS_SIBLING_AFTER
    }
    else {
        side = MapController.suggestNewChildSide(anchorNode, Side.DEFAULT)
    }

    Set<NodeModel> beforeInAnchor = new HashSet<NodeModel>(anchorNode.children)
    Set<NodeModel> beforeInParent = new HashSet<NodeModel>(parent.children)
    boolean targetWasFolded = !sibling && mapController.isFolded(anchorNode)

    if (usesClone()) {
        // addClone() has no notion of a side: it always adds children to the node it is
        // given, and the arranging below moves them wherever they were asked to go.
        clipboardController.addClone(clipboardContents, parent)
    }
    else {
        Object handler = selectedHandler()
        if (handler != null && pasteWithHandlerMethod != null) {
            pasteWithHandlerMethod.invoke(clipboardController, clipboardContents, handler, anchorNode, side)
        }
        else {
            clipboardController.paste(clipboardContents, anchorNode, side)
        }
    }

    // Not every handler honours a sibling side (DirectHtmlFlavorHandler always inserts as
    // a child), so collect the new nodes from both candidate parents and move them.
    List<NodeModel> pasted = newChildrenOf(parent, beforeInParent)
    if (!parent.is(anchorNode)) {
        pasted += newChildrenOf(anchorNode, beforeInAnchor)
    }
    if (pasted.isEmpty()) {
        return
    }

    boolean crossedParent = pasted.any { !it.parentNode.is(parent) }
    arrangeNodes(mapController, pasted, parent, targetIndex)
    if (crossedParent) {
        mapController.setSide(pasted, sibling ? anchorNode.side : MapController.suggestNewChildSide(parent, Side.DEFAULT))
    }

    if (asParent) {
        // The first pasted node takes the place the selected node had; the selected node,
        // with its whole subtree, moves inside it. Any further pasted nodes stay alongside.
        NodeModel newParent = pasted[0]
        mapController.moveNodes([anchorNode], newParent, newParent.childCount)
        Controller.currentController.selection.replaceSelection([newParent] as NodeModel[])
    }
    else {
        Controller.currentController.selection.replaceSelection(pasted as NodeModel[])
    }

    applyFoldingToPasted(mapController, pasted, selectedFolding())
    // Freeplane unfolds the target on its own when unfold_on_paste is set; forcing the state
    // afterwards is what makes the dialog's choice win for this paste either way.
    if (targetWasFolded) {
        mapController.setFolded(anchorNode, !unfoldsTarget(), Controller.currentController.selection.filter)
    }
}

// ---------------------------------------------------------------- entry point

// PasteWithDefaults.groovy sets this to run the stored defaults without a dialog. Read it
// once and clear it immediately, so a crash cannot leave the dialog suppressed.
boolean silentRun = Boolean.TRUE == UIManager.get('pasteSpecial.silent')
UIManager.put('pasteSpecial.silent', null)

Controller controller = Controller.getCurrentController()
NodeModel selected = controller.selection?.selected
if (selected == null) {
    ScriptUtils.c().statusInfo = 'Paste special: no node is selected.'
    return
}

clipboardController = (MMapClipboardController) MapClipboardController.getController()
clipboardContents = clipboardController.getClipboardContents()
if (clipboardContents == null) {
    ScriptUtils.c().statusInfo = 'Paste special: the clipboard is empty.'
    return
}

flavorHandlers = readFlavorHandlers(clipboardController)
pasteWithHandlerMethod = findPasteWithHandlerMethod()
if (pasteWithHandlerMethod == null) {
    flavorHandlers = []
}
Object defaultHandler = readDefaultFlavorHandler(clipboardController, clipboardContents)

anchorNode = selected
anchorChildCount = selected.childCount
copiedNodes = readCopiedNodes(clipboardContents)

// Clone, link and connector all need the copied nodes to be alive in the same map.
boolean sameMapCopy = !copiedNodes.isEmpty() && copiedNodes.every { it.map.is(selected.map) }
boolean cloneAvailable = sameMapCopy && copiedNodes.every { !it.isRoot() && !it.subtreeContainsCloneOf(selected) }
String relationDisabledReason = copiedNodes.isEmpty()
        ? 'Needs nodes copied from a mind map in this session.'
        : (sameMapCopy ? null : 'The copied nodes belong to another map.')

// A leftover dialog from a previous invocation would compete for the option keys. A silent
// run must not touch it: it would close a dialog the user just opened.
if (!silentRun) {
    java.awt.Window.getWindows()
            .findAll { it.name == DIALOG_NAME && it.displayable }
            .each { it.dispose() }
}

numberedButtons = []
slotButtons = [:]
formatButtons = []
defaultRadios = [:]
defaultSlotValues = [:]
defaultFormatValues = [:]

boolean hasParent = selected.parentNode != null
boolean relationsAvailable = (relationDisabledReason == null)

/** A stored default only applies where it can: no siblings at the root, no relation without copied nodes. */
boolean slotApplies(String slot, boolean parentExists, boolean relationsOk) {
    if (!slot) {
        return false
    }
    if (slot.startsWith('link') || slot.startsWith('connector')) {
        return relationsOk
    }
    if (slot in ['siblingBefore', 'siblingAfter', 'siblingAt', 'asParent']) {
        return parentExists
    }
    return slot in ['firstChild', 'atPosition', 'lastChild']
}

JPanel content = new JPanel()
content.layout = new BoxLayout(content, BoxLayout.PAGE_AXIS)
content.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)

String anchorText = HtmlUtils.htmlToPlain(selected.text ?: '').replace('\n', ' ').trim()
if (anchorText.length() > 40) {
    anchorText = anchorText.substring(0, 39) + '…'
}
JLabel header = new JLabel('Into: “' + anchorText + '”  (' + anchorChildCount + ' children)')
header.alignmentX = Component.LEFT_ALIGNMENT
content.add(header)
content.add(Box.createVerticalStrut(12))

// Two columns of groups: stacked in one column the dialog grew taller than a screen. Each
// column carries its own "default" column, aligned within it.
JPanel grid = new JPanel(new GridBagLayout())
JPanel rightGrid = new JPanel(new GridBagLayout())
JPanel columns = new JPanel()
columns.layout = new BoxLayout(columns, BoxLayout.LINE_AXIS)
columns.alignmentX = Component.LEFT_ALIGNMENT
// Without this the shorter column would be centred against the taller one.
grid.alignmentY = Component.TOP_ALIGNMENT
rightGrid.alignmentY = Component.TOP_ALIGNMENT
columns.add(grid)
columns.add(Box.createHorizontalStrut(24))
columns.add(rightGrid)
ButtonGroup actionGroup = new ButtonGroup()
ButtonGroup slotDefaultGroup = new ButtonGroup()
ButtonGroup formatDefaultGroup = new ButtonGroup()
ButtonGroup foldingGroup = new ButtonGroup()
ButtonGroup foldingDefaultGroup = new ButtonGroup()
ButtonGroup targetGroup = new ButtonGroup()
ButtonGroup targetDefaultGroup = new ButtonGroup()

GridBagConstraints optionConstraints(int row, boolean firstOfGroup) {
    GridBagConstraints c = new GridBagConstraints()
    c.gridx = 0
    c.gridy = row
    c.anchor = GridBagConstraints.LINE_START
    c.weightx = 1
    c.fill = GridBagConstraints.HORIZONTAL
    c.insets = new Insets(firstOfGroup ? 8 : 0, 0, 0, 12)
    return c
}

GridBagConstraints defaultConstraints(int row, boolean firstOfGroup) {
    GridBagConstraints c = new GridBagConstraints()
    c.gridx = 1
    c.gridy = row
    c.anchor = GridBagConstraints.CENTER
    c.insets = new Insets(firstOfGroup ? 8 : 0, 0, 0, 0)
    return c
}

int row = 0

// ---- group 1: where to paste

MMapController mapController = (MMapController) Controller.currentModeController.mapController
boolean placesFirst = mapController.placesNewChildFirst(selected)

grid.add(sectionLabel('Where to paste'), optionConstraints(row, false))
JLabel defaultHeader = new JLabel('default', SwingConstants.CENTER)
defaultHeader.font = defaultHeader.font.deriveFont(Font.BOLD)
defaultHeader.toolTipText = 'What plain Ctrl+V uses. Click to change it — it is the same preference the Freeplane options dialog holds.'
grid.add(defaultHeader, defaultConstraints(row, false))
row++

JRadioButton addOption(JPanel box, ButtonGroup group, String label, boolean selected, GridBagConstraints c) {
    JRadioButton button = new JRadioButton(numberedLabel(label))
    button.iconTextGap = button.iconTextGap + 4
    button.selected = selected
    group.add(button)
    box.add(button, c)
    numberedButtons.add(button)
    return button
}

/**
 * Attaches the "default" radio button of the row that was just added. Exactly one of
 * slotValue and formatValue is set — that is what storeDefault() writes when it is picked.
 */
JRadioButton addDefaultRadio(JPanel box, ButtonGroup group, String kind, String value,
                             boolean selected, boolean vanillaBacked, GridBagConstraints c) {
    JRadioButton radio = new JRadioButton()
    radio.selected = selected
    radio.toolTipText = vanillaBacked
            ? 'Stored in Freeplane\'s own preference too, so the built-in paste follows it as well.'
            : 'Freeplane has no preference of its own for this one, so only the companion script (Paste with defaults) follows it.'
    group.add(radio)
    box.add(radio, c)
    int index = numberedButtons.size() - 1
    defaultRadios[index] = radio
    switch (kind) {
        case 'slot': defaultSlotValues[index] = value; break
        case 'format': defaultFormatValues[index] = value; break
        case 'folding': defaultFoldingValues[index] = value; break
        case 'target': defaultUnfoldTargetValues[index] = value; break
    }
    return radio
}

String storedSlot = storedDefaultSlot()
String storedFormat = storedDefaultFormat()
String storedPosition = ResourceController.getResourceController().getProperty('pasteSpecial.defaultPosition')
String effectiveSlot = slotApplies(storedSlot, hasParent, relationsAvailable)
        ? storedSlot
        : (placesFirst ? FIRST_CHILD : LAST_CHILD)

slotButtons[FIRST_CHILD] = addOption(grid, actionGroup, 'First child', effectiveSlot == FIRST_CHILD,
        optionConstraints(row, false))
addDefaultRadio(grid, slotDefaultGroup, 'slot', FIRST_CHILD, storedSlot == FIRST_CHILD, true,
        defaultConstraints(row, false))
row++

// The two "at position" options keep their text field on the same row as the radio button.
JTextField buildPositionRow(JPanel box, ButtonGroup group, JRadioButton button, String label,
                            int slotCount, int initialValue, GridBagConstraints c) {
    JPanel line = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0))
    button.text = numberedLabel(label)
    button.iconTextGap = button.iconTextGap + 4
    group.add(button)
    numberedButtons.add(button)
    JTextField field = new JTextField(4)
    field.text = String.valueOf(initialValue)
    field.maximumSize = new Dimension((int) field.preferredSize.width, (int) field.preferredSize.height)
    line.add(button)
    line.add(Box.createHorizontalStrut(6))
    line.add(field)
    line.add(new JLabel('  (1–' + slotCount + ')'))
    box.add(line, c)
    return field
}

atPositionButton = new JRadioButton()
int storedChildPosition = (storedSlot == AT_POSITION && storedPosition?.isInteger())
        ? storedPosition.toInteger() : (placesFirst ? 1 : anchorChildCount + 1)
positionField = buildPositionRow(grid, actionGroup, atPositionButton, 'At child position',
        anchorChildCount + 1, storedChildPosition, optionConstraints(row, false))
atPositionButton.selected = (effectiveSlot == AT_POSITION)
slotButtons[AT_POSITION] = atPositionButton
addDefaultRadio(grid, slotDefaultGroup, 'slot', AT_POSITION, storedSlot == AT_POSITION, false,
        defaultConstraints(row, false))
row++

slotButtons[LAST_CHILD] = addOption(grid, actionGroup, 'Last child', effectiveSlot == LAST_CHILD,
        optionConstraints(row, false))
addDefaultRadio(grid, slotDefaultGroup, 'slot', LAST_CHILD, storedSlot == LAST_CHILD, true,
        defaultConstraints(row, false))
row++

int siblingCount = hasParent ? selected.parentNode.childCount : 0
JRadioButton before = addOption(grid, actionGroup, 'As sibling before', effectiveSlot == SIBLING_BEFORE,
        optionConstraints(row, false))
addDefaultRadio(grid, slotDefaultGroup, 'slot', SIBLING_BEFORE, storedSlot == SIBLING_BEFORE, false,
        defaultConstraints(row, false))
row++
JRadioButton after = addOption(grid, actionGroup, 'As sibling after', effectiveSlot == SIBLING_AFTER,
        optionConstraints(row, false))
addDefaultRadio(grid, slotDefaultGroup, 'slot', SIBLING_AFTER, storedSlot == SIBLING_AFTER, false,
        defaultConstraints(row, false))
row++
atSiblingPositionButton = new JRadioButton()
int storedSiblingPosition = (storedSlot == SIBLING_AT && storedPosition?.isInteger())
        ? storedPosition.toInteger() : (hasParent ? selected.parentNode.getIndex(selected) + 1 : 1)
siblingPositionField = buildPositionRow(grid, actionGroup, atSiblingPositionButton, 'At sibling position',
        siblingCount + 1, storedSiblingPosition, optionConstraints(row, false))
atSiblingPositionButton.selected = (effectiveSlot == SIBLING_AT)
addDefaultRadio(grid, slotDefaultGroup, 'slot', SIBLING_AT, storedSlot == SIBLING_AT, false,
        defaultConstraints(row, false))
row++
JRadioButton asParent = addOption(grid, actionGroup, 'As parent of this node', effectiveSlot == AS_PARENT,
        optionConstraints(row, false))
addDefaultRadio(grid, slotDefaultGroup, 'slot', AS_PARENT, storedSlot == AS_PARENT, false,
        defaultConstraints(row, false))
row++
[before, after, atSiblingPositionButton, asParent, siblingPositionField].each { it.enabled = hasParent }
if (!hasParent) {
    [before, after, atSiblingPositionButton, asParent].each { it.toolTipText = 'The root node has no siblings.' }
}
slotButtons[SIBLING_BEFORE] = before
slotButtons[SIBLING_AFTER] = after
slotButtons[SIBLING_AT] = atSiblingPositionButton
slotButtons[AS_PARENT] = asParent

// ---- group 2: relations instead of a paste

grid.add(sectionLabel('Or, instead of pasting'), optionConstraints(row++, true))
// Named after where the arrow ENDS UP, which is what you see on the map — and the two
// cases are mirror images, so saying it plainly matters. A hyperlink shows its arrow icon
// on the node that holds the link, that is, on the node it points FROM. A connector draws
// its arrowhead at the far end, on the node it points TO (connector_arrows_default is
// FORWARD, which is start=NONE end=DEFAULT). So "arrow here" means this node points away
// for a link, and something points at this node for a connector.
Map<String, List<String>> relationLabels = [
        (LINK_TO_COPIED)       : ['Link — arrow here',
                                  'The link icon lands on this node, pointing at the copied one.'],
        (LINK_FROM_COPIED)     : ['Link — arrow there',
                                  'The link icon lands on the copied node, pointing back at this one.'],
        (CONNECTOR_FROM_COPIED): ['Connector — arrow here',
                                  'The arrowhead lands on this node: the connector runs from the copied node to this one.'],
        (CONNECTOR_TO_COPIED)  : ['Connector — arrow there',
                                  'The arrowhead lands on the copied node: the connector runs from this node to that one.'],
]
relationLabels.each { String slot, List<String> label ->
    JRadioButton button = addOption(grid, actionGroup, label[0], effectiveSlot == slot, optionConstraints(row, false))
    button.enabled = relationsAvailable
    button.toolTipText = relationDisabledReason ?: label[1]
    slotButtons[slot] = button
    addDefaultRadio(grid, slotDefaultGroup, 'slot', slot, storedSlot == slot, false, defaultConstraints(row, false))
    row++
}

// ---- group 3: the clipboard format (right column, with its own "default" header)

int rightRow = 0
rightGrid.add(sectionLabel('Paste as'), optionConstraints(rightRow, false))
JLabel rightDefaultHeader = new JLabel('default', SwingConstants.CENTER)
rightDefaultHeader.font = rightDefaultHeader.font.deriveFont(Font.BOLD)
rightDefaultHeader.toolTipText = defaultHeader.toolTipText
rightGrid.add(rightDefaultHeader, defaultConstraints(rightRow, false))
rightRow++
ButtonGroup formatGroup = new ButtonGroup()

boolean storedFormatIsClone = (storedFormat == 'clone' && cloneAvailable)
if (flavorHandlers.isEmpty()) {
    formatButtons.add(addOption(rightGrid, formatGroup, 'Default (as Ctrl+V would paste)', !storedFormatIsClone,
            optionConstraints(rightRow++, false)))
}
else {
    List<String> classNames = flavorHandlers.collect { it.getClass().simpleName }
    // The stored format wins when the clipboard offers it; otherwise fall back to the one
    // Freeplane itself would have picked.
    String wantedClassName = (!storedFormatIsClone && classNames.contains(storedFormat))
            ? storedFormat : defaultHandler?.getClass()?.simpleName
    boolean selectionTaken = storedFormatIsClone
    classNames.each { String className ->
        boolean isSelected = !selectionTaken && className == wantedClassName
        if (isSelected) {
            selectionTaken = true
        }
        formatButtons.add(addOption(rightGrid, formatGroup, TextUtils.getText(className), isSelected,
                optionConstraints(rightRow, false)))
        addDefaultRadio(rightGrid, formatDefaultGroup, 'format', className, storedFormat == className,
                vanillaPreferencesForFormat(className) != null, defaultConstraints(rightRow, false))
        rightRow++
    }
    if (!selectionTaken) {
        formatButtons[0].selected = true
    }
}

cloneButton = addOption(rightGrid, formatGroup, 'Clone of the copied nodes', storedFormatIsClone,
        optionConstraints(rightRow, false))
cloneButton.enabled = cloneAvailable
if (!cloneAvailable) {
    cloneButton.toolTipText = relationDisabledReason ?: 'These nodes cannot be cloned here.'
}
addDefaultRadio(rightGrid, formatDefaultGroup, 'format', 'clone', storedFormat == 'clone', false,
        defaultConstraints(rightRow, false))
rightRow++

// ---- group 4: folding, which qualifies the paste instead of choosing it

String storedFolding = storedDefaultFolding()
boolean storedUnfoldTarget = storedDefaultUnfoldTarget()

rightGrid.add(sectionLabel('Folding'), optionConstraints(rightRow++, true))

JRadioButton keepFoldingButton = addOption(rightGrid, foldingGroup, 'Pasted nodes as copied',
        storedFolding == 'keep', optionConstraints(rightRow, false))
addDefaultRadio(rightGrid, foldingDefaultGroup, 'folding', 'keep', storedFolding == 'keep', false,
        defaultConstraints(rightRow, false))
rightRow++
foldPastedButton = addOption(rightGrid, foldingGroup, 'Pasted nodes folded',
        storedFolding == 'folded', optionConstraints(rightRow, false))
addDefaultRadio(rightGrid, foldingDefaultGroup, 'folding', 'folded', storedFolding == 'folded', false,
        defaultConstraints(rightRow, false))
rightRow++
unfoldPastedButton = addOption(rightGrid, foldingGroup, 'Pasted nodes unfolded',
        storedFolding == 'unfolded', optionConstraints(rightRow, false))
addDefaultRadio(rightGrid, foldingDefaultGroup, 'folding', 'unfolded', storedFolding == 'unfolded', false,
        defaultConstraints(rightRow, false))
rightRow++

unfoldTargetButton = addOption(rightGrid, targetGroup, 'Unfold the target if it is folded',
        storedUnfoldTarget, optionConstraints(rightRow, false))
addDefaultRadio(rightGrid, targetDefaultGroup, 'target', 'true', storedUnfoldTarget, true,
        defaultConstraints(rightRow, false))
rightRow++
JRadioButton keepTargetFoldedButton = addOption(rightGrid, targetGroup, 'Leave the target folded',
        !storedUnfoldTarget, optionConstraints(rightRow, false))
addDefaultRadio(rightGrid, targetDefaultGroup, 'target', 'false', !storedUnfoldTarget, true,
        defaultConstraints(rightRow, false))
rightRow++

// These five qualify the paste rather than being it, so their key selects and waits.
[keepFoldingButton, foldPastedButton, unfoldPastedButton, unfoldTargetButton, keepTargetFoldedButton]
        .each { modifierIndexes.add(numberedButtons.indexOf(it)) }

// The companion script runs the same code with the dialog left unbuilt: same defaults,
// same fallbacks, no window. The flag is cleared right away so a later Ctrl+Shift+V opens
// the dialog normally.
if (silentRun) {
    performPaste()
    return
}

content.add(columns)
content.add(Box.createVerticalStrut(10))
content.add(new JSeparator())
content.add(Box.createVerticalStrut(6))

// The hint gets its own lines: on one line it would widen the whole dialog.
JLabel keyHint = new JLabel('<html>A number or letter runs the option at once — the Folding ones only set it<br>' +
        'Alt + that key makes it the default &nbsp;·&nbsp; Enter runs &nbsp;·&nbsp; Esc cancels</html>')
keyHint.alignmentX = Component.LEFT_ALIGNMENT
keyHint.font = keyHint.font.deriveFont((float) (keyHint.font.size2D - 1f))
keyHint.enabled = false
content.add(keyHint)
content.add(Box.createVerticalStrut(6))

JPanel buttonRow = new JPanel()
buttonRow.layout = new BoxLayout(buttonRow, BoxLayout.LINE_AXIS)
buttonRow.alignmentX = Component.LEFT_ALIGNMENT
JButton pasteButton = new JButton('Paste')
JButton cancelButton = new JButton('Cancel')
buttonRow.add(Box.createHorizontalGlue())
buttonRow.add(pasteButton)
buttonRow.add(Box.createHorizontalStrut(6))
buttonRow.add(cancelButton)
content.add(buttonRow)

dialog = new JDialog(UITools.getCurrentFrame(), 'Paste special', false)
dialog.name = DIALOG_NAME
dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
dialog.contentPane.layout = new BorderLayout()
dialog.contentPane.add(content, BorderLayout.CENTER)

Closure closeDialog = {
    // Before disposing: windowClosed is delivered through the event queue, so waiting for
    // it would leave the map blocked for the rest of the current dispatch.
    mapReleaser?.call()
    dialog.visible = false
    dialog.dispose()
}

Closure confirm = {
    closeDialog()
    performPaste()
}
pasteAction = confirm

pasteButton.addActionListener({ ActionEvent e -> confirm() } as ActionListener)
cancelButton.addActionListener({ ActionEvent e -> closeDialog() } as ActionListener)

[[positionField, atPositionButton], [siblingPositionField, atSiblingPositionButton]].each { pair ->
    JTextField field = (JTextField) pair[0]
    JRadioButton button = (JRadioButton) pair[1]
    field.addActionListener({ ActionEvent e -> confirm() } as ActionListener)
    // Typing into a field means that slot, whatever was selected before.
    field.document.addDocumentListener([
            insertUpdate : { DocumentEvent e -> if (button.enabled) button.selected = true },
            removeUpdate : { DocumentEvent e -> if (button.enabled) button.selected = true },
            changedUpdate: { DocumentEvent e -> }
    ] as DocumentListener)
    // Clicking the radio button only selects it, and hands the keyboard to the field.
    button.addActionListener({ ActionEvent e -> focusField(field) } as ActionListener)
}

// Clicking a "default" radio button stores the preference right away — it is a setting,
// not part of the paste, so the dialog stays open and nothing is pasted.
defaultRadios.each { Integer index, JRadioButton radio ->
    radio.addActionListener({ ActionEvent e -> storeDefault(index) } as ActionListener)
}

// The preview sits under the header, before the option columns.
previewTitle = sectionLabel('Preview')
previewHost = new JPanel(new BorderLayout())
JScrollPane previewScroll = new JScrollPane(previewHost)
previewScroll.alignmentX = Component.LEFT_ALIGNMENT
// Sized in rows of the real font, not in guessed pixels: five rows plus the border.
int previewRowHeight = previewScroll.getFontMetrics(previewScroll.font).height + 4
PREVIEW_HEIGHT = previewRowHeight * 5 + 8
previewScroll.preferredSize = new Dimension(560, PREVIEW_HEIGHT)
previewScroll.maximumSize = new Dimension(Integer.MAX_VALUE, PREVIEW_HEIGHT)
previewScroll.border = BorderFactory.createLineBorder(UIManager.getColor('controlShadow'))
JPanel previewBox = new JPanel()
previewBox.layout = new BoxLayout(previewBox, BoxLayout.PAGE_AXIS)
previewBox.alignmentX = Component.LEFT_ALIGNMENT
previewTitle.alignmentX = Component.LEFT_ALIGNMENT
previewBox.add(previewTitle)
previewBox.add(Box.createVerticalStrut(4))
previewBox.add(previewScroll)
content.add(previewBox, 2)
content.add(Box.createVerticalStrut(12), 3)

// Format and folding only mean something for a paste: a link or a connector creates no nodes.
Closure updateFormatEnablement = {
    String slot = selectedSlot()
    boolean pasting = !(slot != null && (slot.startsWith('link') || slot.startsWith('connector')))
    formatButtons.each { it.enabled = pasting }
    cloneButton.enabled = pasting && cloneAvailable
    modifierIndexes.each { numberedButtons[it].enabled = pasting }
    updatePreview()
}
ItemListener enablementListener = { ItemEvent e -> updateFormatEnablement() } as ItemListener
numberedButtons.each { it.addItemListener(enablementListener) }
updateFormatEnablement()

JComponent rootPane = dialog.rootPane
numberedButtons.eachWithIndex { AbstractButton button, int index ->
    String key = shortcutKeyAt(index)
    if (key == ' ') {
        return
    }
    int keyCode = keyCodeFor(key)
    String actionKey = 'pasteSpecialKey' + key
    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(keyCode, 0), actionKey)
    if (key.charAt(0).isDigit()) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0 + Integer.parseInt(key), 0), actionKey)
    }
    rootPane.actionMap.put(actionKey, new AbstractAction() {
        @Override
        void actionPerformed(ActionEvent e) {
            selectByIndex(index)
        }
    })

    if (defaultRadios[index] != null) {
        String defaultActionKey = 'pasteSpecialDefaultKey' + key
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(keyCode, InputEvent.ALT_DOWN_MASK), defaultActionKey)
        rootPane.actionMap.put(defaultActionKey, new AbstractAction() {
            @Override
            void actionPerformed(ActionEvent e) {
                storeDefault(index)
            }
        })
    }
}

rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), 'pasteSpecialConfirm')
rootPane.actionMap.put('pasteSpecialConfirm', new AbstractAction() {
    @Override
    void actionPerformed(ActionEvent e) {
        confirm()
    }
})

rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), 'pasteSpecialCancel')
rootPane.actionMap.put('pasteSpecialCancel', new AbstractAction() {
    @Override
    void actionPerformed(ActionEvent e) {
        closeDialog()
    }
})

// Esc inside a field only leaves the field, so the keys become option keys again.
[positionField, siblingPositionField].each { JTextField field ->
    field.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), 'pasteSpecialLeaveField')
    field.actionMap.put('pasteSpecialLeaveField', new AbstractAction() {
        @Override
        void actionPerformed(ActionEvent e) {
            pasteButton.requestFocusInWindow()
        }
    })
}

// While the dialog is up, the map stops reacting to the mouse. With the "direct" selection
// method — the Freeplane default — merely moving the pointer across the map on the way to
// this dialog selects whatever it passes over, which is both confusing and easy to mistake
// for the dialog having lost track of the node.
//
// A modal dialog would do this for free, but modal means setVisible() blocks the event
// thread until it closes, which hangs any script driving the dialog (the MCP host, and the
// whole test battery with it). The glass pane gives the same protection where it matters —
// the frame that holds the map — while the dialog stays an ordinary window.
JComponent glassPane = (JComponent) UITools.getCurrentFrame()?.glassPane
if (glassPane != null) {
    // Empty listeners are the point: attached to a visible glass pane they swallow every
    // mouse event before it reaches the map.
    java.awt.event.MouseAdapter swallow = new java.awt.event.MouseAdapter() {}
    glassPane.addMouseListener(swallow)
    glassPane.addMouseMotionListener(swallow)
    // Scrolling is harmless and useful — you may want to look around before pasting — so
    // the wheel is handed down to whatever sits under the pointer.
    java.awt.event.MouseWheelListener wheelForwarder = { java.awt.event.MouseWheelEvent event ->
        java.awt.Container contentPane = UITools.getCurrentFrame().contentPane
        java.awt.Point point = SwingUtilities.convertPoint(glassPane, event.point, contentPane)
        Component under = SwingUtilities.getDeepestComponentAt(contentPane, (int) point.@x, (int) point.@y)
        if (under != null) {
            under.dispatchEvent(SwingUtilities.convertMouseEvent(glassPane, event, under))
        }
    } as java.awt.event.MouseWheelListener
    glassPane.addMouseWheelListener(wheelForwarder)
    glassPane.visible = true

    mapReleaser = {
        glassPane.removeMouseListener(swallow)
        glassPane.removeMouseMotionListener(swallow)
        glassPane.removeMouseWheelListener(wheelForwarder)
        glassPane.visible = false
    }
    // The safety net for the ways out that never reach closeDialog(), such as the title
    // bar's close button. Releasing twice is harmless.
    dialog.addWindowListener(new java.awt.event.WindowAdapter() {
        @Override
        void windowClosed(java.awt.event.WindowEvent event) {
            mapReleaser?.call()
        }
    })
}

// Test handles: they survive recompilation because their static type comes from the JDK.
UIManager.put('pasteSpecial.confirm', { confirm() } as Runnable)
UIManager.put('pasteSpecial.cancel', { closeDialog() } as Runnable)

dialog.pack()
UITools.setDialogLocationRelativeTo(dialog, selected)
Rectangle screen = dialog.graphicsConfiguration.bounds
int x = Math.max((int) screen.@x, Math.min((int) dialog.location.@x, (int) (screen.@x + screen.@width - dialog.width)))
int y = Math.max((int) screen.@y, Math.min((int) dialog.location.@y, (int) (screen.@y + screen.@height - dialog.height)))
dialog.setLocation(x, y)
dialog.visible = true
pasteButton.requestFocusInWindow()
