package org.jabref.logic.database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jabref.model.entry.AuthorList;
import org.jabref.model.entry.BibEntry;
import org.jabref.model.entry.field.Field;
import org.jabref.model.entry.field.FieldProperty;
import org.jabref.model.entry.field.StandardField;
import org.jspecify.annotations.NullMarked;

/// Generates likely duplicate pairs without deciding whether they are duplicates.
@NullMarked
public final class DuplicateCandidateGenerator {
    private static final int MAX_BLOCK_SIZE = 100;
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{Alnum}]+");
    private static final Pattern DOI_PREFIX = Pattern.compile("^(?i:https?://(dx\\.)?doi\\.org/)");

    public List<Candidate> generate(List<BibEntry> entries, BooleanSupplier cancelled) {
        List<EntryKeys> entryKeys = entries.stream()
                                           .map(EntryKeys::from)
                                           .toList();
        Map<String, List<Integer>> entriesByKey = new HashMap<>();
        for (int index = 0; index < entryKeys.size(); index++) {
            for (String key : entryKeys.get(index).keys()) {
                entriesByKey.computeIfAbsent(key, _ -> new ArrayList<>()).add(index);
            }
        }

        Set<Long> candidatePairs = new HashSet<>();
        Set<Integer> entriesRequiringFallback = new HashSet<>();
        for (List<Integer> matchingEntries : entriesByKey.values()) {
            if (matchingEntries.size() > MAX_BLOCK_SIZE) {
                entriesRequiringFallback.addAll(matchingEntries);
                continue;
            }
            for (int first = 0; first < matchingEntries.size() - 1; first++) {
                for (int second = first + 1; second < matchingEntries.size(); second++) {
                    if (cancelled.getAsBoolean()) {
                        return List.of();
                    }
                    candidatePairs.add(pairKey(matchingEntries.get(first), matchingEntries.get(second)));
                }
            }
        }

        for (int index = 0; index < entryKeys.size(); index++) {
            if (!entryKeys.get(index).hasReliableKey()) {
                entriesRequiringFallback.add(index);
            }
        }
        List<Integer> fallbackEntries = entriesRequiringFallback.stream().sorted().toList();
        for (int first = 0; first < fallbackEntries.size(); first++) {
            for (int second = 0; second < entryKeys.size(); second++) {
                if (fallbackEntries.get(first) >= second) {
                    continue;
                }
                if (cancelled.getAsBoolean()) {
                    return List.of();
                }
                candidatePairs.add(pairKey(fallbackEntries.get(first), second));
            }
        }

        return candidatePairs.stream()
                             .sorted()
                             .map(key -> new Candidate(entries.get(firstIndex(key)), entries.get(secondIndex(key))))
                             .toList();
    }

    private static long pairKey(int first, int second) {
        return ((long) first << Integer.SIZE) | Integer.toUnsignedLong(second);
    }

    private static int firstIndex(long pairKey) {
        return (int) (pairKey >> Integer.SIZE);
    }

    private static int secondIndex(long pairKey) {
        return (int) pairKey;
    }

    private static String normalize(String value) {
        return NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static String normalizeIdentifier(Field field, String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (field == StandardField.DOI) {
            normalized = DOI_PREFIX.matcher(normalized).replaceFirst("");
        }
        return normalize(normalized);
    }

    private static String normalizePersonName(String value) {
        return normalize(AuthorList.fixAuthorLastNameOnlyCommas(value, false).replace(" and ", " "));
    }

    private static String normalizeTokens(String value) {
        return List.of(normalize(value).split(" ")).stream()
                               .filter(token -> token.length() > 2)
                               .sorted()
                               .collect(Collectors.joining(" "));
    }

    private static String firstField(BibEntry entry, StandardField... fields) {
        for (StandardField field : fields) {
            String value = entry.getFieldLatexFree(field).orElse("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    public record Candidate(BibEntry first, BibEntry second) {
    }

    private record EntryKeys(Set<String> keys) {
        private boolean hasReliableKey() {
            return keys.stream().anyMatch(key -> key.startsWith("identifier:")
                    || key.startsWith("title-author:")
                    || key.startsWith("title-year:")
                    || key.startsWith("venue-volume-pages:"));
        }

        private static EntryKeys from(BibEntry entry) {
            Set<String> keys = new HashSet<>();
            for (Field field : entry.getFields()) {
                if (field.getProperties().contains(FieldProperty.IDENTIFIER)) {
                    entry.getFieldLatexFree(field)
                         .filter(value -> !value.isBlank())
                         .map(value -> "identifier:" + field.getName() + ':' + normalizeIdentifier(field, value))
                         .ifPresent(keys::add);
                }
            }
            entry.getISBN()
                 .ifPresent(isbn -> keys.add("identifier:isbn:" + isbn.asString()));

            String title = firstField(entry, StandardField.TITLE);
            String author = firstField(entry, StandardField.AUTHOR, StandardField.EDITOR);
            String year = firstField(entry, StandardField.YEAR, StandardField.DATE);
            String venue = firstField(entry,
                    StandardField.JOURNAL,
                    StandardField.JOURNALTITLE,
                    StandardField.BOOKTITLE,
                    StandardField.VENUE);
            String volume = firstField(entry, StandardField.VOLUME);
            String pages = firstField(entry, StandardField.PAGES);

            String normalizedTitle = normalize(title);
            String normalizedAuthor = normalizePersonName(author);
            String normalizedYear = normalize(year);
            String normalizedVenue = normalize(venue);

            if (!normalizedTitle.isEmpty() && !normalizedAuthor.isEmpty()) {
                keys.add("title-author:" + normalizedTitle + ':' + normalizedAuthor);
            }
            if (!normalizedTitle.isEmpty() && !normalizedYear.isEmpty()) {
                keys.add("title-year:" + normalizedTitle + ':' + normalizedYear);
            }
            if (!normalizedAuthor.isEmpty() && !normalizedYear.isEmpty()) {
                keys.add("author-year:" + normalizedAuthor + ':' + normalizedYear);
            }
            if (!normalizedTitle.isEmpty() && !normalizedAuthor.isEmpty()) {
                String titleTokens = normalizeTokens(title);
                if (!titleTokens.isEmpty()) {
                    keys.add("title-tokens-author:" + titleTokens + ':' + normalizedAuthor);
                }
            }
            if (!normalizedTitle.isEmpty() && !normalizedYear.isEmpty()) {
                String titleTokens = normalizeTokens(title);
                if (!titleTokens.isEmpty()) {
                    keys.add("title-tokens-year:" + titleTokens + ':' + normalizedYear);
                }
            }
            if (!normalizedVenue.isEmpty() && !volume.isBlank() && !pages.isBlank()) {
                keys.add("venue-volume-pages:" + normalizedVenue + ':' + normalize(volume) + ':' + normalize(pages));
            }
            return new EntryKeys(Set.copyOf(keys));
        }
    }
}
