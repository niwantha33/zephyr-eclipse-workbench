// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.QuickHelpCatalog;
import io.github.niwantha33.zew.core.QuickHelpExplanationProvider;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Zero-network, plain JDK 21 regression tests, run by scripts/test_core.py. */
public final class QuickHelpCatalogTests {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static String first(QuickHelpCatalog docs, String phrase) {
        var hits = docs.search(phrase, 10);
        check(!hits.isEmpty(), "No hit for " + phrase);
        return hits.get(0).entry().id();
    }
    public static void main(String[] args) throws Exception {
        QuickHelpCatalog docs = QuickHelpCatalog.load();
        check(docs.entries().size() == 6, "Six topic records loaded");
        check(docs.entries().stream().map(QuickHelpCatalog.Entry::category).collect(Collectors.toSet())
                .equals(Set.of("Threads", "GPIO", "Timers", "Semaphores", "Mutexes", "Message queues")),
                "All six categories covered");
        check(first(docs, "create_task").equals("k_thread_create"), "Intent alias");
        check(first(docs, "create a thread").equals("k_thread_create"), "Natural language alias");
        check(first(docs, "k_thread_create").equals("k_thread_create"), "Exact API");
        check(first(docs, "k_thread_cr").equals("k_thread_create"), "API prefix");
        check(first(docs, "creat_task").equals("k_thread_create"), "Single-letter typo");
        check(first(docs, "create thred").equals("k_thread_create"), "Multi-word typo");
        check(first(docs, "blink LED").equals("gpio_pin_toggle_dt"), "GPIO intent");
        check(first(docs, "wait for semaphore").equals("k_sem_take"), "Semaphore intent");
        check(first(docs, "mutex wait").equals("k_mutex_lock"), "Mutex intent");
        check(first(docs, "timer tick").equals("k_timer_start"), "Timer intent");
        check(first(docs, "enqueue data").equals("k_msgq_put"), "Message queue intent");
        check(first(docs, "sem").equals("k_sem_take"), "Short partial word");
        check(first(docs, "led").equals("gpio_pin_toggle_dt"), "Partial intent");
        check(first(docs, "gipo").equals("gpio_pin_toggle_dt"), "Transposed typo");
        check(docs.search("create_task", 5).get(0).intention(), "Intention clearly not API");
        check(!docs.search("k_thread_create", 5).get(0).intention(), "Exact API identified");
        check(docs.search("creat_task", 5).get(0).spellingSuggestion(), "Typo marked");
        check(docs.search("", 10).isEmpty(), "Empty input should return no results");
        check(docs.search("   ", 10).isEmpty(), "Whitespace input should return no results");
        check(docs.search("purple spaceship toaster", 10).isEmpty(), "Unknown intent handled");
        check(docs.search("k_thread_create", 0).isEmpty(), "Zero limit handled");
        check(docs.search("k_thread_create", 1).size() == 1, "Limit honoured");
        for (QuickHelpCatalog.Entry e : docs.entries()) {
            check(e.version().equals("4.4.0"), "Pinned upstream version: " + e.id());
            check(e.source().startsWith("https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/"),
                    "Official attribution: " + e.id());
            check(e.signature().contains(e.id()), "Signature present: " + e.id());
            check(e.example().contains("#include <zephyr/") && e.example().contains("int main(void)"),
                    "Standalone sample and headers: " + e.id());
            check(!e.parameters().isBlank() && !e.requirements().isBlank() && !e.mistakes().isBlank()
                    && !e.related().isEmpty(), "Complete metadata: " + e.id());
            check(QuickHelpCatalog.versionState(e, "4.4.0") == QuickHelpCatalog.VersionState.MATCH,
                    "Exact version matches");
            check(QuickHelpCatalog.versionState(e, "4.5.0") == QuickHelpCatalog.VersionState.MISMATCH,
                    "Newer version is mismatch, not automatically supported");
            check(QuickHelpCatalog.versionState(e, "") == QuickHelpCatalog.VersionState.UNKNOWN,
                    "Missing version is unknown");
            check(QuickHelpCatalog.versionState(e, "4.4.99") == QuickHelpCatalog.VersionState.MISMATCH,
                    "Unverified checkout is not a match");
            check(QuickHelpCatalog.versionState(e, null) == QuickHelpCatalog.VersionState.UNKNOWN,
                    "Null version is unknown");
        }
        boolean missingEvidenceRejected = false;
        try {
            new QuickHelpExplanationProvider.Request("Explain", "4.4.0", List.of(), true);
        } catch (IllegalArgumentException expected) { missingEvidenceRejected = true; }
        check(missingEvidenceRejected, "AI interface rejects missing upstream evidence");
        boolean noOptInRejected = false;
        try {
            new QuickHelpExplanationProvider.Request("Explain", "4.4.0", docs.entries(), false);
        } catch (IllegalArgumentException expected) { noOptInRejected = true; }
        check(noOptInRejected, "AI interface rejects external transfer without opt-in");
        System.out.println("QuickHelpCatalogTests: " + checks + " checks passed");
    }
}
