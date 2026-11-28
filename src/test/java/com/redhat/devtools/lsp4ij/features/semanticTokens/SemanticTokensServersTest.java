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

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServerDefinition;
import com.redhat.devtools.lsp4ij.templates.ServerMappingSettings;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The semantic colors of a file with two language servers which have semantic tokens: the file shows those of the
 * first language server in the order of their mappings which answers with semantic tokens, whichever starts first. When
 * the first one fails, the file keeps its colors.
 */
public class SemanticTokensServersTest extends LSPCodeInsightFixtureTestCase {

    private static final String FILE_NAME = "test.luax";

    private static final String TEXT = "foo bar\n";

    // The first language server, the one of the test fixture, has the types function and keyword: "foo" is a function
    private static final SemanticTokens FIRST_TOKENS = new SemanticTokens(List.of(0, 0, 3, 0, 0));

    // The second language server has the types keyword and function: "bar" is a function
    private static final SemanticTokens SECOND_TOKENS = new SemanticTokens(List.of(0, 4, 3, 1, 0));

    private MockLanguageServerDefinition second;

    public SemanticTokensServersTest() {
        super("*.luax");
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var firstCapabilities = MockLanguageServer.defaultServerCapabilities();
        firstCapabilities.setSemanticTokensProvider(options("function", "keyword"));
        MockLanguageServer.reset(() -> firstCapabilities);
        MockLanguageServer.INSTANCE.setSemanticTokens(FIRST_TOKENS);

        // The second language server is mapped after the first one, which the test fixture has mapped already
        second = new MockLanguageServerDefinition("second-semantic-tokens-server", false);
        second.getServer().getInitializeResult().getCapabilities().setSemanticTokensProvider(options("keyword", "function"));
        second.getServer().setSemanticTokens(SECOND_TOKENS);
        LanguageServersRegistry.getInstance().addServerDefinition(myFixture.getProject(), second,
                List.of(ServerMappingSettings.createFileNamePatternsMappingSettings(List.of("*.luax"), null)));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            second.getServer().waitBeforeTearDown();
            LanguageServersRegistry.getInstance().removeServerDefinition(myFixture.getProject(), second);
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    public void testTheFirstServerWinsWhenItStartsLast() throws Exception {
        CompletableFuture<Void> firstStart = MockLanguageServer.INSTANCE.holdInitialize();
        var secondOpen = new CompletableFuture<DidOpenTextDocumentParams>();
        second.getServer().setDidOpenCallback(secondOpen);
        PsiFile file = myFixture.configureByText(FILE_NAME, TEXT);

        // The second language server is ready first: the first finishes its start only once the second has the file
        var semanticTokens = askSemanticTokens(file);
        secondOpen.get(SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        firstStart.complete(null);

        assertEquals(FIRST_TOKENS, semanticTokens.get(SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS).getSemanticTokens());
        assertEquals(List.of("0-3:LSP_FUNCTION"), semanticHighlights());
    }

    public void testTheFirstServerWinsWhenItStartsFirst() throws Exception {
        CompletableFuture<Void> secondStart = second.getServer().holdInitialize();
        var firstOpen = new CompletableFuture<DidOpenTextDocumentParams>();
        MockLanguageServer.INSTANCE.setDidOpenCallback(firstOpen);
        PsiFile file = myFixture.configureByText(FILE_NAME, TEXT);

        var semanticTokens = askSemanticTokens(file);
        firstOpen.get(SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        secondStart.complete(null);

        assertEquals(FIRST_TOKENS, semanticTokens.get(SERVER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS).getSemanticTokens());
        assertEquals(List.of("0-3:LSP_FUNCTION"), semanticHighlights());
    }

    public void testTheNextServerWinsWhenTheFirstHasNoTokens() {
        MockLanguageServer.INSTANCE.setSemanticTokens(null);
        myFixture.configureByText(FILE_NAME, TEXT);

        assertEquals(List.of("4-7:LSP_FUNCTION"), semanticHighlights());
    }

    public void testAFailedFirstServerKeepsItsColorsWhenTheNextHasNone() {
        // After the first answer, the first language server answers with an error, and the second has no tokens
        MockLanguageServer.INSTANCE.getTextDocumentService().setSemanticTokensProcessor(request -> request.number() == 1
                ? CompletableFuture.completedFuture(FIRST_TOKENS)
                : CompletableFuture.failedFuture(error()));
        second.getServer().getTextDocumentService().setSemanticTokensProcessor(request ->
                CompletableFuture.completedFuture(request.number() == 1 ? SECOND_TOKENS : null));
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION"), semanticHighlights());

        appendSpace();

        assertEquals("The file keeps the colors of the first language server", List.of("0-3:LSP_FUNCTION"), semanticHighlights());
    }

    public void testAFailedFirstServerKeepsItsColorsWhenTheNextHasSome() {
        MockLanguageServer.INSTANCE.getTextDocumentService().setSemanticTokensProcessor(request -> request.number() == 1
                ? CompletableFuture.completedFuture(FIRST_TOKENS)
                : CompletableFuture.failedFuture(error()));
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION"), semanticHighlights());

        appendSpace();

        assertEquals("The file keeps the colors of the first language server, rather than taking those of the second",
                List.of("0-3:LSP_FUNCTION"), semanticHighlights());
    }

    public void testTheNextServerWinsWhenTheFirstHasAnEmptyAnswer() {
        MockLanguageServer.INSTANCE.setSemanticTokens(new SemanticTokens(List.of()));
        myFixture.configureByText(FILE_NAME, TEXT);

        assertEquals(List.of("4-7:LSP_FUNCTION"), semanticHighlights());
    }

    private void appendSpace() {
        var document = myFixture.getEditor().getDocument();
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(),
                () -> document.insertString(document.getTextLength(), " "));
        PsiDocumentManager.getInstance(myFixture.getProject()).commitAllDocuments();
    }

    private static ResponseErrorException error() {
        return new ResponseErrorException(new ResponseError(ResponseErrorCode.InternalError, "semantic tokens error", null));
    }

    private static SemanticTokensWithRegistrationOptions options(@NotNull String... tokenTypes) {
        return new SemanticTokensWithRegistrationOptions(new SemanticTokensLegend(List.of(tokenTypes), List.of()), true);
    }

    /**
     * Asks for the semantic tokens of the file, as the highlighting does, which starts its language servers.
     */
    private static CompletableFuture<SemanticTokensData> askSemanticTokens(@NotNull PsiFile file) {
        return ReadAction.compute(() -> LSPFileSupport.getSupport(file).getSemanticTokensSupport()
                .getSemanticTokens(new SemanticTokensParams(new TextDocumentIdentifier())));
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
