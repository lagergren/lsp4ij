/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.formatting;

import com.intellij.formatting.FormattingContext;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.TextRange;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Formatting must work for save and closed files, which have no editor. */
public class ClosedDocumentFormattingTest extends LSPCodeInsightFixtureTestCase {

    public ClosedDocumentFormattingTest() {
        super("*.ts");
    }

    public void testFullFormattingWithoutEditor() throws Exception {
        assertClosedDocumentFormatting(null);
    }

    public void testRangeFormattingWithoutEditor() throws Exception {
        assertClosedDocumentFormatting(new TextRange(0, 13));
    }

    private void assertClosedDocumentFormatting(TextRange range) throws Exception {
        String original = "const value=1;";
        var file = myFixture.configureByText("closed.ts", original);
        var document = myFixture.getEditor().getDocument();
        LanguageServiceAccessor.getInstance(myFixture.getProject())
                .getLanguageServers(file, null, null).get(5, TimeUnit.SECONDS);
        FileEditorManager.getInstance(myFixture.getProject()).closeFile(file.getVirtualFile());
        MockLanguageServer.INSTANCE.setFormattingTextEdits(List.of(
                new TextEdit(new Range(new Position(0, 11), new Position(0, 12)), " = ")));
        var request = new FormattingResult(original, new CompletableFuture<>());

        new LSPFormattingSupport(file).format(document, file, null, range, request);

        assertEquals("const value = 1;", request.result().get(5, TimeUnit.SECONDS));
        assertEquals("Formatting returns text for the platform to apply", original, document.getText());
    }

    private record FormattingResult(String text, CompletableFuture<String> result) implements AsyncFormattingRequest {
        @Override
        public String getDocumentText() {
            return text;
        }

        @Override
        public File getIOFile() {
            return null;
        }

        @Override
        public List<TextRange> getFormattingRanges() {
            return List.of();
        }

        @Override
        public boolean canChangeWhitespaceOnly() {
            return true;
        }

        @Override
        public boolean isQuickFormat() {
            return false;
        }

        @Override
        public FormattingContext getContext() {
            throw new UnsupportedOperationException("The document and file are passed directly to format");
        }

        @Override
        public void onTextReady(String formatted) {
            result.complete(formatted);
        }

        @Override
        public void onError(String title, String message) {
            result.completeExceptionally(new AssertionError(title + ": " + message));
        }

        @Override
        public void onError(String title, String message, int offset) {
            onError(title, message);
        }
    }
}
