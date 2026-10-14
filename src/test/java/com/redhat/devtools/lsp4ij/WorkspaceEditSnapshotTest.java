/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps request-time state separate from a delayed response without racing test threads.
 */
public class WorkspaceEditSnapshotTest extends LSPCodeInsightFixtureTestCase {

    public WorkspaceEditSnapshotTest() {
        super("*.foo");
    }

    private LanguageServerWrapper open() throws Exception {
        myFixture.configureByText("test.foo", "before");
        return LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(myFixture.getFile(), null, null)
                .get(5, TimeUnit.SECONDS).get(0).getServerWrapper();
    }

    private static WorkspaceEdit replacement(String uri) {
        return new WorkspaceEdit(Map.of(uri,
                List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 6)), "after"))));
    }

    private ApplyWorkspaceEditResponse await(CompletableFuture<ApplyWorkspaceEditResponse> future) {
        PlatformTestUtil.waitWithEventsDispatching("workspace edit must complete", future::isDone, 5);
        return future.join();
    }

    public void testChangedAndRestoredTextStillInvalidatesRequest() throws Exception {
        var wrapper = open();
        var snapshot = WorkspaceEditSnapshot.capture(wrapper);
        String uri = wrapper.toUri(myFixture.getFile().getVirtualFile()).toString();
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> {
            myFixture.getEditor().getDocument().setText("changed");
            myFixture.getEditor().getDocument().setText("before");
        });
        PsiDocumentManager.getInstance(myFixture.getProject()).commitAllDocuments();

        assertFalse(await(snapshot.apply(replacement(uri), "Rename")).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
    }

    public void testReopenDoesNotReuseRequestOwnership() throws Exception {
        var wrapper = open();
        var snapshot = WorkspaceEditSnapshot.capture(wrapper);
        String uri = wrapper.toUri(myFixture.getFile().getVirtualFile()).toString();
        wrapper.disconnect(URI.create(uri), false);
        LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(myFixture.getFile(), null, null).get(5, TimeUnit.SECONDS);

        assertFalse(await(snapshot.apply(replacement(uri), "Rename")).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
    }

    public void testTextEditAfterDirectoryRenameUsesOriginalDocumentVersion() throws Exception {
        var wrapper = open();
        var file = myFixture.addFileToProject("old/other.foo", "before");
        LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(file, null, null).get(5, TimeUnit.SECONDS);
        URI uri = wrapper.toUri(file.getVirtualFile());
        String oldDirectory = uri.resolve(".").toString();
        String newDirectory = uri.resolve("../new/").toString();
        String newUri = newDirectory + "other.foo";
        var snapshot = WorkspaceEditSnapshot.capture(wrapper);
        int version = wrapper.getOpenedDocument(uri).getSynchronizer().getVersion();
        var text = new TextDocumentEdit(new VersionedTextDocumentIdentifier(newUri, version),
                List.of(Either.forLeft(new TextEdit(new Range(new Position(0, 0), new Position(0, 6)), "after"))));

        // Directory URIs may include a trailing slash on either side of the move.
        for (String oldUri : List.of(oldDirectory, oldDirectory.substring(0, oldDirectory.length() - 1))) {
            for (String targetUri : List.of(newDirectory, newDirectory.substring(0, newDirectory.length() - 1))) {
                var edit = new WorkspaceEdit(List.of(Either.forRight(new RenameFile(oldUri, targetUri)), Either.forLeft(text)));
                WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> assertNull(snapshot.validate(edit)));
            }
        }
        text.getTextDocument().setVersion(version + 1);
        var stale = new WorkspaceEdit(List.of(Either.forRight(new RenameFile(oldDirectory, newDirectory)), Either.forLeft(text)));
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> assertNotNull(snapshot.validate(stale)));
        assertEquals("before", file.getText());
    }

    public void testStoppedConnectionCannotApplyItsResponse() throws Exception {
        var wrapper = open();
        var snapshot = WorkspaceEditSnapshot.capture(wrapper);
        String uri = wrapper.toUri(myFixture.getFile().getVirtualFile()).toString();
        wrapper.stop().get(5, TimeUnit.SECONDS);

        assertFalse(await(snapshot.apply(replacement(uri), "Rename")).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
    }

    public void testTargetOpenedAfterRequestIsRefused() throws Exception {
        var wrapper = open();
        var other = myFixture.addFileToProject("other.foo", "before");
        var snapshot = WorkspaceEditSnapshot.capture(wrapper);
        LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(other, null, null).get(5, TimeUnit.SECONDS);
        var document = LSPIJUtils.getDocument(other.getVirtualFile());
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> document.setText("new typing"));

        assertFalse(await(snapshot.apply(replacement(wrapper.toUri(other.getVirtualFile()).toString()), "Rename")).isApplied());
        assertEquals("new typing", document.getText());
    }
}
