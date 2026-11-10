package com.airca.rag;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Structured RAG retrieval result containing qualifying runbook candidates,
 * previously resolved incident matches, relevant git diff, and the formatted context.
 */
public record RetrievalResult(
        String context,
        List<VectorRetrieverService.RunbookMatch> runbookMatches,
        List<VectorRetrieverService.ResolvedIncidentMatch> pastIncidents,
        String gitDiff) {

    public RetrievalResult {
        runbookMatches = runbookMatches == null ? Collections.emptyList() : List.copyOf(runbookMatches);
        pastIncidents = pastIncidents == null ? Collections.emptyList() : List.copyOf(pastIncidents);
        context = context == null ? "" : context;
        gitDiff = gitDiff == null ? "" : gitDiff;
    }

    public RetrievalResult(String context, List<VectorRetrieverService.RunbookMatch> runbookMatches) {
        this(context, runbookMatches, Collections.emptyList(), "");
    }

    public Optional<VectorRetrieverService.RunbookMatch> topRunbookMatch() {
        return runbookMatches.isEmpty() ? Optional.empty() : Optional.of(runbookMatches.get(0));
    }

    public Optional<String> topRunbookName() {
        return topRunbookMatch()
                .map(VectorRetrieverService.RunbookMatch::runbookName)
                .map(String::trim)
                .filter(name -> !name.isEmpty());
    }

    public boolean hasQualifyingMatch() {
        return topRunbookName().isPresent();
    }

    public static RetrievalResult empty(String context) {
        return new RetrievalResult(context, Collections.emptyList(), Collections.emptyList(), "");
    }
}
