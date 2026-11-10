package com.airca.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalResultTest {

    @Test
    void emptyResult_hasEmptyListsAndNoTopRunbook() {
        RetrievalResult result = RetrievalResult.empty("Sample context");

        assertEquals("Sample context", result.context());
        assertTrue(result.runbookMatches().isEmpty());
        assertTrue(result.pastIncidents().isEmpty());
        assertEquals("", result.gitDiff());
        assertTrue(result.topRunbookMatch().isEmpty());
        assertTrue(result.topRunbookName().isEmpty());
        assertFalse(result.hasQualifyingMatch());
    }

    @Test
    void nullArguments_areNormalizedToSafeDefaults() {
        RetrievalResult result = new RetrievalResult(null, null, null, null);

        assertEquals("", result.context());
        assertTrue(result.runbookMatches().isEmpty());
        assertTrue(result.pastIncidents().isEmpty());
        assertEquals("", result.gitDiff());
        assertFalse(result.hasQualifyingMatch());
    }

    @Test
    void topRunbookMatchAndName_returnsFirstMatchAndTrimsName() {
        VectorRetrieverService.RunbookMatch match1 = new VectorRetrieverService.RunbookMatch(
                "  RB-ALPHA-01 Database Deadlock  ",
                "Deadlock remediation steps",
                "docs/alpha.md",
                null,
                0.08,
                0.92);
        VectorRetrieverService.RunbookMatch match2 = new VectorRetrieverService.RunbookMatch(
                "RB-BETA-02 Connection Pool",
                "Pool sizing steps",
                "docs/beta.md",
                null,
                0.15,
                0.85);

        RetrievalResult result = new RetrievalResult("Context", List.of(match1, match2));

        assertTrue(result.topRunbookMatch().isPresent());
        assertEquals(match1, result.topRunbookMatch().get());
        assertTrue(result.topRunbookName().isPresent());
        assertEquals("RB-ALPHA-01 Database Deadlock", result.topRunbookName().get());
        assertTrue(result.hasQualifyingMatch());
    }

    @Test
    void blankRunbookName_isIgnoredAsQualifyingMatch() {
        VectorRetrieverService.RunbookMatch blankNameMatch = new VectorRetrieverService.RunbookMatch(
                "    ",
                "Text",
                "docs/blank.md",
                null,
                0.05,
                0.95);

        RetrievalResult result = new RetrievalResult("Context", List.of(blankNameMatch));

        assertTrue(result.topRunbookMatch().isPresent());
        assertTrue(result.topRunbookName().isEmpty());
        assertFalse(result.hasQualifyingMatch());
    }
}
