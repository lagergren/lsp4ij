/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.psi.PsiFile;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.redhat.devtools.lsp4ij.client.CoalesceByKey;
import com.redhat.devtools.lsp4ij.features.callHierarchy.LSPCallHierarchyIncomingCallsSupport;
import com.redhat.devtools.lsp4ij.features.callHierarchy.LSPCallHierarchyOutgoingCallsSupport;
import com.redhat.devtools.lsp4ij.features.callHierarchy.LSPPrepareCallHierarchySupport;
import com.redhat.devtools.lsp4ij.features.codeAction.intention.LSPIntentionCodeActionSupport;
import com.redhat.devtools.lsp4ij.features.codeLens.LSPCodeLensSupport;
import com.redhat.devtools.lsp4ij.features.color.LSPColorSupport;
import com.redhat.devtools.lsp4ij.features.color.LSPColorPresentationSupport;
import com.redhat.devtools.lsp4ij.features.completion.LSPCompletionSupport;
import com.redhat.devtools.lsp4ij.features.declaration.LSPDeclarationSupport;
import com.redhat.devtools.lsp4ij.features.inlineCompletion.LSPInlineCompletionSupport;
import com.redhat.devtools.lsp4ij.features.documentLink.LSPDocumentLinkSupport;
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolSupport;
import com.redhat.devtools.lsp4ij.features.documentation.LSPHoverSupport;
import com.redhat.devtools.lsp4ij.features.foldingRange.LSPFoldingRangeSupport;
import com.redhat.devtools.lsp4ij.features.formatting.LSPFormattingSupport;
import com.redhat.devtools.lsp4ij.features.formatting.LSPOnTypeFormattingSupport;
import com.redhat.devtools.lsp4ij.features.highlight.LSPHighlightSupport;
import com.redhat.devtools.lsp4ij.features.implementation.LSPImplementationSupport;
import com.redhat.devtools.lsp4ij.features.inlayhint.LSPInlayHintsSupport;
import com.redhat.devtools.lsp4ij.features.navigation.LSPDefinitionSupport;
import com.redhat.devtools.lsp4ij.features.references.LSPReferenceSupport;
import com.redhat.devtools.lsp4ij.features.rename.LSPPrepareRenameSupport;
import com.redhat.devtools.lsp4ij.features.rename.LSPRenameSupport;
import com.redhat.devtools.lsp4ij.features.selectionRange.LSPSelectionRangeSupport;
import com.redhat.devtools.lsp4ij.features.semanticTokens.LSPSemanticTokensSupport;
import com.redhat.devtools.lsp4ij.features.signatureHelp.LSPSignatureHelpSupport;
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionSupport;
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPPrepareTypeHierarchySupport;
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPTypeHierarchySubtypesSupport;
import com.redhat.devtools.lsp4ij.features.typeHierarchy.LSPTypeHierarchySupertypesSupport;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * LSP file support stored in the opened {@link PsiFile} with key "lsp.file.support"
 * which manages and caches LSP textDocument requests like codeLens, inlayHint, color futures, etc.
 */
@ApiStatus.Internal
public class LSPFileSupport extends UserDataHolderBase implements Disposable {

    public static final @NotNull CancelChecker NO_CANCELLABLE_CHECKER = () -> {
    };
    private static final Key<LSPFileSupport> LSP_FILE_SUPPORT_KEY = Key.create("lsp.file.support");
    /**
     * How long a restart of the highlighting of the file waits at most for more requests to restart it, which it then
     * runs for too, in milliseconds.
     */
    private static final long RESTART_DEBOUNCE_MILLIS = 2000;
    /**
     * How long a restart of the highlighting of the file that a language server's
     * <code>workspace/semanticTokens/refresh</code> asks for waits at most, in milliseconds: the semantic tokens of a
     * visible editor are to be refreshed promptly, which is about as long as the highlighting waits after typing.
     */
    private static final long SEMANTIC_TOKENS_REFRESH_DELAY_MILLIS = 300;
    private final PsiFile file;
    private final LSPCodeLensSupport codeLensSupport;
    private final LSPInlayHintsSupport inlayHintsSupport;
    private final LSPColorSupport colorSupport;
    private final LSPColorPresentationSupport colorPresentationSupport;
    private final LSPFoldingRangeSupport foldingRangeSupport;
    private final LSPSelectionRangeSupport selectionRangeSupport;
    private final LSPFormattingSupport formattingSupport;
    private final LSPOnTypeFormattingSupport onTypeFormattingSupport;
    private final LSPHighlightSupport highlightSupport;
    private final LSPSignatureHelpSupport signatureHelpSupport;
    private final LSPDocumentLinkSupport documentLinkSupport;
    private final LSPHoverSupport hoverSupport;
    private final LSPIntentionCodeActionSupport intentionCodeActionSupport;
    private final LSPPrepareRenameSupport prepareRenameSupport;
    private final LSPRenameSupport renameSupport;
    private final LSPCompletionSupport completionSupport;
    private final LSPInlineCompletionSupport inlineCompletionSupport;
    private final LSPImplementationSupport implementationSupport;
    private final LSPReferenceSupport referenceSupport;
    private final LSPDefinitionSupport definitionSupport;
    private final LSPDeclarationSupport declarationSupport;
    private final LSPTypeDefinitionSupport typeDefinitionSupport;
    private final LSPSemanticTokensSupport semanticTokensSupport;
    private final LSPDocumentSymbolSupport documentSymbolSupport;
    private final LSPPrepareCallHierarchySupport prepareCallHierarchySupport;
    private final LSPCallHierarchyIncomingCallsSupport callHierarchyIncomingCallsSupport;
    private final LSPCallHierarchyOutgoingCallsSupport callHierarchyOutgoingCallsSupport;
    private final LSPPrepareTypeHierarchySupport prepareTypeHierarchySupport;
    private final LSPTypeHierarchySubtypesSupport typeHierarchySubtypesSupport;
    private final LSPTypeHierarchySupertypesSupport typeHierarchySupertypesSupport;
    private final List<Key<?>> keys = new ArrayList<>();
    private volatile Alarm refreshPsiFileAlarm = null;
    // How long a restart of the highlighting of the file waits for more requests, which only a test changes
    private volatile long restartDebounceMillis = RESTART_DEBOUNCE_MILLIS;
    // Whether a restart of the highlighting of the file is pending, when it runs, in System.nanoTime(), and the cancel
    // checkers of the requests that it runs for, guarded by this. The restart is pending until it takes its requests:
    // a request that comes later, even while the restart runs, asks for a restart of its own
    private boolean restartPending;
    private long pendingRestartTime;
    private final List<CancelChecker> pendingRestartCancelCheckers = new ArrayList<>();
    // Runs once a restart has taken its requests and submitted the restart, which only a test sets
    private volatile @Nullable Runnable restartTakenHook;
    private boolean disposed;

    private LSPFileSupport(@NotNull PsiFile file) {
        this.file = file;
        this.codeLensSupport = new LSPCodeLensSupport(file);
        this.inlayHintsSupport = new LSPInlayHintsSupport(file);
        this.colorSupport = new LSPColorSupport(file);
        this.colorPresentationSupport = new LSPColorPresentationSupport(file);
        this.foldingRangeSupport = new LSPFoldingRangeSupport(file);
        this.selectionRangeSupport = new LSPSelectionRangeSupport(file);
        this.formattingSupport = new LSPFormattingSupport(file);
        this.onTypeFormattingSupport = new LSPOnTypeFormattingSupport(file);
        this.highlightSupport = new LSPHighlightSupport(file);
        this.signatureHelpSupport = new LSPSignatureHelpSupport(file);
        this.documentLinkSupport = new LSPDocumentLinkSupport(file);
        this.hoverSupport = new LSPHoverSupport(file);
        this.intentionCodeActionSupport = new LSPIntentionCodeActionSupport(file);
        this.prepareRenameSupport = new LSPPrepareRenameSupport(file);
        this.renameSupport = new LSPRenameSupport(file);
        this.completionSupport = new LSPCompletionSupport(file);
        this.inlineCompletionSupport = new LSPInlineCompletionSupport(file);
        this.implementationSupport = new LSPImplementationSupport(file);
        this.referenceSupport = new LSPReferenceSupport(file);
        this.definitionSupport = new LSPDefinitionSupport(file);
        this.declarationSupport = new LSPDeclarationSupport(file);
        this.typeDefinitionSupport = new LSPTypeDefinitionSupport(file);
        this.semanticTokensSupport = new LSPSemanticTokensSupport(file);
        this.documentSymbolSupport = new LSPDocumentSymbolSupport(file);
        this.prepareCallHierarchySupport = new LSPPrepareCallHierarchySupport(file);
        this.callHierarchyIncomingCallsSupport = new LSPCallHierarchyIncomingCallsSupport(file);
        this.callHierarchyOutgoingCallsSupport = new LSPCallHierarchyOutgoingCallsSupport(file);
        this.prepareTypeHierarchySupport = new LSPPrepareTypeHierarchySupport(file);
        this.typeHierarchySubtypesSupport = new LSPTypeHierarchySubtypesSupport(file);
        this.typeHierarchySupertypesSupport = new LSPTypeHierarchySupertypesSupport(file);
        file.putUserData(LSP_FILE_SUPPORT_KEY, this);
        // When project is disposed, we dispose the LSP file support
        Disposer.register(file.getProject(), this);

    }

    /**
     * Return the existing LSP file support for the given Psi file, or create a new one if necessary.
     *
     * @param file the Psi file.
     * @return the existing LSP file support for the given Psi file, or create a new one if necessary.
     */
    public static @NotNull LSPFileSupport getSupport(@NotNull PsiFile file) {
        LSPFileSupport support = file.getUserData(LSP_FILE_SUPPORT_KEY);
        if (support == null) {
            // create LSP support by taking care of multiple threads which could call it.
            support = createSupport(file);
        }
        return support;
    }

    private synchronized static @NotNull LSPFileSupport createSupport(@NotNull PsiFile file) {
        LSPFileSupport support = file.getUserData(LSP_FILE_SUPPORT_KEY);
        if (support != null) {
            return support;
        }
        return new LSPFileSupport(file);
    }

    /**
     * Returns true if the given Psi file has LSP support and false otherwise.
     *
     * @param file the Psi file.
     * @return true if the given Psi file has LSP support and false otherwise.
     */
    public static boolean hasSupport(@NotNull PsiFile file) {
        return file.getUserData(LSP_FILE_SUPPORT_KEY) != null;
    }

    @Override
    public void dispose() {
        this.disposed = true;
        // cancel all LSP requests
        file.putUserData(LSP_FILE_SUPPORT_KEY, null);
        getCodeLensSupport().cancel();
        getInlayHintsSupport().cancel();
        getColorSupport().cancel();
        getColorPresentationSupport().cancel();
        getFoldingRangeSupport().cancel();
        getSelectionRangeSupport().cancel();
        getFormattingSupport().cancel();
        getOnTypeFormattingSupport().cancel();
        getHighlightSupport().cancel();
        getSignatureHelpSupport().cancel();
        getDocumentLinkSupport().cancel();
        getHoverSupport().cancel();
        getIntentionCodeActionSupport().cancel();
        getPrepareRenameSupport().cancel();
        getRenameSupport().cancel();
        getCompletionSupport().cancel();
        getImplementationSupport().cancel();
        getReferenceSupport().cancel();
        getDeclarationSupport().cancel();
        getTypeDefinitionSupport().cancel();
        getSemanticTokensSupport().cancel();
        getDocumentSymbolSupport().cancel();
        getPrepareCallHierarchySupport().cancel();
        getCallHierarchyIncomingCallsSupport().cancel();
        getCallHierarchyOutgoingCallsSupport().cancel();
        getPrepareTypeHierarchySupport().cancel();
        getTypeHierarchySubtypesSupport().cancel();
        getTypeHierarchySupertypesSupport().cancel();
        for (var key : keys) {
            var value = super.getUserData(key);
            if (value instanceof Disposable disposable) {
                disposable.dispose();
            }
        }
    }

    /**
     * Returns the LSP codeLens support.
     *
     * @return the LSP codeLens support.
     */
    public LSPCodeLensSupport getCodeLensSupport() {
        return codeLensSupport;
    }

    /**
     * Returns the LSP inlayHint support.
     *
     * @return the LSP inlayHint support.
     */
    public LSPInlayHintsSupport getInlayHintsSupport() {
        return inlayHintsSupport;
    }

    /**
     * Returns the LSP color support.
     *
     * @return the LSP color support.
     */
    public LSPColorSupport getColorSupport() {
        return colorSupport;
    }

    /**
     * Returns the LSP color presentation support.
     *
     * @return the LSP color presentation support.
     */
    public LSPColorPresentationSupport getColorPresentationSupport() {
        return colorPresentationSupport;
    }

    /**
     * Returns the LSP folding range support.
     *
     * @return the LSP folding range support.
     */
    public LSPFoldingRangeSupport getFoldingRangeSupport() {
        return foldingRangeSupport;
    }

    /**
     * Returns the LSP selection range support.
     *
     * @return the LSP selection range support.
     */
    public LSPSelectionRangeSupport getSelectionRangeSupport() {
        return selectionRangeSupport;
    }

    /**
     * Returns the LSP formatting support.
     *
     * @return the LSP formatting support.
     */
    public LSPFormattingSupport getFormattingSupport() {
        return formattingSupport;
    }

    /**
     * Returns the LSP on-type formatting support.
     *
     * @return the LSP on-type formatting support.
     */
    public LSPOnTypeFormattingSupport getOnTypeFormattingSupport() {
        return onTypeFormattingSupport;
    }

    /**
     * Returns the LSP highlight support.
     *
     * @return the LSP highlight support.
     */
    public LSPHighlightSupport getHighlightSupport() {
        return highlightSupport;
    }

    /**
     * Returns the LSP signature help support.
     *
     * @return the LSP signature help support.
     */
    public LSPSignatureHelpSupport getSignatureHelpSupport() {
        return signatureHelpSupport;
    }

    /**
     * Returns the LSP document link support.
     *
     * @return the LSP document link support.
     */
    public LSPDocumentLinkSupport getDocumentLinkSupport() {
        return documentLinkSupport;
    }

    /**
     * Returns the LSP hover support.
     *
     * @return the LSP hover support.
     */
    public LSPHoverSupport getHoverSupport() {
        return hoverSupport;
    }

    /**
     * Returns the LSP code action support.
     *
     * @return the LSP code action support.
     */
    public LSPIntentionCodeActionSupport getIntentionCodeActionSupport() {
        return intentionCodeActionSupport;
    }

    /**
     * Returns the LSP prepare rename support.
     *
     * @return the LSP prepare rename support.
     */
    public LSPPrepareRenameSupport getPrepareRenameSupport() {
        return prepareRenameSupport;
    }

    /**
     * Returns the LSP prepare rename support.
     *
     * @return the LSP prepare rename support.
     */
    public LSPRenameSupport getRenameSupport() {
        return renameSupport;
    }

    /**
     * Returns the LSP completion support.
     *
     * @return the LSP completion support.
     */
    public LSPCompletionSupport getCompletionSupport() {
        return completionSupport;
    }

    /**
     * Returns the LSP inline completion support.
     *
     * @return the LSP inline completion support.
     */
    public LSPInlineCompletionSupport getInlineCompletionSupport() {
        return inlineCompletionSupport;
    }

    /**
     * Returns the LSP implementation support.
     *
     * @return the LSP implementation support.
     */
    public LSPImplementationSupport getImplementationSupport() {
        return implementationSupport;
    }

    /**
     * Returns the LSP reference support.
     *
     * @return the LSP reference support.
     */
    public LSPReferenceSupport getReferenceSupport() {
        return referenceSupport;
    }

    /**
     * Returns the LSP definition support.
     *
     * @return the LSP definition support.
     */
    public LSPDefinitionSupport getDefinitionSupport() {
        return definitionSupport;
    }

    /**
     * Returns the LSP declaration support.
     *
     * @return the LSP declaration support.
     */
    public LSPDeclarationSupport getDeclarationSupport() {
        return declarationSupport;
    }

    /**
     * Returns the LSP typeDefinition support.
     *
     * @return the LSP typeDefinition support.
     */
    public LSPTypeDefinitionSupport getTypeDefinitionSupport() {
        return typeDefinitionSupport;
    }

    /**
     * Returns the LSP semantic tokens support.
     *
     * @return the LSP semantic tokens support.
     */
    public LSPSemanticTokensSupport getSemanticTokensSupport() {
        return semanticTokensSupport;
    }

    /**
     * Returns the LSP document symbol support.
     *
     * @return the LSP document symbol support.
     */
    public LSPDocumentSymbolSupport getDocumentSymbolSupport() {
        return documentSymbolSupport;
    }

    /**
     * Returns the LSP prepare call hierarchy support.
     *
     * @return the LSP prepare call hierarchy support.
     */
    public LSPPrepareCallHierarchySupport getPrepareCallHierarchySupport() {
        return prepareCallHierarchySupport;
    }

    /**
     * Returns the LSP call hierarchy incoming calls support.
     *
     * @return the LSP call hierarchy incoming calls support.
     */
    public LSPCallHierarchyIncomingCallsSupport getCallHierarchyIncomingCallsSupport() {
        return callHierarchyIncomingCallsSupport;
    }

    /**
     * Returns the LSP prepare call hierarchy outgoing calls support.
     *
     * @return the LSP prepare call hierarchy outgoing calls support.
     */
    public LSPCallHierarchyOutgoingCallsSupport getCallHierarchyOutgoingCallsSupport() {
        return callHierarchyOutgoingCallsSupport;
    }

    /**
     * Returns the LSP prepare type hierarchy support.
     *
     * @return the LSP prepare type hierarchy support.
     */
    public LSPPrepareTypeHierarchySupport getPrepareTypeHierarchySupport() {
        return prepareTypeHierarchySupport;
    }

    /**
     * Returns the LSP type hierarchy subtypes support.
     *
     * @return the LSP type hierarchy subtypes support.
     */
    public LSPTypeHierarchySubtypesSupport getTypeHierarchySubtypesSupport() {
        return typeHierarchySubtypesSupport;
    }

    /**
     * Returns the LSP type hierarchy supertypes support.
     *
     * @return the LSP type hierarchy supertypes support.
     */
    public LSPTypeHierarchySupertypesSupport getTypeHierarchySupertypesSupport() {
        return typeHierarchySupertypesSupport;
    }

    /**
     * Call 'DaemonCodeAnalyzer.getInstance(project).restart(file);' with debounce.
     */
    public void restartDaemonCodeAnalyzerWithDebounce() {
        restartDaemonCodeAnalyzerWithDebounce(NO_CANCELLABLE_CHECKER);
    }

    /**
     * Call 'DaemonCodeAnalyzer.getInstance(project).restart(file);' with debounce by checking the given cancel checker.
     * <p>
     * The restart waits {@value #RESTART_DEBOUNCE_MILLIS} ms for more requests to restart the highlighting of the file,
     * which it then runs for too. A request never postpones a restart that is pending already, so that requests which
     * come more often than that, as from a language server which refreshes its features while it indexes, can't keep
     * the highlighting from restarting.
     *
     * @param cancelChecker the cancel checker.
     */
    public void restartDaemonCodeAnalyzerWithDebounce(@NotNull CancelChecker cancelChecker) {
        restartDaemonCodeAnalyzer(cancelChecker, restartDebounceMillis);
    }

    /**
     * Call 'DaemonCodeAnalyzer.getInstance(project).restart(file);' within a short delay, for a language server's
     * <code>workspace/semanticTokens/refresh</code>, by checking the given cancel checker: the semantic tokens of a
     * visible editor are to be refreshed promptly. A restart that is pending already runs then.
     *
     * @param cancelChecker the cancel checker.
     */
    @ApiStatus.Internal
    public void restartDaemonCodeAnalyzerForSemanticTokensRefresh(@NotNull CancelChecker cancelChecker) {
        restartDaemonCodeAnalyzer(cancelChecker, SEMANTIC_TOKENS_REFRESH_DELAY_MILLIS);
    }

    /**
     * Restarts the highlighting of the file after the given delay at the latest, with the restart that is pending
     * already when it runs no later, so that a request never postpones the restart that an earlier one asked for.
     *
     * @param cancelChecker the cancel checker of the request.
     * @param delayMillis   how long the restart waits at most, in milliseconds.
     */
    private void restartDaemonCodeAnalyzer(@NotNull CancelChecker cancelChecker, long delayMillis) {
        if (cancelChecker.isCanceled()) {
            return;
        }
        var alarm = getRefreshPsiFileAlarm();
        if (alarm == null) {
            return;
        }
        synchronized (this) {
            long time = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
            pendingRestartCancelCheckers.add(cancelChecker);
            if (restartPending && pendingRestartTime - time <= 0) {
                // The pending restart runs no later than this request asks: it runs for this request too
                return;
            }
            restartPending = true;
            pendingRestartTime = time;
            alarm.cancelAllRequests();
            alarm.addRequest(this::restartDaemonCodeAnalyzerNow, delayMillis);
        }
    }

    private void restartDaemonCodeAnalyzerNow() {
        List<CancelChecker> cancelCheckers;
        synchronized (this) {
            // The restart takes its requests: a later one asks for a restart of its own, which the Alarm, which counts
            // this one until it returns, wouldn't say
            restartPending = false;
            cancelCheckers = List.copyOf(pendingRestartCancelCheckers);
            pendingRestartCancelCheckers.clear();
        }
        // The restart runs if one of its requests still asks for it
        CancelChecker cancelChecker = () -> {
            if (cancelCheckers.stream().allMatch(CancelChecker::isCanceled)) {
                throw new CancellationException();
            }
        };
        var project = file.getProject();
        var coalesceBy = new CoalesceByKey("psiFile/refresh", file.getVirtualFile().getUrl());
        var executeInSmartMode = DumbService.getInstance(project).isDumb();
        var action = ReadAction.nonBlocking((Callable<Void>) () -> {
                    if (!cancelChecker.isCanceled()) {
                        DaemonCodeAnalyzer.getInstance(project).restart(file);
                    }
                    return null;
                }).expireWith(this)
                .coalesceBy(coalesceBy);
        if (executeInSmartMode) {
            action.inSmartMode(project);
        }
        action.submit(AppExecutorUtil.getAppExecutorService());
        var hook = restartTakenHook;
        if (hook != null) {
            hook.run();
        }
    }

    /**
     * Sets how long a restart of the highlighting of this file waits for more requests to restart it, which it then runs
     * with, instead of {@value #RESTART_DEBOUNCE_MILLIS} ms, so that a test can count the restarts that are pending
     * however slowly it runs: they would have run once that time is up.
     *
     * @param debounce how long a restart waits for more requests to restart the highlighting of this file.
     */
    @TestOnly
    public void setRestartDebounce(@NotNull Duration debounce) {
        this.restartDebounceMillis = debounce.toMillis();
    }

    /**
     * Sets what runs once a restart of the highlighting of this file has taken its requests and submitted the restart,
     * while the restart still runs, so that a test can ask for another restart then.
     *
     * @param hook what runs then, or null.
     */
    @TestOnly
    public void setRestartTakenHook(@Nullable Runnable hook) {
        this.restartTakenHook = hook;
    }

    private Alarm getRefreshPsiFileAlarm() {
        if (refreshPsiFileAlarm == null) {
            synchronized (this) {
                // Check disposed inside synchronized to prevent race condition
                if (disposed) {
                    return null;
                }
                if (refreshPsiFileAlarm == null) {
                    refreshPsiFileAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
                }
            }
        }
        return refreshPsiFileAlarm.isDisposed() ? null : refreshPsiFileAlarm;
    }

    public PsiFile getFile() {
        return file;
    }

    @Override
    public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) {
        keys.add(key);
        super.putUserData(key, value);
    }

}
