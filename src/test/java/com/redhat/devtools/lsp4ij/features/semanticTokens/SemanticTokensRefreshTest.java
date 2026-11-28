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
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.EditorTracker;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.services.LanguageClient;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A language server's <code>workspace/semanticTokens/refresh</code> shows its new semantic tokens without an edit: the
 * refreshes of other features can't postpone it. The daemon of the test highlights the editor as the IDE does, by its
 * timer, and the test waits for the new tokens to show.
 */
public class SemanticTokensRefreshTest extends LSPCodeInsightFixtureTestCase {

    private static final String FILE_NAME = "test.luax";

    private static final String TEXT = "foo bar\n";

    // "foo" is a function and "bar" a keyword
    private static final SemanticTokens TOKENS = new SemanticTokens(List.of(
            0, 0, 3, 0, 0,
            0, 4, 3, 1, 0));

    // "bar" alone, as a function
    private static final SemanticTokens NEW_TOKENS = new SemanticTokens(List.of(
            0, 4, 3, 0, 0));

    public SemanticTokensRefreshTest() {
        super("*.luax");
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var legend = new SemanticTokensLegend(List.of("function", "keyword"), List.of());
        var serverCapabilities = MockLanguageServer.defaultServerCapabilities();
        serverCapabilities.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(legend, true));
        MockLanguageServer.reset(() -> serverCapabilities);
        // The language server changes its tokens after the first request
        MockLanguageServer.INSTANCE.getTextDocumentService().setSemanticTokensProcessor(request ->
                CompletableFuture.completedFuture(request.number() == 1 ? TOKENS : NEW_TOKENS));
    }

    public void testRefreshesOfOtherFeaturesDoNotPostponeARefresh() throws Exception {
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights(myFixture.doHighlighting()));

        whileTheDaemonRuns(() -> {
            var client = client();
            // The language server refreshes its code lenses more often than the debounce of the restarts of the
            // highlighting, as some do while they index, while it refreshes its semantic tokens once
            var codeLensRefreshes = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(client::refreshCodeLenses, 0, 100, TimeUnit.MILLISECONDS);
            try {
                client.refreshSemanticTokens();
                waitForTheNewTokens();
            } finally {
                codeLensRefreshes.cancel(false);
            }
        });
    }

    private void waitForTheNewTokens() {
        var document = myFixture.getEditor().getDocument();
        PlatformTestUtil.waitWithEventsDispatching("the refresh must show the new semantic tokens without an edit",
                () -> List.of("4-7:LSP_FUNCTION").equals(semanticHighlights(
                        DaemonCodeAnalyzerImpl.getHighlights(document, null, myFixture.getProject()))),
                IN_MEMORY_TIMEOUT_SECONDS);
    }

    /**
     * Returns the client of the mock language server, as the language server sees it.
     */
    private static LanguageClient client() {
        var clients = MockLanguageServer.INSTANCE.getRemoteProxies();
        return clients.get(clients.size() - 1);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Runs the given test while the daemon highlights the editor by its timer, as when the editor is shown.
     */
    private void whileTheDaemonRuns(ThrowingRunnable test) throws Exception {
        var daemon = (DaemonCodeAnalyzerImpl) DaemonCodeAnalyzer.getInstance(myFixture.getProject());
        var editors = EditorTracker.getInstance(myFixture.getProject());
        setActiveEditors(editors, List.of(myFixture.getEditor()));
        daemon.setUpdateByTimerEnabled(true);
        try {
            test.run();
        } finally {
            daemon.setUpdateByTimerEnabled(false);
            setActiveEditors(editors, List.of());
            waitForDaemonCodeAnalyzerFinished();
        }
    }

    /**
     * Sets the active editors, which recent IntelliJ versions, such as 2026.2.3 and 2026.3, do with
     * {@code setActiveEditorsInTests}, and earlier ones, such as 2025.3, with {@code setActiveEditors}.
     */
    private static void setActiveEditors(EditorTracker editors, List<Editor> active) throws Exception {
        Method setter;
        try {
            setter = EditorTracker.class.getMethod("setActiveEditorsInTests", List.class);
        } catch (NoSuchMethodException earlierVersion) {
            setter = EditorTracker.class.getMethod("setActiveEditors", List.class);
        }
        setter.invoke(editors, active);
    }

    /**
     * Returns the semantic highlights among the given ones, as "start-end:key", in the order of their offsets.
     */
    private static List<String> semanticHighlights(List<HighlightInfo> highlights) {
        return highlights
                .stream()
                .filter(info -> info.forcedTextAttributesKey != null
                        && info.forcedTextAttributesKey.getExternalName().startsWith("LSP_"))
                .map(info -> info.getStartOffset() + "-" + info.getEndOffset() + ":"
                        + info.forcedTextAttributesKey.getExternalName())
                .sorted()
                .toList();
    }
}
