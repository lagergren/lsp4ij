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
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.services.LanguageClient;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A language server's <code>workspace/semanticTokens/refresh</code> shows its new semantic tokens without an edit,
 * promptly: it doesn't wait for the debounce of the restarts of the highlighting that other features ask for, and the
 * refreshes of other features can't postpone it. While its answer is pending, the file keeps its colors, and a write
 * action runs. The daemon of the test highlights the editor as the IDE does, by its timer, and the test waits for the
 * new tokens to show.
 */
public class SemanticTokensRefreshTest extends LSPCodeInsightFixtureTestCase {

    private static final String FILE_NAME = "test.luax";

    // Only ends a hang
    private static final long HANG_SECONDS = 30;

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

    public void testARefreshDoesNotWaitForTheDebounceOfOtherRestarts() throws Exception {
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights(myFixture.doHighlighting()));
        // The restarts that other features ask for wait so long that the test can't pass by waiting for them
        LSPFileSupport.getSupport(myFixture.getFile()).setRestartDebounce(Duration.ofMinutes(10));

        whileTheDaemonRuns(() -> {
            client().refreshSemanticTokens();
            waitForTheNewTokens();
        });
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

    public void testAPendingAnswerKeepsTheColorsAndLetsAWriteActionRun() throws Exception {
        var answer = new CompletableFuture<SemanticTokens>();
        MockLanguageServer.INSTANCE.getTextDocumentService().setSemanticTokensProcessor(request ->
                request.number() == 1 ? CompletableFuture.completedFuture(TOKENS) : answer);
        // Only ends a hang: a write action that waits for the answer gets it then, and the test fails
        var hang = AppExecutorUtil.getAppScheduledExecutorService()
                .schedule(() -> answer.complete(NEW_TOKENS), HANG_SECONDS, TimeUnit.SECONDS);
        myFixture.configureByText(FILE_NAME, TEXT);
        assertEquals(List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), semanticHighlights(myFixture.doHighlighting()));

        try {
            whileTheDaemonRuns(() -> {
                client().refreshSemanticTokens();
                var requests = MockLanguageServer.INSTANCE.getTextDocumentService().getSemanticTokensRequests();
                PlatformTestUtil.waitWithEventsDispatching("the refreshed highlighting must ask for the semantic tokens",
                        () -> requests.size() == 2, IN_MEMORY_TIMEOUT_SECONDS);
                assertEquals("The colors stay while the answer is pending",
                        List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), currentSemanticHighlights());

                // The highlighting waits for the answer in a read action, which a write action cancels
                long start = System.nanoTime();
                WriteAction.run(() -> {});
                long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertFalse("The write action waited for the answer", answer.isDone());
                assertTrue("The write action waited " + waited + " ms", waited < TimeUnit.SECONDS.toMillis(HANG_SECONDS));
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                assertEquals("The colors stay after the write action",
                        List.of("0-3:LSP_FUNCTION", "4-7:LSP_KEYWORD"), currentSemanticHighlights());

                // The answer shows without an edit
                answer.complete(NEW_TOKENS);
                waitForTheNewTokens();
            });
        } finally {
            hang.cancel(false);
        }
    }

    private void waitForTheNewTokens() {
        PlatformTestUtil.waitWithEventsDispatching("the refresh must show the new semantic tokens without an edit",
                () -> List.of("4-7:LSP_FUNCTION").equals(currentSemanticHighlights()), IN_MEMORY_TIMEOUT_SECONDS);
    }

    /**
     * Returns the semantic highlights that the editor shows.
     */
    private List<String> currentSemanticHighlights() {
        var document = myFixture.getEditor().getDocument();
        return semanticHighlights(DaemonCodeAnalyzerImpl.getHighlights(document, null, myFixture.getProject()));
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
