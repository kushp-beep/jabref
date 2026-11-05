package org.jabref.benchmarks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jabref.logic.database.DuplicateCandidateGenerator;
import org.jabref.logic.database.DuplicateCheck;
import org.jabref.model.database.BibDatabaseMode;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.BibEntryTypesManager;
import org.jabref.model.entry.field.StandardField;
import org.jabref.model.entry.types.StandardEntryType;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(1)
public class DuplicateSearchBenchmark {
    @State(Scope.Thread)
    public static class BenchmarkState {
        @Param({"complete", "sparse", "common"})
        public String fixture;

        @Param({"64", "256", "1010"})
        public int size;

        private final DuplicateCandidateGenerator candidateGenerator = new DuplicateCandidateGenerator();
        private final DuplicateCheck duplicateCheck = new DuplicateCheck(new BibEntryTypesManager());
        private List<BibEntry> entries;
        private Map<BibEntry, Integer> entryIndexes;
        private Set<IndexedPair> expectedDuplicates;

        @Setup(Level.Trial)
        public void setUp() {
            entries = createEntries(fixture, size);
            entryIndexes = new IdentityHashMap<>();
            for (int index = 0; index < entries.size(); index++) {
                entryIndexes.put(entries.get(index), index);
            }
            expectedDuplicates = verifiedDuplicatePairs(allPairs(entries));
            Set<IndexedPair> candidateDuplicates = verifiedDuplicatePairs(candidateGenerator.generate(entries, () -> false));
            if (!expectedDuplicates.equals(candidateDuplicates)) {
                throw new AssertionError("Candidate selection missed duplicate pairs for " + fixture + " with " + size + " entries");
            }
        }

        private Set<IndexedPair> verifiedDuplicatePairs(List<DuplicateCandidateGenerator.Candidate> candidates) {
            List<IndexedPair> pairs = candidates.stream()
                                                .map(candidate -> new IndexedPair(entryIndexes.get(candidate.first()), entryIndexes.get(candidate.second())))
                                                .toList();
            return verifiedDuplicatePairs(new HashSet<>(pairs));
        }

        private Set<IndexedPair> verifiedDuplicatePairs(Set<IndexedPair> pairs) {
            return pairs.stream()
                        .filter(pair -> duplicateCheck.isDuplicate(entries.get(pair.first()), entries.get(pair.second()), BibDatabaseMode.BIBTEX))
                        .collect(Collectors.toSet());
        }
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long comparisons;
        public long duplicates;

        @Setup(Level.Invocation)
        public void reset() {
            comparisons = 0;
            duplicates = 0;
        }
    }

    @Benchmark
    public int originalAllPairs(BenchmarkState state, Counters counters) {
        int duplicates = 0;
        for (int first = 0; first < state.entries.size() - 1; first++) {
            for (int second = first + 1; second < state.entries.size(); second++) {
                counters.comparisons++;
                if (state.duplicateCheck.isDuplicate(state.entries.get(first), state.entries.get(second), BibDatabaseMode.BIBTEX)) {
                    duplicates++;
                }
            }
        }
        counters.duplicates = duplicates;
        return duplicates;
    }

    @Benchmark
    public int candidateSelectionAndCheck(BenchmarkState state, Counters counters) {
        int duplicates = 0;
        List<DuplicateCandidateGenerator.Candidate> candidates = state.candidateGenerator.generate(state.entries, () -> false);
        for (DuplicateCandidateGenerator.Candidate candidate : candidates) {
            counters.comparisons++;
            if (state.duplicateCheck.isDuplicate(candidate.first(), candidate.second(), BibDatabaseMode.BIBTEX)) {
                duplicates++;
            }
        }
        counters.duplicates = duplicates;
        return duplicates;
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

    private static List<BibEntry> createEntries(String fixture, int size) {
        List<BibEntry> entries = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            BibEntry entry = new BibEntry(StandardEntryType.Article);
            if ("complete".equals(fixture)) {
                entry = entry.withField(StandardField.TITLE, "Distinct title " + index)
                             .withField(StandardField.AUTHOR, "Author " + index)
                             .withField(StandardField.YEAR, String.valueOf(2000 + index % 20))
                             .withField(StandardField.JOURNAL, "Journal " + index % 10)
                             .withField(StandardField.VOLUME, String.valueOf(index % 5 + 1))
                             .withField(StandardField.PAGES, index + "-" + (index + 10));
            } else if ("sparse".equals(fixture)) {
                entry = entry.withField(StandardField.TITLE, "Sparse title " + index);
            } else {
                entry = entry.withField(StandardField.TITLE, "Common title")
                             .withField(StandardField.AUTHOR, "Author " + index)
                             .withField(StandardField.YEAR, "2024")
                             .withField(StandardField.JOURNAL, "Common journal");
            }
            entries.add(entry);
        }
        addDuplicatePairs(entries);
        return entries;
    }

    private static void addDuplicatePairs(List<BibEntry> entries) {
        for (int index = 0; index + 1 < entries.size(); index += 100) {
            entries.set(index + 1, new BibEntry(entries.get(index)));
        }
    }

    private record IndexedPair(int first, int second) {
    }
}
