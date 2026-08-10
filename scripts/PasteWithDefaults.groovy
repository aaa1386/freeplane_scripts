// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/2965
// Version: 1.0

/**
 * Paste with defaults — a plain paste that obeys the defaults chosen in PasteSpecial.groovy,
 * including the ones Freeplane itself cannot express.
 *
 * Freeplane stores only three things about pasting: whether new children go first or last,
 * and which of the HTML/plain-text formats to use. "Paste as sibling", "paste as parent",
 * "paste at position 3", "paste as a clone", "make a connector instead" — none of those can
 * be a default in vanilla Freeplane, because there is no preference to hold them. This
 * script is what makes them possible: it runs PasteSpecial in silent mode, which applies the
 * stored defaults and pastes without showing the dialog.
 *
 * Assign it to control V (Freeplane asks "Replace shortcut?" and the built-in paste stays
 * available in the Edit menu), and keep control shift V on PasteSpecial for the dialog.
 *
 * It never leaves you without a paste: if PasteSpecial cannot be found or throws, this falls
 * back to Freeplane's own paste, which is what control V would have done anyway.
 *
 * The defaults live in the profile, next to Freeplane's own preferences:
 *
 *     pasteSpecial.defaultSlot      where the nodes land, or a link/connector relation
 *     pasteSpecial.defaultPosition  the number, for the two "at position" slots
 *     pasteSpecial.defaultFormat    the clipboard format, or "clone"
 *
 * A default that does not fit the situation is skipped rather than obeyed — pasting as a
 * sibling of the root node, or a connector with nothing copied from a map, falls back to a
 * normal paste. That check lives in PasteSpecial, so both entry points behave alike.
 */

import javax.swing.UIManager

import org.freeplane.core.resources.ResourceController
import org.freeplane.core.util.ConfigurationUtils
import org.freeplane.core.util.FileUtils
import org.freeplane.core.util.LogUtils
import org.freeplane.features.map.MapController
import org.freeplane.features.map.NodeModel
import org.freeplane.features.map.NodeModel.Side
import org.freeplane.features.map.clipboard.MapClipboardController
import org.freeplane.features.map.mindmapmode.clipboard.MMapClipboardController
import org.freeplane.features.mode.Controller
import org.freeplane.plugin.script.ScriptResources
import org.freeplane.plugin.script.ScriptingEngine

NodeModel selected = Controller.currentController.selection?.selected
if (selected == null) {
    return
}

ResourceController resourceController = ResourceController.getResourceController()
// Found by name in the script folders, so the pair works on any machine without a hardcoded
// path. Relative entries resolve against the user directory, and the profile's own scripts
// folder is searched too: Freeplane always reads it, and it is not in the preference.
List<File> scriptDirs = ConfigurationUtils
        .decodeListValue(resourceController.getProperty('script_directories') ?: '', false)
        .findAll { it }
        .collect { FileUtils.getAbsoluteFile(resourceController.freeplaneUserDirectory, it) }
scriptDirs << ScriptResources.getUserScriptsDir()

File pasteSpecial = scriptDirs.collect { new File(it, 'PasteSpecial.groovy') }.find { it.isFile() }

boolean pasted = false
if (pasteSpecial != null) {
    UIManager.put('pasteSpecial.silent', Boolean.TRUE)
    try {
        ScriptingEngine.executeScript(selected, pasteSpecial, null)
        pasted = true
    }
    catch (Throwable error) {
        LogUtils.warn('PasteWithDefaults: PasteSpecial failed, falling back to the built-in paste', error)
    }
    finally {
        UIManager.put('pasteSpecial.silent', null)
    }
}
else {
    LogUtils.warn('PasteWithDefaults: PasteSpecial.groovy is not in any configured script directory')
}

if (!pasted) {
    MMapClipboardController clipboardController = (MMapClipboardController) MapClipboardController.getController()
    def clipboardContents = clipboardController.getClipboardContents()
    if (clipboardContents != null) {
        clipboardController.paste(clipboardContents, selected, MapController.suggestNewChildSide(selected, Side.DEFAULT))
    }
}
