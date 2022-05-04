/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests.fixtures.logging.comparison;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class ExhaustiveLinesSearcher {
    private boolean matchBlankLines = false;
    private boolean showLessLikelyMatches = false;
    private boolean useUnifiedDiff = false;

    public ExhaustiveLinesSearcher matchesBlankLines() {
        matchBlankLines = true;
        return this;
    }

    public ExhaustiveLinesSearcher showLessLikelyMatches() {
        showLessLikelyMatches = true;
        return this;
    }

    /**
     * Display the most likely potential match according to the git diff algorithm.
     * <p>
     * Enabling this option will only show a single match, regardless of how many potential matches
     * containing the same number of matching lines are found.
     *
     * @return {@code this}
     */
    public ExhaustiveLinesSearcher useUnifiedDiff() {
        useUnifiedDiff = true;
        return this;
    }

    public void assertLinesContainedIn(List<String> expectedLines, List<String> actualLines) throws LineSearchFailures.AbstractLineListComparisonFailure {
        if (expectedLines.isEmpty() && actualLines.isEmpty()) {
            return;
        }

        if (actualLines.size() < expectedLines.size()) {
            LineSearchFailures.insufficientSize(expectedLines, actualLines);
        }

        if (!findFirstCompleteMatch(expectedLines, actualLines).isPresent()) {
            exhaustiveSearch(expectedLines, actualLines);
        }
    }

    public void assertSameLines(List<String> expectedLines, List<String> actualLines) {
        if (expectedLines.isEmpty() && actualLines.isEmpty()) {
            return;
        }

        if (expectedLines.size() != actualLines.size()) {
            LineSearchFailures.differentSizes(expectedLines, actualLines);
        }

        if (!findFirstCompleteMatch(expectedLines, actualLines).orElse(-1).equals(0)) {
            exhaustiveSearch(expectedLines, actualLines);
        }
    }

    /**
     * Quickly attempts to find a complete match of the expected lines in the actual lines.
     * @param expectedLines
     * @param actualLines
     * @return the index of the first matching line, or {@link Optional#empty()} if no match was found
     */
    private Optional<Integer> findFirstCompleteMatch(List<String> expectedLines, List<String> actualLines) {
        for (int actualIdx = 0; actualIdx < actualLines.size(); actualIdx++) {
            if (isMatchingIndex(expectedLines, actualLines, actualIdx)) {
                return Optional.of(actualIdx);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks whether the given actual line matches the expected line at the given actual index.
     * @param expectedLines
     * @param actualLines
     * @param actualMatchStartIdx
     * @return {@code true} if the actual index is a valid potential match position for the given number of expected lines,
     *  and the actual lines matches the corresponding expected lines beginning at that index
     */
    private boolean isMatchingIndex(List<String> expectedLines, List<String> actualLines, int actualMatchStartIdx) {
        for (int expectedIdx = 0; expectedIdx < expectedLines.size(); expectedIdx++) {
            int actualIdx = actualMatchStartIdx + expectedIdx;
            if (actualIdx >= actualLines.size() || !Objects.equals(expectedLines.get(expectedIdx), actualLines.get(actualIdx))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds all the lines in the actual line that match any line in the expected list.
     * @param expectedLines
     * @param actualLines
     * @return a map of expected line index to a list of all actual line indices that match the expected line
     */
    private Map<Integer, List<Integer>> findMatchingLines(List<String> expectedLines, List<String> actualLines) {
        Map<Integer, List<Integer>> result = new HashMap<>(expectedLines.size());
        for (int expectedIdx = 0; expectedIdx < expectedLines.size(); expectedIdx++) {
            String expectedLine = expectedLines.get(expectedIdx);
            for (int actualIdx = 0; actualIdx < actualLines.size(); actualIdx++) {
                String actualLine = actualLines.get(actualIdx);
                if (Objects.equals(expectedLine, actualLine) && (matchBlankLines || !StringUtils.isEmpty(expectedLine))) {
                    result.computeIfAbsent(expectedIdx, matches -> new ArrayList<>()).add(actualIdx);
                }
            }
        }
        return result;
    }

    private void exhaustiveSearch(List<String> expectedLines, List<String> actualLines) {
        Map<Integer, List<Integer>> matchingLines = findMatchingLines(expectedLines, actualLines);
        Set<PotentialMatch> potentialMatches = convertMatchingLines(expectedLines, actualLines, matchingLines);
        if (matchingLines.isEmpty()) {
            LineSearchFailures.noMatchingLines(expectedLines, actualLines);
        } else {
            if (!showLessLikelyMatches) {
                filterLessLikelyMatches(potentialMatches);
            }
            LineSearchFailures.potentialMatchesExist(expectedLines, actualLines, potentialMatches, useUnifiedDiff);
        }
    }

    private void filterLessLikelyMatches(Set<PotentialMatch> potentialMatches) {
        final long highestNumMatches = potentialMatches.stream().map(PotentialMatch::getNumMatches).max(Long::compareTo).orElse(0L);
        potentialMatches.removeIf(pm -> pm.getNumMatches() < highestNumMatches);
    }

    private Set<PotentialMatch> convertMatchingLines(List<String> expectedLines, List<String> actualLines, Map<Integer, List<Integer>> matchingLines) {
        Set<PotentialMatch> potentialMatches = new HashSet<>(matchingLines.size());
        for (Map.Entry<Integer, List<Integer>> entry : matchingLines.entrySet()) {
            int expectedIdx = entry.getKey();
            for (int actualIdx : entry.getValue()) {
                int matchBeginsActualIdx = actualIdx - expectedIdx;
                if (PotentialMatch.isPossibleMatchIndex(expectedLines, actualLines, matchBeginsActualIdx)) {
                    potentialMatches.add(new PotentialMatch(expectedLines, actualLines, matchBeginsActualIdx));
                }
            }
        }
        return potentialMatches;
    }
}
