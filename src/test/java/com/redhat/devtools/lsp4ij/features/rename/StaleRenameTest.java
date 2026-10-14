/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.rename;

import com.intellij.openapi.command.WriteCommandAction;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A delayed rename response must retain the document state from before the request.
 */
public class StaleRenameTest extends LSPCodeInsightFixtureTestCase {

    public StaleRenameTest() {
        super("*.foo");
    }

    public void testTypingWhileRenameIsPendingInvalidatesResponse() throws Exception {
        var file = myFixture.configureByText("test.foo", "before");
        var servers = LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(file, null, null).get(5, TimeUnit.SECONDS);
        String uri = servers.get(0).getServerWrapper().toUri(file.getVirtualFile()).toString();
        var requested = new CompletableFuture<Void>();
        var reply = new CompletableFuture<WorkspaceEdit>();
        MockLanguageServer.INSTANCE.setRenameProcessor(params -> {
            requested.complete(null);
            return reply.join();
        });

        var params = new LSPRenameParams(new TextDocumentIdentifier(uri), new Position(0, 0), servers);
        params.setNewName("after");
        var support = new LSPRenameSupport(file);
        try {
            var pending = support.getRename(params);
            requested.get(5, TimeUnit.SECONDS);
            WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () ->
                    myFixture.getEditor().getDocument().setText("new typing"));
            reply.complete(new WorkspaceEdit(Map.of(uri,
                    List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 6)), "after")))));

            var edits = pending.get(5, TimeUnit.SECONDS);
            assertEquals(1, edits.size());
            var data = edits.get(0);
            WriteCommandAction.runWriteCommandAction(myFixture.getProject(), () ->
                    assertNotNull(data.snapshot().validate(data.edit())));
            assertEquals("new typing", myFixture.getEditor().getDocument().getText());
        } finally {
            reply.cancel(false);
            support.cancel();
        }
    }
}
