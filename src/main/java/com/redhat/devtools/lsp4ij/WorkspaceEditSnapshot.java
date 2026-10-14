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
 * Checks that a workspace edit still belongs to the connection and documents it was prepared for.
 * Capture before sending a rename request, then validate the response inside the write command
 * that applies it. For an incoming {@code workspace/applyEdit}, capture when the request arrives.
 * <p>
 * Two independent checks are needed: editor modification stamps detect typing while a reply is
 * pending, and LSP versions identify the text the server used. A matching version alone is not
 * enough when local changes have not yet been synchronized. Synchronizer identity also detects
 * a close/reopen, even if IntelliJ reuses the same {@link Document}.
 * <p>
 * All text targets are checked before any mutation. This prevents partial application due to a
 * stale target. Failures inside {@link LSPIJUtils#applyWorkspaceEdit(WorkspaceEdit)} still follow
 * the existing application behavior, without rollback. An unversioned incoming edit can only
 * be checked for changes since receipt, not before it. Closed documents have no captured buffer
 * state, so unversioned edits to them retain the existing behavior.
 */
public final class WorkspaceEditSnapshot {

    private final LanguageServerWrapper wrapper;
    private final @Nullable LanguageServer server;
    private final BooleanSupplier ownerActive;
    private final Map<URI, DocumentState> documents;

    private WorkspaceEditSnapshot(LanguageServerWrapper wrapper, @Nullable LanguageServer server,
                                  BooleanSupplier ownerActive) {
        this.wrapper = wrapper;
        this.server = server;
        this.ownerActive = ownerActive;
        // Rename replies can edit other files, so the targets are not known at capture time.
        var states = new HashMap<URI, DocumentState>();
        for (var opened : wrapper.getOpenedDocuments()) {
            var synchronizer = opened.getSynchronizer();
            URI uri = wrapper.toUri(opened.getFile());
            if (synchronizer != null && uri != null) {
                Document document = synchronizer.getDocument();
                states.put(uri, new DocumentState(synchronizer, document.getModificationStamp()));
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
        return ReadAction.compute(() -> new WorkspaceEditSnapshot(wrapper, server.getServer(), () -> true));
    }

    /**
     * Also checks the lifetime of the client receiving an edit. Its wrapper can outlive it
     * after a restart, so comparing the wrapper's current server alone is not sufficient.
     */
    public static @NotNull WorkspaceEditSnapshot capture(@NotNull LanguageServerWrapper wrapper,
                                                         @NotNull BooleanSupplier ownerActive) {
        return ReadAction.compute(() -> new WorkspaceEditSnapshot(wrapper, wrapper.getLanguageServer(), ownerActive));
    }

    /**
     * Returns a refusal reason, or {@code null} if the connection and text targets are current.
     * The caller must retain write access from this check through application: releasing it
     * between the two would let typing invalidate a successful check. Validation itself does
     * not mutate files, including when a later target in the edit is stale.
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
                    originalUris.put(withoutTrailingSlash(rename.getNewUri()), withoutTrailingSlash(original));
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

    /**
     * Queues an incoming edit on the EDT and completes with its application or refusal result.
     * Capture and application are deliberately separate: the document may change while queued.
     */
    public @NotNull CompletableFuture<ApplyWorkspaceEditResponse> apply(@NotNull WorkspaceEdit edit,
                                                                       @Nullable String label) {
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
                String commandName = label == null ? LanguageServerBundle.message("lsp.workspace.edit.command") : label;
                // Keep validation and all mutations in one write command, including the refusal path.
                var response = WriteCommandAction.writeCommandAction(wrapper.getProject())
                        .withName(commandName).compute(() -> {
                            String reason = validate(edit);
                            if (reason != null) {
                                return refused(reason);
                            }
                            LSPIJUtils.applyWorkspaceEdit(edit, label);
                            return new ApplyWorkspaceEditResponse(true);
                        });
                result.complete(response);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
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
        // Identity detects close/reopen; the stamp detects changes even if the text was restored.
        if (state != null && !state.matches(synchronizer)) {
            return LanguageServerBundle.message("lsp.workspace.edit.document.changed", uriString);
        }
        // A supplied LSP version must describe the current buffer, including pending local edits.
        if (version != null && (state == null || synchronizer == null
                || !synchronizer.isCurrentVersion(version))) {
            return LanguageServerBundle.message("lsp.workspace.edit.version.changed", uriString);
        }
        // Without a captured state, a newly opened target has no trustworthy request-time stamp.
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
     * Resolves a destination URI back to its location at capture time. For example, after
     * {@code /old -> /new}, a text edit for {@code /new/a} must validate {@code /old/a}.
     * Keys and values have no trailing slash. The slash boundary excludes siblings such as
     * {@code /newer}; the longest prefix handles a later rename of a child directory.
     */
    private static String originalUri(Map<String, String> originalUris, String uri) {
        String destination = originalUris.keySet().stream()
                .filter(candidate -> uri.equals(candidate) || uri.startsWith(candidate + "/"))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (destination == null) {
            return uri;
        }
        return originalUris.get(destination) + uri.substring(destination.length());
    }

    private static String withoutTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private static ApplyWorkspaceEditResponse refused(String reason) {
        var response = new ApplyWorkspaceEditResponse(false);
        response.setFailureReason(reason);
        return response;
    }

    private record DocumentState(DocumentContentSynchronizer synchronizer, long modificationStamp) {
        boolean matches(@Nullable DocumentContentSynchronizer current) {
            return current == synchronizer && synchronizer.getDocument().getModificationStamp() == modificationStamp;
        }
    }
}
