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

import com.redhat.devtools.lsp4ij.fixtures.LSPCodeInsightFixtureTestCase;
import com.redhat.devtools.lsp4ij.mock.MockLanguageServer;
import org.eclipse.lsp4j.SemanticTokenModifiers;
import org.eclipse.lsp4j.SemanticTokensCapabilities;

import java.util.List;

/**
 * The semantic tokens capabilities that the client declares to the language server.
 */
public class SemanticTokensClientCapabilitiesTest extends LSPCodeInsightFixtureTestCase {

    public SemanticTokensClientCapabilitiesTest() {
        super("*.luax");
    }

    public void testTheClientDeclaresTheStandardTokenModifiers() {
        assertEquals(List.of(
                        SemanticTokenModifiers.Declaration,
                        SemanticTokenModifiers.Definition,
                        SemanticTokenModifiers.Readonly,
                        SemanticTokenModifiers.Static,
                        SemanticTokenModifiers.Deprecated,
                        SemanticTokenModifiers.Abstract,
                        SemanticTokenModifiers.Async,
                        SemanticTokenModifiers.Modification,
                        SemanticTokenModifiers.Documentation,
                        SemanticTokenModifiers.DefaultLibrary),
                semanticTokensCapabilities().getTokenModifiers());
    }

    public void testTheClientDeclaresFullAndRangeRequests() {
        var requests = semanticTokensCapabilities().getRequests();
        assertEquals(Boolean.TRUE, requests.getFull().getLeft());
        assertEquals(Boolean.TRUE, requests.getRange().getLeft());
    }

    private SemanticTokensCapabilities semanticTokensCapabilities() {
        MockLanguageServer.reset();
        var file = myFixture.configureByText("test.luax", "foo");
        awaitLanguageServers(file);
        return MockLanguageServer.INSTANCE.getInitializeParams().getCapabilities().getTextDocument().getSemanticTokens();
    }
}
