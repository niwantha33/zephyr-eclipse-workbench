// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.core.expressions.PropertyTester;

/** Only imported Zephyr resources participate in contextual Debug As. */
public final class ZephyrProjectTester extends PropertyTester {
    @Override public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        boolean imported = "isZephyrProject".equals(property)
                ? ZephyrDebugShortcut.project(receiver) != null || ZephyrProjectLifecycle.supports(SelectedProject.fromObject(receiver))
                : "isImportedProject".equals(property) && ZephyrDebugShortcut.project(receiver) != null;
        return expectedValue == null ? imported : imported == Boolean.parseBoolean(expectedValue.toString());
    }
}
