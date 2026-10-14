/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Captures document and connection ownership before requesting an edit. Validation must
 * run in the same write command as application, so a queued reply cannot overwrite a
 * newer buffer or a reopened document whose protocol version happens to match.
 */
public final class WorkspaceEditSnapshot {

    private record DocumentState(DocumentContentSynchronizer synchronizer, Document document, long stamp) {
    }

    private final LanguageServerWrapper wrapper;
    private final LanguageServer server;
    private final BooleanSupplier ownerActive;
    private final Map<URI, DocumentState> documents;

    private WorkspaceEditSnapshot(LanguageServerWrapper wrapper, BooleanSupplier ownerActive) {
        this.wrapper = wrapper;
        this.server = wrapper.getLanguageServer();
        this.ownerActive = ownerActive;
        var states = new HashMap<URI, DocumentState>();
        for (var opened : wrapper.getOpenedDocuments()) {
            var synchronizer = opened.getSynchronizer();
            URI uri = wrapper.toUri(opened.getFile());
            if (synchronizer != null && uri != null) {
                Document document = synchronizer.getDocument();
                states.put(uri, new DocumentState(synchronizer, document, document.getModificationStamp()));
            }
        }
        this.documents = Map.copyOf(states);
    }

    /**
     * Captures document state and the current connection before requesting an edit.
     */
    public static @NotNull WorkspaceEditSnapshot capture(@NotNull LanguageServerWrapper wrapper) {
        return capture(wrapper, () -> true);
    }

    /**
     * Captures state for the exact server selected for a request, even if its wrapper restarts.
     */
    public static @NotNull WorkspaceEditSnapshot capture(@NotNull LanguageServerItem server) {
        var wrapper = server.getServerWrapper();
        return capture(wrapper, () -> wrapper.getLanguageServer() == server.getServer());
    }

    /**
     * Also checks whether the owner requesting or receiving the edit is still active.
     */
    public static @NotNull WorkspaceEditSnapshot capture(@NotNull LanguageServerWrapper wrapper,
                                                         @NotNull BooleanSupplier ownerActive) {
        return ReadAction.compute(() -> new WorkspaceEditSnapshot(wrapper, ownerActive));
    }

    /**
     * Returns a refusal reason, or {@code null} when the entire edit is current.
     * No document is changed by validation, including when a later document is stale.
     */
    public @Nullable String validate(@NotNull WorkspaceEdit edit) {
        ApplicationManager.getApplication().assertWriteAccessAllowed();
        if (!ownerActive.getAsBoolean() || wrapper.isDisposed() || wrapper.getProject().isDisposed()
                || server == null || wrapper.getLanguageServer() != server) {
            return LanguageServerBundle.message("lsp.workspace.edit.connection.changed");
        }
        if (edit.getDocumentChanges() != null) {
            // Resource edits may rename a document before its following text edit. Versions
            // and request ownership still belong to the original synchronized URI.
            var originalUris = new HashMap<String, String>();
            for (var change : edit.getDocumentChanges()) {
                if (change.isLeft()) {
                    TextDocumentEdit text = change.getLeft();
                    String uri = text.getTextDocument().getUri();
                    String reason = validateDocument(originalUri(originalUris, uri), text.getTextDocument().getVersion());
                    if (reason != null) {
                        return reason;
                    }
                } else if (change.getRight() instanceof RenameFile rename) {
                    String original = originalUri(originalUris, rename.getOldUri());
                    String reason = validateDocument(original, null);
                    if (reason != null) {
                        return reason;
                    }
                    originalUris.put(rename.getNewUri(), original);
                }
            }
        } else if (edit.getChanges() != null) {
            for (String uri : edit.getChanges().keySet()) {
                String reason = validateDocument(uri, null);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    // Later text edits can refer to a file or directory moved by an earlier resource operation.
    private static String originalUri(Map<String, String> aliases, String uri) {
        String prefix = aliases.keySet().stream()
                .filter(candidate -> uri.equals(withoutTrailingSlash(candidate))
                        || uri.startsWith(withoutTrailingSlash(candidate) + "/"))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (prefix == null) {
            return uri;
        }
        return withoutTrailingSlash(aliases.get(prefix)) + uri.substring(withoutTrailingSlash(prefix).length());
    }

    private static String withoutTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private @Nullable String validateDocument(String uriString, @Nullable Integer version) {
        URI uri;
        try {
            uri = URI.create(uriString);
        } catch (IllegalArgumentException exception) {
            return LanguageServerBundle.message("lsp.workspace.edit.invalid.uri", uriString);
        }
        var opened = wrapper.getOpenedDocument(uri);
        var synchronizer = opened == null ? null : opened.getSynchronizer();
        var state = documents.get(uri);
        if (state != null && (synchronizer != state.synchronizer()
                || state.document().getModificationStamp() != state.stamp())) {
            return LanguageServerBundle.message("lsp.workspace.edit.document.changed", uriString);
        }
        if (version != null && (state == null || synchronizer == null
                || !synchronizer.isCurrentVersion(version))) {
            return LanguageServerBundle.message("lsp.workspace.edit.version.changed", uriString);
        }
        var file = LSPIJUtils.findResourceFor(uriString);
        if (state == null && (synchronizer != null
                || (file != null && LSPIJUtils.editorsForFile(file, wrapper.getProject()).length != 0))) {
            return LanguageServerBundle.message("lsp.workspace.edit.document.opened", uriString);
        }
        if (file != null && !file.isWritable()) {
            return LanguageServerBundle.message("lsp.workspace.edit.read.only", uriString);
        }
        return null;
    }

    /**
     * Applies the edit in a write command, reporting refusal if any target is stale.
     */
    public @NotNull CompletableFuture<ApplyWorkspaceEditResponse> apply(@NotNull WorkspaceEdit edit, @Nullable String label) {
        var result = new CompletableFuture<ApplyWorkspaceEditResponse>();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (result.isCancelled()) {
                return;
            }
            if (wrapper.getProject().isDisposed()) {
                result.complete(refused(LanguageServerBundle.message("lsp.workspace.edit.connection.changed")));
                return;
            }
            try {
                WriteCommandAction.writeCommandAction(wrapper.getProject())
                        .withName(label == null ? LanguageServerBundle.message("lsp.workspace.edit.command") : label).run(() -> {
                            String reason = validate(edit);
                            if (reason != null) {
                                result.complete(refused(reason));
                            } else {
                                LSPIJUtils.applyWorkspaceEdit(edit, label);
                                result.complete(new ApplyWorkspaceEditResponse(true));
                            }
                        });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    private static ApplyWorkspaceEditResponse refused(String reason) {
        var response = new ApplyWorkspaceEditResponse(false);
        response.setFailureReason(reason);
        return response;
    }
}
