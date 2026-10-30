# Chip and peripheral parameters

In Configuration Studio, choose a peripheral tab such as ADC, then **Parameters & pins**. The Hardware workspace opens the matching controller from the selected build. Select a controller or a channel in the hierarchy to inspect its parameters.

The **Chip & parameters** page displays a logical SoC schematic alongside exact generated-DTS property values. ADC channel properties include gain, reference, resolution and acquisition time when present in that build. Pinctrl states are followed through numeric phandles to their pinmux/psels/pins properties. GPIO specifiers and unknown vendor encodings remain raw; the view does not guess how they map to package pads.

Click a schematic route to select its parameter row. **Show declaration** reveals the exact generated source line. Double-click a parameter, or use **Prepare overlay request**, to enter a Devicetree value and review an overlay fragment for copying. No source or generated files are changed. Add the reviewed draft to the application's active overlay and rebuild to validate it against the device binding. The displayed built values remain unchanged until that build is refreshed.

## Physical package boundary

The schematic positions are not physical pad locations. Devicetree GPIO numbers, ADC inputs, pinmux encodings and physical package-pad numbers are different identifiers. A package-accurate pinout requires a verified map for the exact MCU part and package. This change does not supply or invent that mapping, possible pin alternatives, electrical limits or pin-conflict validation. Missing pin data remains visibly unavailable.

## Validation

Core tests cover per-channel ADC properties, multiline pinctrl values, boolean properties, comment handling, source lines, exact overlay targets and missing routes. The installed-Eclipse test opens ADC parameters, checks the built gain value and pin route, and reveals the parameter source line. These checks do not replace board/package verification.
