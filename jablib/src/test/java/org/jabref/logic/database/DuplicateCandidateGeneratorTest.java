package org.jabref.logic.database;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jabref.model.database.BibDatabaseMode;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateCandidateGeneratorTest {
    private final DuplicateCandidateGenerator generator = new DuplicateCandidateGenerator();

    @Test
    void generatesCandidatesFromStrongKeysAndOnlyOnce() {
        BibEntry first = article("A Study of Graph Algorithms", "Jane Doe", "2024")
                .withField(StandardField.DOI, "https://doi.org/10.1234/ABC");
        BibEntry second = article("Graph Algorithms: A Study", "Jane Doe", "2024")
                .withField(StandardField.DOI, "10.1234/abc");
        BibEntry unrelated = article("Unrelated Work", "John Doe", "");

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second, unrelated), () -> false);

        assertEquals(1, candidates.size());
        assertEquals(first, candidates.getFirst().first());
        assertEquals(second, candidates.getFirst().second());
    }

    @Test
    void generatesCandidateForExactDuplicates() {
        BibEntry first = article("A title", "An author", "2024");
        BibEntry second = new BibEntry(first);

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second), () -> false);

        assertEquals(1, candidates.size());
        assertEquals(first, candidates.getFirst().first());
        assertEquals(second, candidates.getFirst().second());
    }

    @Test
    void generatesCandidatesForMissingIdentifiersUsingBibliographicFields() {
        BibEntry first = article("A title", "An author", "2024");
        BibEntry second = article("A title", "An author", "2024");

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second), () -> false);

        assertEquals(1, candidates.size());
    }

    @Test
    void generatesCandidatesForFormattingDifferences() {
        BibEntry first = article("A Study: of Graph Algorithms", "Doe, Jane", "2024");
        BibEntry second = article("a study of graph algorithms", "Jane Doe", "2024");

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second), () -> false);

        assertEquals(1, candidates.size());
    }

    @Test
    void generatesCandidatesForIncompleteMetadataWhenAStrongKeyIsAvailable() {
        BibEntry first = article("A title", "", "");
        BibEntry second = new BibEntry(StandardEntryType.Article)
                .withField(StandardField.TITLE, "A title")
                .withField(StandardField.AUTHOR, "An author")
                .withField(StandardField.DOI, "10.1234/example");

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second), () -> false);

        assertEquals(1, candidates.size());
    }

    @Test
    void doesNotGenerateCandidateForUnrelatedEntries() {
        BibEntry first = article("A title", "An author", "2024");
        BibEntry second = article("A different title", "A different author", "2025");

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second), () -> false);

        assertEquals(List.of(), candidates);
    }

    @Test
    void candidatePairsGeneratedByMultipleRulesAreReturnedOnce() {
        BibEntry first = article("A title", "An author", "2024")
                .withField(StandardField.JOURNAL, "A journal")
                .withField(StandardField.VOLUME, "1")
                .withField(StandardField.PAGES, "1-10");
        BibEntry second = article("A title", "An author", "2024")
                .withField(StandardField.JOURNAL, "A journal")
                .withField(StandardField.VOLUME, "1")
                .withField(StandardField.PAGES, "1-10");

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(List.of(first, second), () -> false);

        assertEquals(1, candidates.size());
    }

    @Test
    void largeCommonBlocksUseTheExhaustiveFallback() {
        List<BibEntry> entries = IntStream.range(0, 101)
                .mapToObj(index -> article("Common title", "Author " + index, "2024"))
                .toList();

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(entries, () -> false);

        assertEquals(101 * 100 / 2, candidates.size());
    }

    @Test
    void candidateResultsContainAllDuplicatesFoundByExhaustiveSearch() {
        List<BibEntry> entries = List.of(
                article("A title", "An author", "2024").withField(StandardField.DOI, "10.1234/example"),
                article("a title", "An author", "2024"),
                article("A serious paper about something", "Another author", "2023"),
                article("A serious paper about something else", "Another author", "2023"),
                article("Unrelated title", "Unrelated author", "2022"));
        DuplicateCheck duplicateCheck = new DuplicateCheck(new BibEntryTypesManager());
        Set<IndexedPair> exhaustiveDuplicates = allPairs(entries).stream()
                .filter(pair -> duplicateCheck.isDuplicate(entries.get(pair.first()), entries.get(pair.second()), BibDatabaseMode.BIBTEX))
                .collect(Collectors.toSet());
        Map<BibEntry, Integer> entryIndexes = new IdentityHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            entryIndexes.put(entries.get(index), index);
        }
        Set<IndexedPair> candidateDuplicates = generator.generate(entries, () -> false).stream()
                .map(candidate -> new IndexedPair(entryIndexes.get(candidate.first()), entryIndexes.get(candidate.second())))
                .filter(pair -> duplicateCheck.isDuplicate(entries.get(pair.first()), entries.get(pair.second()), BibDatabaseMode.BIBTEX))
                .collect(Collectors.toSet());

        assertEquals(exhaustiveDuplicates, candidateDuplicates);
    }

    @Test
    void stopsGeneratingWhenCancelled() {
        List<BibEntry> entries = List.of(
                article("A title", "An author", "2024"),
                article("A title", "An author", "2024"),
                article("A title", "An author", "2024"));
        AtomicInteger cancellationChecks = new AtomicInteger();

        List<DuplicateCandidateGenerator.Candidate> candidates = generator.generate(entries, () -> cancellationChecks.incrementAndGet() > 1);

        assertEquals(List.of(), candidates);
        assertEquals(2, cancellationChecks.get());
    }

    private static Set<IndexedPair> allPairs(List<BibEntry> entries) {
        Set<IndexedPair> pairs = new HashSet<>();
        for (int first = 0; first < entries.size() - 1; first++) {
            for (int second = first + 1; second < entries.size(); second++) {
                pairs.add(new IndexedPair(first, second));
            }
        }
        return pairs;
    }

    private record IndexedPair(int first, int second) {
    }

    private static BibEntry article(String title, String author, String year) {
        BibEntry entry = new BibEntry(StandardEntryType.Article);
        if (!title.isEmpty()) {
            entry = entry.withField(StandardField.TITLE, title);
        }
        if (!author.isEmpty()) {
            entry = entry.withField(StandardField.AUTHOR, author);
        }
        if (!year.isEmpty()) {
            entry = entry.withField(StandardField.YEAR, year);
        }
        return entry;
    }
}
