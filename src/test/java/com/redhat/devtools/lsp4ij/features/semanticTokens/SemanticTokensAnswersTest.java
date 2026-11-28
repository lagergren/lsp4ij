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

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import com.redhat.devtools.lsp4ij.mock.MockTextDocumentService.SemanticTokensRequest;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The semantic colors of a file when the language server answers a <code>textDocument/semanticTokens</code> request
 * with an error, or when the request is cancelled: the file keeps the colors it shows, where their text is unchanged.
 * The next highlighting pass asks again for a cancelled request, and the next edit for an error.
 */
public class SemanticTokensAnswersTest extends LSPCodeInsightFixtureTestCase {

    private static final String FILE_NAME = "test.luax";

    // "foo" is a function and "bar" a keyword
    private static final String TEXT = "foo bar\n";

    private static final SemanticTokens TOKENS = new SemanticTokens(List.of(
            0, 0, 3, 0, 0,
            0, 4, 3, 1, 0));

    // "bar" alone, as a function
    private static final SemanticTokens OTHER_TOKENS = new SemanticTokens(List.of(
            0, 4, 3, 0, 0));

    public SemanticTokensAnswersTest() {
        super("*.luax");
    }

    public void testAnErrorAfterAnEditKeepsTheColorsOfTheUnchangedText() {
        // "foo" is a function and "bar" a keyword, two spaces apart
        var tokens = new SemanticTokens(List.of(
                0, 0, 3, 0, 0,
                0, 5, 3, 1, 0));
        startServer(request -> request.number() == 1
                ? CompletableFuture.completedFuture(tokens)
                : CompletableFuture.failedFuture(error(ResponseErrorCode.InternalError)));
        myFixture.configureByText(FILE_NAME, "foo  bar\n");
        assertEquals(List.of("0-3:LSP_FUNCTION", "5-8:LSP_KEYWORD"), semanticHighlights());

        // "x" between the two spaces: the text before it stays, and the text after it moves by one character
        insert(4, "x");

        assertEquals("The colors are kept, where their text has moved",
                List.of("0-3:LSP_FUNCTION", "6-9:LSP_KEYWORD"), semanticHighlights());
        assertEquals(2, requests().size());
    }

    public void testAnErrorAfterAnEditDropsTheColorsOfTheChangedText() {
        startServer(request -> request.number() == 1
                ? CompletableFuture.completedFuture(TOKENS)
                : CompletableFuture.failedFuture(error(ResponseErrorCode.InternalError)));
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());

        // "foo" becomes "fxoo"
        insert(1, "x");

        assertEquals("The color of the changed text is dropped", List.of("5-8:LSP_KEYWORD"), semanticHighlights());
    }

    public void testAnErrorAfterAnEditDropsTheColorOfATokenFollowedByTheChange() {
        startServer(request -> request.number() == 1
                ? CompletableFuture.completedFuture(TOKENS)
                : CompletableFuture.failedFuture(error(ResponseErrorCode.InternalError)));
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());

        // "foo" becomes "foox", another identifier
        insert(3, "x");

        assertEquals("The color of the token next to the change is dropped",
                List.of("5-8:LSP_KEYWORD"), semanticHighlights());
    }

    public void testAnErrorAfterAnEditDropsTheColorOfATokenPrecededByTheChange() {
        startServer(request -> request.number() == 1
                ? CompletableFuture.completedFuture(TOKENS)
                : CompletableFuture.failedFuture(error(ResponseErrorCode.InternalError)));
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());

        // "foo" becomes "xfoo", another identifier
        insert(0, "x");

        assertEquals("The color of the token next to the change is dropped",
                List.of("5-8:LSP_KEYWORD"), semanticHighlights());
    }

    public void testAnErrorAnswerIsKeptUntilTheNextEdit() {
        startServer(request -> switch (request.number()) {
            case 1 -> CompletableFuture.completedFuture(TOKENS);
            case 2 -> CompletableFuture.failedFuture(error(ResponseErrorCode.InternalError));
            default -> CompletableFuture.completedFuture(OTHER_TOKENS);
        });
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());
        insert(TEXT.length(), " ");
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());

        // A restart of the highlighting without an edit, as other features ask for, doesn't send the request, and
        // report its error, again
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), rehighlight());
        assertEquals(2, requests().size());

        // The next edit asks again, and shows the answer
        insert(TEXT.length(), " ");
        assertEquals(List.of("4-7:LSP_FUNCTION"), semanticHighlights());
        assertEquals(3, requests().size());
    }

    public void testACancelledRequestKeepsTheColors() {
        var held = new CompletableFuture<SemanticTokens>();
        var cancelled = new CompletableFuture<Void>();
        PsiFile[] file = new PsiFile[1];
        startServer(request -> {
            if (request.number() == 1) {
                return CompletableFuture.completedFuture(TOKENS);
            }
            if (request.number() == 2) {
                // As workspace/semanticTokens/refresh does, evict the request while the highlighting waits for it
                CompletableFuture.runAsync(() -> {
                    LSPFileSupport.getSupport(file[0]).getSemanticTokensSupport().cancel();
                    cancelled.complete(null);
                }, AppExecutorUtil.getAppExecutorService());
                return held;
            }
            return CompletableFuture.completedFuture(OTHER_TOKENS);
        });
        file[0] = myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());
        LSPFileSupport.getSupport(file[0]).getSemanticTokensSupport().cancel();

        assertEquals("The cancelled request keeps the colors",
                List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), rehighlight());
        assertTrue("The request was cancelled while the highlighting waited for it", cancelled.isDone());
        assertEquals(2, requests().size());
        // The next highlighting pass asks again
        assertEquals(List.of("4-7:LSP_FUNCTION"), rehighlight());
    }

    public void testAnswerWithoutTokensRemovesTheColors() {
        startServer(request -> CompletableFuture.completedFuture(request.number() == 1 ? TOKENS : null));
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights());
        insert(0, "x");

        assertEquals("A language server which answers that the file has no semantic tokens removes its colors",
                List.of(), semanticHighlights());
    }

    /**
     * Starts the mock language server with semantic tokens of the types function and keyword, which answers the
     * semantic tokens requests with the given processor.
     */
    private static void startServer(@NotNull Function<SemanticTokensRequest, CompletableFuture<SemanticTokens>> processor) {
        var legend = new SemanticTokensLegend(List.of("function", "keyword"), List.of());
        var options = new SemanticTokensWithRegistrationOptions(legend, true);
        var serverCapabilities = MockLanguageServer.defaultServerCapabilities();
        serverCapabilities.setSemanticTokensProvider(options);
        MockLanguageServer.reset(() -> serverCapabilities);
        MockLanguageServer.INSTANCE.getTextDocumentService().setSemanticTokensProcessor(processor);
    }

    private static List<SemanticTokensRequest> requests() {
        return MockLanguageServer.INSTANCE.getTextDocumentService().getSemanticTokensRequests();
    }

    private static ResponseErrorException error(ResponseErrorCode code) {
        return new ResponseErrorException(new ResponseError(code, "semantic tokens error", null));
    }

    private void insert(int offset, String text) {
        var document = myFixture.getEditor().getDocument();
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> document.insertString(offset, text));
        PsiDocumentManager.getInstance(myFixture.getProject()).commitAllDocuments();
    }

    /**
     * Restarts the highlighting of the file without an edit, as a refresh or a language server's diagnostics do, then
     * highlights it, and returns its semantic highlights, as {@link #semanticHighlights()} does.
     */
    private List<String> rehighlight() {
        DaemonCodeAnalyzer.getInstance(myFixture.getProject()).restart(myFixture.getFile());
        return semanticHighlights();
    }

    /**
     * Highlights the file, and returns its semantic highlights, as "start-end:key", in the order of their offsets.
     */
    private List<String> semanticHighlights() {
        return myFixture.doHighlighting()
                .stream()
                .filter(info -> info.forcedTextAttributesKey != null
                        && info.forcedTextAttributesKey.getExternalName().startsWith("LSP_"))
                .sorted((a, b) -> a.getStartOffset() != b.getStartOffset()
                        ? Integer.compare(a.getStartOffset(), b.getStartOffset())
                        : Integer.compare(a.getEndOffset(), b.getEndOffset()))
                .map(info -> info.getStartOffset() + "-" + info.getEndOffset() + ":"
                        + info.forcedTextAttributesKey.getExternalName())
                .toList();
    }
}
