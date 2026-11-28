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

import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import com.redhat.devtools.lsp4ij.mock.MockTextDocumentService.SemanticTokensRequest;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensServerFull;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A language server which has semantic tokens for a range of the file only, and not for the whole file, gets
 * <code>textDocument/semanticTokens/range</code> for the range of the whole file, and its tokens show; one which has
 * both gets <code>textDocument/semanticTokens/full</code>.
 */
public class SemanticTokensRangeTest extends LSPCodeInsightFixtureTestCase {

    private static final String FILE_NAME = "test.luax";

    // Two lines, and an empty third one
    private static final String TEXT = "foo bar\nbaz\n";

    // "foo" is a function, and "baz" a keyword
    private static final SemanticTokens TOKENS = new SemanticTokens(List.of(
            0, 0, 3, 0, 0,
            1, 0, 3, 1, 0));

    public SemanticTokensRangeTest() {
        super("*.luax");
    }

    public void testARangeOnlyServerGetsTheRangeOfTheWholeFile() {
        var options = new SemanticTokensWithRegistrationOptions(legend());
        options.setRange(true);
        startServer(options);
        myFixture.configureByText(FILE_NAME, TEXT);

        assertEquals(List.of("0-3:LSP_FUNCTION", "8-11:LSP_KEYWORD"), semanticHighlights());
        var range = new Range(new Position(0, 0), new Position(2, 0));
        assertEquals(List.of(new SemanticTokensRequest("range", 1, range)), requests());
    }

    public void testAServerWithFullAndRangeGetsFull() {
        var options = new SemanticTokensWithRegistrationOptions(legend(), true, true);
        startServer(options);
        myFixture.configureByText(FILE_NAME, TEXT);

        assertEquals(List.of("0-3:LSP_FUNCTION", "8-11:LSP_KEYWORD"), semanticHighlights());
        assertEquals(List.of(new SemanticTokensRequest("full", 1, null)), requests());
    }

    public void testAServerWithFullDeltaGetsFull() {
        var options = new SemanticTokensWithRegistrationOptions(legend());
        options.setFull(Either.forRight(new SemanticTokensServerFull(true)));
        options.setRange(true);
        startServer(options);
        myFixture.configureByText(FILE_NAME, TEXT);

        assertEquals(List.of("0-3:LSP_FUNCTION", "8-11:LSP_KEYWORD"), semanticHighlights());
        assertEquals(List.of(new SemanticTokensRequest("full", 1, null)), requests());
    }

    private static SemanticTokensLegend legend() {
        return new SemanticTokensLegend(List.of("function", "keyword"), List.of());
    }

    private static void startServer(SemanticTokensWithRegistrationOptions options) {
        var serverCapabilities = MockLanguageServer.defaultServerCapabilities();
        serverCapabilities.setSemanticTokensProvider(options);
        MockLanguageServer.reset(() -> serverCapabilities);
        MockLanguageServer.INSTANCE.getTextDocumentService()
                .setSemanticTokensProcessor(request -> CompletableFuture.completedFuture(TOKENS));
    }

    private static List<SemanticTokensRequest> requests() {
        return MockLanguageServer.INSTANCE.getTextDocumentService().getSemanticTokensRequests();
    }

    /**
     * Highlights the file, and returns its semantic highlights, as "start-end:key", in the order of their offsets.
     */
    private List<String> semanticHighlights() {
        return myFixture.doHighlighting()
                .stream()
                .filter(info -> info.forcedTextAttributesKey != null
                        && info.forcedTextAttributesKey.getExternalName().startsWith("LSP_"))
                .map(info -> info.getStartOffset() + "-" + info.getEndOffset() + ":"
                        + info.forcedTextAttributesKey.getExternalName())
                .sorted()
                .toList();
    }
}
