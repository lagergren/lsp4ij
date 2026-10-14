/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
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
 * Exercises workspace/applyEdit through the client, including refusal before any mutation.
 */
public class WorkspaceEditValidationTest extends LSPCodeInsightFixtureTestCase {
    public WorkspaceEditValidationTest() {
        super("*.foo");
    }

    private record Context(LanguageServerWrapper wrapper, LanguageClientImpl client, String uri) {
    }

    private Context open() throws Exception {
        myFixture.configureByText("test.foo", "before");
        var server = LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(myFixture.getFile(), null, null).get(5, TimeUnit.SECONDS).get(0);
        var wrapper = server.getServerWrapper();
        var client = new LanguageClientImpl(myFixture.getProject());
        Disposer.register(myFixture.getProject(), client);
        client.setServerWrapper(wrapper);
        String uri = wrapper.toUri(myFixture.getFile().getVirtualFile()).toString();
        return new Context(wrapper, client, uri);
    }

    private static TextEdit replacement() {
        return new TextEdit(new Range(new Position(0, 0), new Position(0, 6)), "after");
    }

    private static WorkspaceEdit versioned(String uri, Integer version) {
        var text = new TextDocumentEdit(new VersionedTextDocumentIdentifier(uri, version),
                List.of(Either.forLeft(replacement())));
        return new WorkspaceEdit(List.of(Either.forLeft(text)));
    }

    private int version(Context context) {
        return context.wrapper().getOpenedDocument(URI.create(context.uri())).getSynchronizer().getVersion();
    }

    private ApplyWorkspaceEditResponse await(CompletableFuture<ApplyWorkspaceEditResponse> future) {
        PlatformTestUtil.waitWithEventsDispatching("workspace edit must complete", future::isDone, 5);
        return future.join();
    }

    public void testCurrentVersionIsAppliedAndAcknowledged() throws Exception {
        var context = open();
        var response = await(context.client().applyEdit(new ApplyWorkspaceEditParams(versioned(context.uri(), version(context)))));
        assertTrue(response.isApplied());
        assertEquals("after", myFixture.getEditor().getDocument().getText());
    }

    public void testStaleVersionRefusesBeforeAnyOtherDocumentIsChanged() throws Exception {
        var context = open();
        int originalVersion = version(context);
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () ->
                myFixture.getEditor().getDocument().setText("new typing"));
        var other = myFixture.addFileToProject("other.foo", "before");
        var first = new TextDocumentEdit(new VersionedTextDocumentIdentifier(
                context.wrapper().toUri(other.getVirtualFile()).toString(), null), List.of(Either.forLeft(replacement())));
        var second = versioned(context.uri(), originalVersion).getDocumentChanges().get(0);
        var edit = new WorkspaceEdit(List.of(Either.forLeft(first), second));
        var response = await(context.client().applyEdit(new ApplyWorkspaceEditParams(edit)));
        assertFalse(response.isApplied());
        assertNotNull(response.getFailureReason());
        assertEquals("before", other.getText());
        assertEquals("new typing", myFixture.getEditor().getDocument().getText());
    }

    public void testUnversionedQueuedReplyCannotOverwriteNewTyping() throws Exception {
        var context = open();
        var edit = new WorkspaceEdit(Map.of(context.uri(), List.of(replacement())));
        var future = context.client().applyEdit(new ApplyWorkspaceEditParams(edit));
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> myFixture.getEditor().getDocument().setText("new typing"));
        assertFalse(await(future).isApplied());
        assertEquals("new typing", myFixture.getEditor().getDocument().getText());
    }

    public void testVersionedEditForDisconnectedDocumentIsRefused() throws Exception {
        var context = open();
        var edit = versioned(context.uri(), version(context));
        context.wrapper().disconnect(URI.create(context.uri()), false);
        assertFalse(await(context.client().applyEdit(new ApplyWorkspaceEditParams(edit))).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
    }

    public void testUnsolicitedEditForPreviousIncarnationCannotMatchReopenedVersion() throws Exception {
        var context = open();
        int previousVersion = version(context);
        context.wrapper().disconnect(URI.create(context.uri()), false);
        LanguageServiceAccessor.getInstance(myFixture.getProject()).getLanguageServers(myFixture.getFile(), null, null)
                .get(5, TimeUnit.SECONDS);
        assertTrue(version(context) > previousVersion);
        assertFalse(await(context.client().applyEdit(new ApplyWorkspaceEditParams(
                versioned(context.uri(), previousVersion)))).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
        assertTrue(await(context.client().applyEdit(new ApplyWorkspaceEditParams(
                versioned(context.uri(), version(context))))).isApplied());
        assertEquals("after", myFixture.getEditor().getDocument().getText());
    }

    public void testDidOpenVersionCannotAuthorizeAnEditToNewerText() throws Exception {
        var context = open();
        var file = myFixture.getFile().getVirtualFile();
        var document = myFixture.getEditor().getDocument();
        context.wrapper().disconnect(URI.create(context.uri()), false);
        var connectionInfo = context.wrapper().createFileConnectionInfo(file, document, true);
        WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () -> document.setText("new typing"));
        context.wrapper().connect(file, connectionInfo).get(5, TimeUnit.SECONDS);

        assertFalse(await(context.client().applyEdit(new ApplyWorkspaceEditParams(
                versioned(context.uri(), version(context))))).isApplied());
        assertEquals("new typing", document.getText());
    }

    public void testRetiredClientCannotApplyAnUnsolicitedEdit() throws Exception {
        var context = open();
        context.client().dispose();
        assertFalse(await(context.client().applyEdit(new ApplyWorkspaceEditParams(
                new WorkspaceEdit(Map.of(context.uri(), List.of(replacement())))))).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
    }

    public void testQueuedEditIsRefusedIfItsClientRetires() throws Exception {
        var context = open();
        var pending = context.client().applyEdit(new ApplyWorkspaceEditParams(
                new WorkspaceEdit(Map.of(context.uri(), List.of(replacement())))));
        context.client().dispose();
        assertFalse(await(pending).isApplied());
        assertEquals("before", myFixture.getEditor().getDocument().getText());
    }

}
