// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

/**
 * Small, dependency-free policy for first-identifier-character completion.
 * CDT remains responsible for computing and inserting all actual proposals.
 */
final class SmartCompletionTrigger {
    private SmartCompletionTrigger() {}

    static boolean shouldOpen(char typed, char inserted, char previous) {
        return isIdentifierStart(typed) && typed == inserted
                && !isIdentifierPart(previous)
                // CDT already owns member-access auto-activation.
                && previous != '.' && previous != '>' && previous != ':';
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
