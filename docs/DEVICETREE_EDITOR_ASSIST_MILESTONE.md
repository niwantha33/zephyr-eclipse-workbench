# Native Devicetree content assist (first integration milestone)

This branch adds an Eclipse text editor for `.overlay`, `.dts`, `.dtsi` using JFace text and a bounded off-UI-thread index from the *selected* checkout's dtlib/edtlib. Normal double-click association is registered via org.eclipse.ui.editors.

Open an application overlay in Project Explorer and use Ctrl+Space. On an empty file the editor offers five small starting actions; after `&`, complete actual node labels from the configured build. Within a resolved `&label { }` node, complete properties from upstream merged bindings; enum and status values are available while typing assignments. Help describes source and evidence limitations. Controllers without a built DTS are never invented. Only the selected Zephyr checkout and application binding root are indexed. Source document edits are read at each completion request.

## Evidence boundaries

The index is a generated snapshot, *not* live hardware. New source edits, overlay selection and parent include preprocessing are not applied to the build snapshot. A missing, mismatched or unparseable build yields only safe syntax/starter assistance. Binding data for modules not in these two roots is not asserted. Do not equate status=okay with driver inclusion or runtime use.

## Not implemented in this milestone

Hover hyperlinks, F3 declaration navigation, linked placeholders, diagnostic markers, phandle-array cell assistance, pinctrl symbol insertion, complete child-node templates, include completion, built-source freshness by per-file hashes, and native Eclipse runtime smoke verification remain work for subsequent reviewed changes. The feature must not be represented as complete against the full attached specification.

## Manual QA

1. Open an imported Zephyr project, choose an actual build profile, configure/build.
2. Double-click app.overlay in Project Explorer, verify the editor title is Zephyr Devicetree Editor.
3. Ctrl+Space on an empty file: choose Configure an existing controller.
4. Ctrl+Space after `&`: verify labels match *this* board and include disabled nodes.
5. Enter `&known_label {\n    ` and complete actual properties. Enter `status = ` and Ctrl+Space.
6. Change the board to a different checkout/build; close/reopen the editor and verify no prior labels are offered.
7. Make an unsaved change and repeat completion, confirming the new cursor context is used.
8. Build/validate the resulting overlay with the workbench's existing Zephyr build/evidence workflow; content assist is not validation.

Source references: Zephyr Devicetree binding syntax and scripts/dts/python-devicetree from the selected repository checkout.
