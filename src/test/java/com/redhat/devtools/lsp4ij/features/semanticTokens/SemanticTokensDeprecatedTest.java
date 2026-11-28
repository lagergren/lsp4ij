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

import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;

import java.util.List;

/**
 * A token with the modifier <code>deprecated</code> is struck through, as IntelliJ shows a deprecated symbol, and keeps
 * its color: in a file without PSI of its own, whose highlights are added at once, and in a file with PSI, whose
 * highlights are added per PSI element.
 */
public class SemanticTokensDeprecatedTest extends LSPCodeInsightFixtureTestCase {

    private static final String DEPRECATED = CodeInsightColors.DEPRECATED_ATTRIBUTES.getExternalName();

    public SemanticTokensDeprecatedTest() {
        super("*.luax", "*.xml");
    }

    public void testADeprecatedTokenIsStruckThrough() {
        // "foo" is a deprecated function, and "bar" a function
        startServer(new SemanticTokens(List.of(
                0, 0, 3, 0, 1,
                0, 4, 3, 0, 0)));
        myFixture.configureByText("test.luax", "foo bar\n");

        assertEquals(List.of("0-3:" + DEPRECATED, "0-3:LSP_FUNCTION", "4-7:LSP_FUNCTION"), semanticHighlights());
    }

    public void testADeprecatedTokenOfAPsiElementIsStruckThrough() {
        // The tag name "foo" is a deprecated function, and the attribute name "bar" a function
        startServer(new SemanticTokens(List.of(
                0, 1, 3, 0, 1,
                0, 4, 3, 0, 0)));
        myFixture.configureByText("test.xml", "<foo bar=\"x\"/>\n");

        assertEquals(List.of("1-4:" + DEPRECATED, "1-4:LSP_FUNCTION", "5-8:LSP_FUNCTION"), semanticHighlights());
    }

    private static void startServer(SemanticTokens tokens) {
        var legend = new SemanticTokensLegend(List.of("function"), List.of("deprecated"));
        var serverCapabilities = MockLanguageServer.defaultServerCapabilities();
        serverCapabilities.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(legend, true));
        MockLanguageServer.reset(() -> serverCapabilities);
        MockLanguageServer.INSTANCE.setSemanticTokens(tokens);
    }

    /**
     * Highlights the file, and returns its semantic highlights, as "start-end:key", in the order of their offsets and
     * keys.
     */
    private List<String> semanticHighlights() {
        return myFixture.doHighlighting()
                .stream()
                .filter(info -> info.forcedTextAttributesKey != null
                        && (info.forcedTextAttributesKey.getExternalName().startsWith("LSP_")
                        || info.forcedTextAttributesKey.getExternalName().equals(DEPRECATED)))
                .map(info -> info.getStartOffset() + "-" + info.getEndOffset() + ":"
                        + info.forcedTextAttributesKey.getExternalName())
                .sorted()
                .toList();
    }
}
