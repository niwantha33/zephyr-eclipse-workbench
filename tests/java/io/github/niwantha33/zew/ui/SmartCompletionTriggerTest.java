// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

/** Standalone JDK test for the first-letter trigger (no Eclipse installation required). */
public final class SmartCompletionTriggerTest {
    private static void check(boolean result, String scenario) {
        if (!result) throw new AssertionError(scenario);
        System.out.println("PASS completion: " + scenario);
    }

    public static void main(String[] args) {
        // An existing Eclipse workspace can store 'true' even when our new
        // default is false. Repair all four combinations without gratuitously
        // rewriting already-safe settings.
        for (boolean single : new boolean[] { false, true }) {
            for (boolean prefix : new boolean[] { false, true }) {
                var settings = new java.util.HashMap<String, Boolean>();
                settings.put(SmartCompletionTrigger.CDT_AUTO_INSERT, single);
                settings.put(SmartCompletionTrigger.CDT_COMMON_PREFIX_INSERT, prefix);
                final int[] writes = { 0 };
                java.util.function.Consumer<String> disable = key -> {
                    settings.put(key, false);
                    writes[0]++;
                };
                SmartCompletionTrigger.enforceSuggestionOnly(
                        key -> settings.getOrDefault(key, false), disable);
                check(!settings.get(SmartCompletionTrigger.CDT_AUTO_INSERT)
                                && !settings.get(SmartCompletionTrigger.CDT_COMMON_PREFIX_INSERT)
                                && writes[0] == (single ? 1 : 0) + (prefix ? 1 : 0),
                        "saved CDT settings repaired (single=" + single + ", prefix=" + prefix + ")");
                SmartCompletionTrigger.enforceSuggestionOnly(
                        key -> settings.getOrDefault(key, false), disable);
                check(writes[0] == (single ? 1 : 0) + (prefix ? 1 : 0),
                        "suggestion-only repair is idempotent");
            }
        }

        check(SmartCompletionTrigger.safeForAutomaticInvocation(false, false),
                "automatic suggestions are safe when CDT inserts nothing without acceptance");
        check(!SmartCompletionTrigger.safeForAutomaticInvocation(true, false),
                "single CDT proposal cannot silently turn void into volatile");
        check(!SmartCompletionTrigger.safeForAutomaticInvocation(false, true),
                "common-prefix insertion cannot rewrite a partially typed keyword");
        check(!SmartCompletionTrigger.safeForAutomaticInvocation(true, true),
                "both unsafe CDT insertion options suppress first-letter popup");
        check(SmartCompletionTrigger.shouldOpen('v', 'v', ' '),
                "keyword starts remain eligible after safe CDT preferences");
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
