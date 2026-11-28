/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.semanticTokens;

import com.intellij.psi.PsiFile;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.redhat.devtools.lsp4ij.LSPRequestConstants;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.features.AbstractLSPDocumentFeatureSupport;
import com.redhat.devtools.lsp4ij.internal.CancellationSupport;
import com.redhat.devtools.lsp4ij.internal.CancellationUtil;
import com.redhat.devtools.lsp4ij.internal.CompletableFutures;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.isDoneNormally;

/**
 * LSP semanticTokens support which loads and caches semantic tokens by consuming:
 *
 * <ul>
 *     <li>LSP 'textDocument/semanticTokens' requests</li>
 * </ul>
 *
 * <p>
 * A request that no language server answered, because it was cancelled, by the client or by the language server
 * (ServerCancelled, ContentModified), completes exceptionally, and is not kept: the next highlighting pass asks again.
 * A request that a language server answered with an error completes exceptionally too, and is kept until the file
 * changes, so that its error isn't reported again at each restart of the highlighting. In both cases, the highlighting
 * shows the semantic tokens it showed last, which this support keeps, see {@link #getShownTokens()}.
 * </p>
 */
public class LSPSemanticTokensSupport extends AbstractLSPDocumentFeatureSupport<SemanticTokensParams, SemanticTokensData> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LSPSemanticTokensSupport.class);

    private static final SemanticTokensLegend DEFAULT_LEGEND;

    // How many times a request that the language server cancelled itself, or whose content it found modified, is sent
    // again, and the delay before the first time, which doubles each time
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MILLIS = 100;

    static {
        DEFAULT_LEGEND = new SemanticTokensLegend();
        DEFAULT_LEGEND.setTokenModifiers(Collections.emptyList());
        DEFAULT_LEGEND.setTokenTypes(Collections.emptyList());
    }

    /**
     * The semantic tokens that the highlighting of the file shows, with the text of the file they were decoded for.
     *
     * @param data the semantic tokens.
     * @param text the text of the file that the semantic tokens were decoded for.
     */
    @ApiStatus.Internal
    public record ShownSemanticTokens(@NotNull SemanticTokensData data, @NotNull CharSequence text) {
    }

    // The semantic tokens that the highlighting of the file shows, which it shows again when a request has no answer
    private volatile @Nullable ShownSemanticTokens shownTokens;

    public LSPSemanticTokensSupport(@NotNull PsiFile file) {
        super(file);
    }

    public CompletableFuture<SemanticTokensData> getSemanticTokens(SemanticTokensParams params) {
        return super.getFeatureData(params);
    }

    /**
     * Returns the semantic tokens that the highlighting of the file shows, and null when it shows none.
     *
     * @return the semantic tokens that the highlighting of the file shows, and null when it shows none.
     */
    @ApiStatus.Internal
    public @Nullable ShownSemanticTokens getShownTokens() {
        return shownTokens;
    }

    /**
     * Sets the semantic tokens that the highlighting of the file shows: the answer of a language server, or null when
     * the language servers answered that the file has none.
     *
     * @param shownTokens the semantic tokens that the highlighting of the file shows, or null.
     */
    @ApiStatus.Internal
    public void setShownTokens(@Nullable ShownSemanticTokens shownTokens) {
        this.shownTokens = shownTokens;
    }

    @Override
    protected CompletableFuture<SemanticTokensData> doLoad(SemanticTokensParams params, CancellationSupport cancellationSupport) {
        PsiFile file = super.getFile();
        return getSemanticTokens(file, params, cancellationSupport);
    }

    private static @NotNull CompletableFuture<SemanticTokensData> getSemanticTokens(@NotNull PsiFile file,
                                                                                    @NotNull SemanticTokensParams params,
                                                                                    @NotNull CancellationSupport cancellationSupport) {

        return getLanguageServers(file,
                f -> f.getSemanticTokensFeature().isEnabled(file),
                f -> f.getSemanticTokensFeature().isSupported(file))
                .thenComposeAsync(languageServers -> {
                    // Here languageServers is the list of language servers which matches the given file
                    // and which have semantic tokens capability
                    if (languageServers.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    // The semantic tokens of the first language server
                    return getSemanticTokensFor(params, file, languageServers.get(0), cancellationSupport)
                            .thenApply(answer -> switch (answer.status()) {
                                case ANSWERED -> answer.data();
                                // Kept until the file changes, so that the error isn't asked for, and reported, again
                                // at each restart of the highlighting
                                case ERROR -> throw new ErrorAnswerException();
                                // Not kept, so that the next highlighting pass asks again
                                case NO_ANSWER -> throw CompletableFutures.noAnswer();
                            });
                });
    }

    /**
     * Keeps a request that a language server answered with an error until the file changes, as an answer is kept, so
     * that the next restarts of the highlighting, which other features ask for too, don't send it again, and report its
     * error again. A request that no language server answered is not kept.
     */
    @Override
    protected boolean isValidLSPFuture() {
        return super.isValidLSPFuture() || (isErrorAnswer(getFuture()) && checkValid());
    }

    /**
     * Returns true if the given future of semantic tokens failed because a language server answered with an error, and
     * false otherwise.
     */
    private static boolean isErrorAnswer(@Nullable CompletableFuture<?> future) {
        if (future == null || !future.isCompletedExceptionally() || future.isCancelled()) {
            return false;
        }
        try {
            future.join();
            return false;
        } catch (CompletionException e) {
            return ExceptionUtil.causedBy(e, ErrorAnswerException.class);
        } catch (CancellationException e) {
            return false;
        }
    }

    /**
     * The failure of a request that a language server answered with an error, which the support keeps until the file
     * changes. Its cause is the failure of a request that no language server answered, which
     * CompletableFutures.awaitWithCheckCanceled logs at the debug level only: {@link CancellationSupport} has reported
     * the error.
     */
    private static final class ErrorAnswerException extends RuntimeException {

        private ErrorAnswerException() {
            // No stack trace: the failure is expected
            super("A language server answered with an error", CompletableFutures.noAnswer(), false, false);
        }
    }

    /**
     * How a language server answered a semantic tokens request.
     */
    private enum AnswerStatus {
        /**
         * The language server answered, with semantic tokens or with null.
         */
        ANSWERED,
        /**
         * The language server answered with an error, which is kept until the file changes.
         */
        ERROR,
        /**
         * The request was cancelled, by the client, or by the language server (ServerCancelled, ContentModified), or
         * failed without an answer of the language server: the next highlighting pass asks again.
         */
        NO_ANSWER
    }

    /**
     * The answer of a language server to a semantic tokens request.
     *
     * @param data   the semantic tokens that the language server answered, or null when it answered null, or didn't
     *               answer.
     * @param status how the language server answered.
     */
    private record ServerAnswer(@Nullable SemanticTokensData data, @NotNull AnswerStatus status) {

        private static final ServerAnswer ERROR = new ServerAnswer(null, AnswerStatus.ERROR);
        private static final ServerAnswer NO_ANSWER = new ServerAnswer(null, AnswerStatus.NO_ANSWER);
    }

    private static CompletableFuture<ServerAnswer> getSemanticTokensFor(@NotNull SemanticTokensParams params,
                                                                        @NotNull PsiFile file,
                                                                        @NotNull LanguageServerItem languageServer,
                                                                        @NotNull CancellationSupport cancellationSupport) {
        // The parameters of this language server, with the Uri of the file it knows, which its retries reuse
        var serverParams = new SemanticTokensParams(new TextDocumentIdentifier());
        serverParams.setWorkDoneToken(params.getWorkDoneToken());
        serverParams.setPartialResultToken(params.getPartialResultToken());
        // Update textDocument Uri with custom file Uri if needed
        updateTextDocumentUri(serverParams.getTextDocument(), file, languageServer);
        return getSemanticTokensFor(serverParams, languageServer, cancellationSupport, 0);
    }

    /**
     * Sends the given semantic tokens request to the given language server, and sends it again, after a delay which
     * doubles each time, at most {@value #MAX_RETRIES} times, while the language server cancels it itself
     * (ServerCancelled), as the client declares <code>serverCancelSupport</code>, or because its content was modified
     * (ContentModified): the LSP specification asks the client to send such a request again.
     */
    private static CompletableFuture<ServerAnswer> getSemanticTokensFor(@NotNull SemanticTokensParams params,
                                                                        @NotNull LanguageServerItem languageServer,
                                                                        @NotNull CancellationSupport cancellationSupport,
                                                                        int retries) {
        CompletableFuture<SemanticTokens> request;
        CompletableFuture<SemanticTokens> answer;
        try {
            request = languageServer
                    .getTextDocumentService()
                    .semanticTokensFull(params);
            answer = cancellationSupport.execute(request, languageServer, LSPRequestConstants.TEXT_DOCUMENT_SEMANTIC_TOKENS_FULL);
        } catch (CancellationException e) {
            // The request has been cancelled before it was sent (a ProcessCanceledException is one)
            return CompletableFuture.completedFuture(ServerAnswer.NO_ANSWER);
        } catch (RuntimeException e) {
            // The request could not be sent, as when the language server stopped meanwhile: the next pass asks again
            LOGGER.debug("Could not send a semantic tokens request to the language server '{}'",
                    languageServer.getServerDefinition().getDisplayName(), e);
            return CompletableFuture.completedFuture(ServerAnswer.NO_ANSWER);
        }
        return answer.handleAsync((semanticTokens, error) -> {
            if (error != null || !isDoneNormally(request)) {
                // The language server hasn't answered: CancellationSupport has reported its error, if any,
                // and completed with null instead
                if (retries < MAX_RETRIES && isToBeSentAgain(request) && !cancellationSupport.isCanceled()) {
                    return sendAgain(params, languageServer, cancellationSupport, retries);
                }
                return CompletableFuture.completedFuture(isAnsweredWithError(request) ? ServerAnswer.ERROR : ServerAnswer.NO_ANSWER);
            }
            if (semanticTokens == null) {
                // textDocument/semanticTokens/full may return null
                return CompletableFuture.completedFuture(new ServerAnswer(null, AnswerStatus.ANSWERED));
            }
            return CompletableFuture.completedFuture(new ServerAnswer(new SemanticTokensData(semanticTokens,
                    getLegend(languageServer),
                    languageServer.getSemanticTokensColorsProvider(),
                    languageServer.getClientFeatures().getSemanticTokensFeature()), AnswerStatus.ANSWERED));
        }).thenCompose(Function.identity());
    }

    /**
     * Sends the given semantic tokens request again, once the delay of the given retry is over, unless the client has
     * cancelled it meanwhile, as an edit or a refresh does, whose highlighting asks again itself.
     */
    private static CompletableFuture<ServerAnswer> sendAgain(@NotNull SemanticTokensParams params,
                                                             @NotNull LanguageServerItem languageServer,
                                                             @NotNull CancellationSupport cancellationSupport,
                                                             int retries) {
        var answer = new CompletableFuture<ServerAnswer>();
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (cancellationSupport.isCanceled()) {
                answer.complete(ServerAnswer.NO_ANSWER);
                return;
            }
            getSemanticTokensFor(params, languageServer, cancellationSupport, retries + 1)
                    .whenComplete((serverAnswer, error) -> answer.complete(error == null ? serverAnswer : ServerAnswer.NO_ANSWER));
        }, RETRY_DELAY_MILLIS << retries, TimeUnit.MILLISECONDS);
        return answer;
    }

    /**
     * Returns true if the given failed request is to be sent again: the language server cancelled it itself
     * (ServerCancelled), or answered that its content was modified (ContentModified), and false otherwise.
     */
    private static boolean isToBeSentAgain(@NotNull CompletableFuture<?> request) {
        if (!request.isCompletedExceptionally() || request.isCancelled()) {
            return false;
        }
        try {
            request.join();
            return false;
        } catch (CompletionException e) {
            return e.getCause() instanceof ResponseErrorException error
                    && (CancellationUtil.isServerCancelled(error) || CancellationUtil.isContentModified(error));
        } catch (CancellationException e) {
            return false;
        }
    }

    /**
     * Returns true if the language server answered the given failed request with an error other than a cancellation
     * (RequestCancelled, ServerCancelled) or ContentModified, and false otherwise: a cancelled request, or one that
     * failed without an answer of the language server, as when it stopped, is asked again.
     */
    private static boolean isAnsweredWithError(@NotNull CompletableFuture<?> request) {
        if (!request.isCompletedExceptionally() || request.isCancelled()) {
            return false;
        }
        try {
            request.join();
            return false;
        } catch (CompletionException e) {
            return e.getCause() instanceof ResponseErrorException error
                    && !CancellationUtil.isRequestCancelled(error)
                    && !CancellationUtil.isContentModified(error);
        } catch (CancellationException e) {
            return false;
        }
    }

    @NotNull
    private static SemanticTokensLegend getLegend(LanguageServerItem languageServer) {
        var semanticTokenFeature = languageServer.getClientFeatures().getSemanticTokensFeature();
        SemanticTokensLegend legend = semanticTokenFeature.getLegend();
        if (legend != null) {
            return legend;
        }
        var serverCapabilities = languageServer.getServerCapabilities();
        if (serverCapabilities != null &&
                serverCapabilities.getSemanticTokensProvider() != null &&
                serverCapabilities.getSemanticTokensProvider().getLegend() != null) {
            return serverCapabilities.getSemanticTokensProvider().getLegend();
        }
        return DEFAULT_LEGEND;
    }

}
