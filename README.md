# freeplane_scripts

A collection of Groovy scripts for [Freeplane](https://www.freeplane.org/), by euu2021.

For more collections of scripts and add-on, see this page: [scripts collections](https://docs.freeplane.org/scripting/Finding_useful_scripts.html).

## How to use

See [Start here page](https://docs.freeplane.org/scripting/Start_Here.html).

Each file's header links to the forum thread it came from, if any.

## Versions and changelog

Scripts are versioned one by one, not as a collection: you install a single `.groovy` file, so
everything you need to know about it lives in that file's header.

```groovy
// Copyright (C) 2026  euu2021 (Github)
// SPDX-License-Identifier: GPL-2.0-or-later
// Discussion thread: https://github.com/freeplane/freeplane/discussions/1234
// Version: 1.2
```

**Is my copy outdated?** Compare the `Version:` line of your file with the one here. If they
differ, the script's `CHANGELOG` block (at the end of its documentation comment) lists what
changed between the two, newest first:

```
 CHANGELOG
 ---------
   1.1 (2026-07-26)
       What changed, and what you have to do about it, if anything.
   1.0 (2026-07-21)
       First public version.
```

What the numbers mean:

| Change | Meaning |
|---|---|
| 1.**2** → 1.**3** | Anything you do not have to act on: a new feature, an option, a fix. Replacing the file is enough. |
| **1**.3 → **2**.0 | You have to do something: reassign a shortcut, redo a setting, delete a state file, or upgrade Freeplane. Such entries are also marked `(!)` in the changelog. |

Versioning starts on 2026-07-26, with every script at `1.0`; earlier history is in the
repository log. A script only grows a `CHANGELOG` block once it changes after that date, so
scripts that never needed a fix stay as short as they always were.

## Tags

Scripts can belong to more than one category, so they are tagged. Ctrl-F a tag to find everything in that category.

- `navigation` — move the selection / jump around the map
- `selection` — select nodes
- `tags` — work with node tags
- `styles` — styles, colors, edges, themes
- `filter/search` — filtering and live search
- `panel/GUI` — floating panels, dialogs, sliders
- `maps` — multiple maps, view-roots, links between maps
- `editing` — create, duplicate or modify nodes
- `fold/minimize` — folding and minimizing nodes
- `clipboard` — clipboard operations
- `listener` — runs continuously, reacting to events
- `init` — runs once at startup
- `view` — zoom, layout, viewport
- `connectors` — works with connectors
- `sound` — audio feedback
- `experimental` — prototype / proof of concept

## Scripts

⭐ marks the main ones: scripts that add substantial features Freeplane does not have out of the box.

Sibling scripts (directional variants, script pairs) share a single row.

| | Script | What it does | Tags |
|:-:|---|---|---|
| | [applyCustomEdgeColors](scripts/applyCustomEdgeColors.groovy) | Applies the user-defined edge colors to the selected nodes and their whole branch, cycling through colors. | `styles` |
| ⭐ | [autoMinimizerListener](scripts/autoMinimizerListener.groovy) | Listener that keeps the map compact: any node longer than the shortened-text length is shown minimized, no matter how it appeared — typed, pasted, imported, created by a script or by the AI, or already in a map you open. | `listener` `fold/minimize` `editing` |
| ⭐ | [autoRunScripts](scripts/autoRunScripts.groovy) | Window to pick which scripts Freeplane runs by itself and when: at startup, when a map opens or closes, when a tab is created or selected, every N minutes, or on the way out. Keeps a history of what ran, with durations. | `init` `listener` `panel/GUI` |
| | [autoScrollModes](scripts/autoScrollModes.groovy) | Selection listeners that keep the current node positioned as you navigate (auto-scroll modes). | `navigation` `view` `listener` |
| | [conditionalStyleByFilter](scripts/conditionalStyleByFilter.groovy) | Adds a conditional style to the nodes matched by a script filter. | `styles` `filter/search` |
| | [connectedNodesNavigator](scripts/connectedNodesNavigator.groovy) | Popup listing every node connected to the selected one (with direction), for quick jumping. | `navigation` `connectors` `panel/GUI` |
| | [ConnectedCluster](scripts/ConnectedCluster.groovy) | Selects the node plus every node linked to it by connectors. | `selection` `connectors` |
| | [copyNodeIdWithHash](scripts/copyNodeIdWithHash.groovy) | Copies the selected node's ID to the clipboard with `#` prepended, ready to paste as an internal hyperlink. | `clipboard` `maps` |
| | [CrossMapLinks](scripts/CrossMapLinks.groovy) | Panel showing the current map's cross-map links: OUT (this map links to), IN (backlinks / what links here), and Friends (connected maps you can browse in place, without opening them). Scans the active map's folder. | `maps` `navigation` `panel/GUI` |
| | [duplicateNode](scripts/duplicateNode.groovy) | Duplicates the selected node(s), placing each copy right below its original. | `editing` |
| ⭐ | [FoldedNodeCount](scripts/FoldedNodeCount.groovy) | Draws, in the folding mark of a folded node, how many nodes are hidden inside that branch, coloured by order of magnitude (white / yellow / orange / red). Lets the size of a folded branch be read without unfolding it. Run again to switch off. | `fold/minimize` `view` |
| | [foldSiblingsOfSelected](scripts/foldSiblingsOfSelected.groovy) | Navigation mode that folds the siblings of the selected node to keep focus on the current branch. | `fold/minimize` `navigation` `listener` |
| | [initScriptsTool](scripts/initScriptsTool.groovy) · [Bridge](scripts/initScriptsToolBridge.groovy) | Runs every `//init` script found across your script folders and reports which ran; the *Bridge* is a small file you drop in your profile's `scripts/init/` folder so the tool runs at startup. | `init` |
| | [invertMapColors](scripts/invertMapColors.groovy) | Toggles the whole map between light and dark themes (styles + background). | `styles` |
| | [jfaceTreeViewer](scripts/jfaceTreeViewer.groovy) | Prototype: shows the map as a tree with attribute columns (JXTreeTable) over the map. | `panel/GUI` `experimental` |
| | [justifyTopicAlignment](scripts/justifyTopicAlignment.groovy) | Aligns each level automatically, XMind-style. | `view` `listener` |
| | [leftRightTraversal](scripts/leftRightTraversal.groovy) | Tweaks Left/Right arrow-key behavior when traversing nodes. | `navigation` `selection` |
| | [LiveFilterPanel](scripts/LiveFilterPanel.groovy) | Search panel that, on every keystroke, hides everything except the matches and their ancestor path (live filter over the whole map). | `filter/search` `panel/GUI` |
| | [LoremIpsumGenerator](scripts/LoremIpsumGenerator.groovy) | Fills the selected node with a random lorem ipsum subtree of a given size (1000 nodes by default): 1..10 children per node, mostly short texts with a thin long tail. For building large maps to test with. | `editing` |
| ⭐ | [MinimizedNodeTooltipOnSelection](scripts/MinimizedNodeTooltipOnSelection.groovy) | Shows a minimized (shortened) node's full content as a tooltip when the selection reaches it — the same tooltip the mouse gets on hover, without the mouse. It follows the selection whoever moves it: arrow keys, a bookmark, a panel, another script. Selecting by clicking is left alone, since hovering already shows it there. It goes away on the next selection, on a click, on wheel scrolling or when the window loses focus. Running the script again switches it off. | `fold/minimize` `navigation` `listener` |
| | navigationLimitedToSiblings [Down](scripts/navigationLimitedToSiblingsDown.groovy) · [UP](scripts/navigationLimitedToSiblingsUP.groovy) | Moves the selection up/down, but only among siblings (won't leave the level). | `navigation` `selection` |
| | [noteFontSizeSlider](scripts/noteFontSizeSlider.groovy) | Slider dialog to quickly adjust the Note style's font size. | `styles` `panel/GUI` |
| | [openLinkedMapAndClose](scripts/openLinkedMapAndClose.groovy) | Opens the map linked from the selected node and closes the current one (backlink navigation without piling up open maps). | `maps` `navigation` |
| | [OutlineLiveFilter](scripts/OutlineLiveFilter.groovy) | Type-to-filter field docked in the Outline panel; filters the outline only, leaving the map untouched. | `filter/search` `panel/GUI` |
| | [paste_and_minimize](scripts/paste_and_minimize.groovy) | Pastes, then auto-minimizes the just-pasted nodes that exceed the shortened-text length. Superseded by autoMinimizerListener, which covers every way a node can appear. | `clipboard` `fold/minimize` `editing` |
| ⭐ | [PasteSpecial](scripts/PasteSpecial.groovy) · [PasteWithDefaults](scripts/PasteWithDefaults.groovy) | One dialog that combines the clipboard formats of *Edit → Paste as* with control over **where** the pasted nodes land: first or last child, a numbered position, before or after the selected node, or as its parent — plus link and connector, which use the clipboard without pasting. Every option runs from a single keystroke, and any of them can become what a plain paste does, which is what *PasteWithDefaults* applies. | `clipboard` `editing` `panel/GUI` `connectors` |
| ⭐ | recentRootsNavigator [script1](scripts/recentRootsNavigator_script1.groovy) · [script2](scripts/recentRootsNavigator_script2.groovy) | The Recent Roots Navigator — a tabless jump-in workflow to move between recently used view-roots (main panel + its launcher shortcut). | `navigation` `maps` `panel/GUI` |
| | [removeLastTag](scripts/removeLastTag.groovy) | Removes the last tag from the selected nodes. | `tags` `editing` |
| | [resizeMapOverview](scripts/resizeMapOverview.groovy) | Resizes the Map Overview panel (the bird's-eye overview box) to any width/height you set. | `view` `panel/GUI` |
| ⭐ | [safeDeleteListener](scripts/safeDeleteListener.groovy) | Listener that guards against accidental deletion: warns before a large branch goes, or before any node carrying a protected tag. Start it automatically. | `listener` `init` `editing` |
| | [saveEventAutosave](scripts/saveEventAutosave.groovy) | Enables autosave and sets up a timer around the save event. | `listener` |
| | select [DOWN](scripts/selectDOWN.groovy) · [UP](scripts/selectUP.groovy) · [LEFT](scripts/selectLEFT.groovy) · [RIGHT](scripts/selectRIGHT.groovy) | Free-selects the node in the given direction and re-centers the view. | `navigation` `selection` |
| | [selectLevel1Children](scripts/selectLevel1Children.groovy) | Selects the visible first-level children of the selected nodes. | `selection` |
| | [selectNodesByCloud](scripts/selectNodesByCloud.groovy) | Selects nodes by clicking on the clouds drawn around them. | `selection` |
| | selectionLimitedToSiblings [Down](scripts/selectionLimitedToSiblingsDown.groovy) · [Up](scripts/selectionLimitedToSiblingsUp.groovy) | Extends the selection up/down, but only among siblings. | `selection` `navigation` |
| | [setCreationDateToChildren](scripts/setCreationDateToChildren.groovy) | Sets a creation date on the selected nodes' children via a dialog. | `editing` |
| | [simpleTagCreator](scripts/simpleTagCreator.groovy) | Creates a new tag and adds it to the selected node. | `tags` `editing` |
| ⭐ | [SoundEffects](scripts/SoundEffects.groovy) | Plays a short sound when a node is selected, created, moved or deleted, and when a branch is folded or unfolded. Each action has its own switch, and an action switched off is never installed. The sounds are synthesized in memory, so no audio file is needed — three themes are available (wooden chess pieces, dungeon, plain bleeps), and your own `.wav` files replace them if you prefer. Running the script again switches the sounds off. | `sound` `listener` |
| | [TagCategoryDialogFilter](scripts/TagCategoryDialogFilter.groovy) | Docks a live filter field in the "Manage tag categories" dialog; each keystroke filters the tag tree and auto-expands it, so matches inside collapsed branches show up. | `tags` `filter/search` `panel/GUI` |
| | [TagClickLocator](scripts/TagClickLocator.groovy) | Click a tag on a node in the map and it is located in the Tag Tree: the branches on its path expand and it is selected and scrolled into view (clears an active tag filter first). | `tags` `navigation` `panel/GUI` |
| | [TagFilterAutoExpand](scripts/TagFilterAutoExpand.groovy) | Makes the Tag panel's native filter auto-expand, so matching tags inside collapsed branches become visible (they stay hidden otherwise). Works as a menu or startup (init) script. | `tags` `filter/search` `init` |
| | [tagsImportFormat](scripts/tagsImportFormat.groovy) | Imports / normalizes a tag format into the node. | `tags` |
| | [tagsWithFormulaListener](scripts/tagsWithFormulaListener.groovy) | Listener that generates a set of tags from a formula. | `tags` `listener` |
| | [TagTreeSingleClickAssign](scripts/TagTreeSingleClickAssign.groovy) | Assign a tag to the selected node with a single left-click in the Tag Tree, instead of the default double-click. | `tags` `editing` |
| ⭐ | [UndoStackViewer](scripts/UndoStackViewer.groovy) | Window listing what the last actions on the map actually did — which node was deleted and from where, what text changed (showing the part that changed, not two truncated strings), what moved, plus icons, tags, links, attributes and style. Deletions in red, additions in green, and the clock time where Freeplane records one. Walking the rows selects the node involved, so an accidental change can be found without pressing Ctrl+Z to see what it undoes. It follows the map, refreshing on every action, undo and redo. *Undo N actions* rewinds to the selected row in one step, Ctrl+Z and Ctrl+Y work in the window, and folding/view noise is hidden by default without renumbering the steps. | `panel/GUI` `editing` `view` |
| ⭐ | [UnifiedTagPanel](scripts/UnifiedTagPanel.groovy) | One overlay panel that merges Freeplane's three tag interfaces: the Tags side panel, the "Edit node tags" dialog and the "Manage tag categories" dialog. Search in two modes — hide what does not match, or keep the whole tree in place and highlight the typed text where it occurs (accent-insensitive either way). Assign with a click or with Enter, which creates the tag, and its whole `a::b` category path, when nothing matches. A usage count on every tag with the unused ones faded, plus *hide unused*, *sort by usage* and *delete all unused*. Rename, nest, reorder, colour and delete the hierarchy — each one undo step, with the tags on the nodes rewritten along. Favourites pinned per map, stored inside the `.mm`. And *filter map by this tag*, which puts the folding back when cleared. | `tags` `panel/GUI` `filter/search` `editing` |
| ⭐ | [UtilityPanels](https://github.com/euu2021/Freeplane_UtilityPanels) ↗ | Collection of utility panels built into Freeplane's UI: quick search (with lines to results), recent nodes, breadcrumbs, in-place siblings preview, inspector tooltips, and more. Hosted in its own repository. | `panel/GUI` `filter/search` `navigation` |
| ⭐ | [VisualEffects](scripts/VisualEffects.groovy) | Draws a short animation where a map action happened — select, create, move, delete, fold and unfold — each with several styles chosen in the config block. The visual companion to *SoundEffects*: painted on a transparent panel over the map view that reserves no space and does not intercept clicks, so nothing about the map behaviour changes. Running the script again switches it off. | `view` `listener` `panel/GUI` |
| | [zoomTo100](scripts/zoomTo100.groovy) | Sets the map zoom to 100%. | `view` |
