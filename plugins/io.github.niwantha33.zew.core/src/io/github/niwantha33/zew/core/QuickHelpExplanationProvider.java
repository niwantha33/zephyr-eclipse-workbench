// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;

/**
 * Future opt-in integration seam. Nothing implements, registers, invokes, or
 * exposes this interface in the offline release. Retrieval and citations are
 * required inputs, and generated commentary is distinct from official facts.
 */
public interface QuickHelpExplanationProvider {
    record Request(String question, String zephyrVersion, List<QuickHelpCatalog.Entry> retrievedEvidence,
            boolean projectContentSharingApproved) {
        public Request {
            retrievedEvidence = List.copyOf(retrievedEvidence);
            if (retrievedEvidence.isEmpty())
                throw new IllegalArgumentException("Retrieve official evidence before asking an AI provider");
            if (!projectContentSharingApproved)
                throw new IllegalArgumentException("An external AI request requires explicit user opt-in");
        }
    }
    record Explanation(String generatedText, List<String> citedSourceUrls,
            String missingEvidence) {
        public Explanation { citedSourceUrls = List.copyOf(citedSourceUrls); }
    }
    Explanation explain(Request request) throws Exception;
}
