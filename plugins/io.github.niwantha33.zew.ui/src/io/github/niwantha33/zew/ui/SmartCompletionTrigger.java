// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Small, dependency-free policy for first-identifier-character completion.
 * CDT remains responsible for computing and inserting all actual proposals.
 */
final class SmartCompletionTrigger {
    private SmartCompletionTrigger() {}

    // CDT ContentAssistPreference keys; CDT preference values are shared by
    // all native C/C++ editors in the same Workbench installation.
    static final String CDT_AUTO_INSERT = "content_assist_autoinsert";
    static final String CDT_COMMON_PREFIX_INSERT = "content_assist_prefix_completion";

    /**
     * Repair saved CDT insertion settings, not only their defaults. An older
     * workspace may explicitly enable automatic insertion, so opening the
     * proposal popup can turn "void" into "volatile" without user approval.
     *
     * This is intentionally run once during this Workbench's startup. CDT
     * receives preference-change events and updates live content assistants.
     * If the user enables these settings later, automatic first-letter
     * suggestions fail closed via safeForAutomaticInvocation().
     */
    static void enforceSuggestionOnly(Predicate<String> isEnabled, Consumer<String> disable) {
        if (isEnabled.test(CDT_AUTO_INSERT)) disable.accept(CDT_AUTO_INSERT);
        if (isEnabled.test(CDT_COMMON_PREFIX_INSERT)) disable.accept(CDT_COMMON_PREFIX_INSERT);
    }

    static boolean shouldOpen(char typed, char inserted, char previous) {
        return isIdentifierStart(typed) && typed == inserted
                && !isIdentifierPart(previous)
                // CDT already owns member-access auto-activation.
                && previous != '.' && previous != '>' && previous != ':';
    }

    /**
     * These CDT options can change source simply by showing a proposal popup.
     * Never automatically invoke the editor assistant while either is enabled.
     */
    static boolean safeForAutomaticInvocation(boolean insertSingle, boolean insertCommonPrefix) {
        return !insertSingle && !insertCommonPrefix;
    }

    static boolean isCurrentIdentifier(String fragment) {
        if (fragment == null || fragment.isEmpty()) return false;
        if (!isIdentifierStart(fragment.charAt(0))) return false;
        for (int i = 1; i < fragment.length(); i++)
            if (!isIdentifierPart(fragment.charAt(i))) return false;
        return true;
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z';
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || value >= '0' && value <= '9';
    }
}
