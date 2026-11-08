// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

/** Standalone JDK test for the first-letter trigger (no Eclipse installation required). */
public final class SmartCompletionTriggerTest {
    private static void check(boolean result, String scenario) {
        if (!result) throw new AssertionError(scenario);
        System.out.println("PASS completion: " + scenario);
    }

    public static void main(String[] args) {
        check(SmartCompletionTrigger.shouldOpen('g', 'g', ' '), "first GPIO letter");
        check(SmartCompletionTrigger.shouldOpen('D', 'D', '#'), "macro after preprocessor marker");
        check(SmartCompletionTrigger.shouldOpen('_', '_', '\0'), "underscore at file start");
        check(!SmartCompletionTrigger.shouldOpen('p', 'p', 'g'), "continued prefix does not reopen");
        check(!SmartCompletionTrigger.shouldOpen('i', 'i', '.'), "CDT owns member completion after dot");
        check(!SmartCompletionTrigger.shouldOpen('m', 'm', '>'), "CDT owns pointer member completion");
        check(!SmartCompletionTrigger.shouldOpen('N', 'N', ':'), "CDT owns scope completion");
        check(!SmartCompletionTrigger.shouldOpen('g', 'x', ' '), "ignore shortcut without inserted letter");
        check(!SmartCompletionTrigger.shouldOpen('1', '1', ' '), "numbers do not trigger");
        check(SmartCompletionTrigger.isCurrentIdentifier("gpio_pin_set_dt"), "identifier prefix remains valid");
        check(SmartCompletionTrigger.isCurrentIdentifier("CONFIG_DEBUG"), "macro prefix remains valid");
        check(!SmartCompletionTrigger.isCurrentIdentifier("gpio_ set"), "cancel when whitespace typed");
        check(!SmartCompletionTrigger.isCurrentIdentifier("gpio()"), "cancel when punctuation typed");
        check(!SmartCompletionTrigger.isCurrentIdentifier(""), "cancel empty token");
    }
}
