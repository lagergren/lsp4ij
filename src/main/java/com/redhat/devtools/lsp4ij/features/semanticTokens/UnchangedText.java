/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.semanticTokens;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;

/**
 * The text that an earlier and a later version of a document have in common: the text before the first change, and the
 * text after the last one. A range of the earlier version that lies in one of them, without touching the change, has
 * the same text in the later version, at the offset that {@link #map(int, int)} gives; any other range has changed, or
 * may have: an insertion next to an identifier, as "x" typed after "foo", changes the identifier.
 *
 * @param prefixLength   the length of the text before the first change, the same in both versions.
 * @param oldSuffixStart the offset, in the earlier version, of the text after the last change.
 * @param newSuffixStart the offset, in the later version, of the text after the last change.
 */
record UnchangedText(int prefixLength, int oldSuffixStart, int newSuffixStart) {

    // How many characters are compared between two checks of the cancellation
    private static final int CHARACTERS_BETWEEN_CHECKS = 1 << 14;

    /**
     * Returns the text that the given versions of a document have in common at their start and at their end.
     *
     * @param oldText the earlier version.
     * @param newText the later version.
     * @return the text that the given versions of a document have in common at their start and at their end.
     */
    static @NotNull UnchangedText between(@NotNull CharSequence oldText, @NotNull CharSequence newText) {
        int oldLength = oldText.length();
        int newLength = newText.length();
        int shorterLength = Math.min(oldLength, newLength);
        int prefixLength = 0;
        while (prefixLength < shorterLength && oldText.charAt(prefixLength) == newText.charAt(prefixLength)) {
            prefixLength++;
            if (prefixLength % CHARACTERS_BETWEEN_CHECKS == 0) {
                ProgressManager.checkCanceled();
            }
        }
        int suffixLength = 0;
        while (suffixLength < shorterLength - prefixLength
                && oldText.charAt(oldLength - 1 - suffixLength) == newText.charAt(newLength - 1 - suffixLength)) {
            suffixLength++;
            if (suffixLength % CHARACTERS_BETWEEN_CHECKS == 0) {
                ProgressManager.checkCanceled();
            }
        }
        return new UnchangedText(prefixLength, oldLength - suffixLength, newLength - suffixLength);
    }

    /**
     * Returns the start offset, in the later version, of the given range of the earlier version, whose text is the same
     * in both, or -1 when its text has changed, or touches a change.
     *
     * @param start the start offset of the range in the earlier version.
     * @param end   the end offset of the range in the earlier version.
     * @return the start offset of the range in the later version, or -1 when its text has changed, or touches a change.
     */
    int map(int start, int end) {
        if (end < prefixLength) {
            // Before the first change, with at least one unchanged character between them
            return start;
        }
        if (start > oldSuffixStart) {
            // After the last change, with at least one unchanged character between them
            return start - oldSuffixStart + newSuffixStart;
        }
        return -1;
    }
}
