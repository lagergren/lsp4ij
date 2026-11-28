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

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.testFramework.LightPlatformTestCase;
import org.eclipse.lsp4j.SemanticTokenModifiers;
import org.eclipse.lsp4j.SemanticTokenTypes;

import java.util.List;

/**
 * The color of a semantic token of the type <code>variable</code>: a variable without the modifier
 * <code>readonly</code>, as most language servers send, is not underlined in IntelliJ's schemes, which underline a
 * local variable that is assigned more than once.
 */
public class SemanticTokensVariableColorTest extends LightPlatformTestCase {

    public void testAVariableIsColoredAsALocalVariable() {
        var provider = new DefaultSemanticTokensColorsProvider();
        assertEquals(SemanticTokensHighlightingColors.VARIABLE,
                provider.getTextAttributesKey(SemanticTokenTypes.Variable, List.of(), createFile("test.txt", "")));
        assertEquals(SemanticTokensHighlightingColors.READONLY_VARIABLE,
                provider.getTextAttributesKey(SemanticTokenTypes.Variable, List.of(SemanticTokenModifiers.Readonly), createFile("test.txt", "")));
        // A variable inherits the color of a local variable, as a readonly one does, and not of a reassigned one
        assertEquals(DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
                SemanticTokensHighlightingColors.VARIABLE.getFallbackAttributeKey());
        assertEquals(DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
                SemanticTokensHighlightingColors.READONLY_VARIABLE.getFallbackAttributeKey());
    }

    public void testAVariableIsNotUnderlinedInIntelliJSchemes() {
        EditorColorsScheme[] schemes = EditorColorsManager.getInstance().getAllSchemes();
        assertTrue("IntelliJ has color schemes", schemes.length > 0);
        for (EditorColorsScheme scheme : schemes) {
            var attributes = scheme.getAttributes(SemanticTokensHighlightingColors.VARIABLE);
            assertTrue(scheme.getName() + " must not draw an effect, such as an underline, on a variable: " + attributes,
                    attributes == null || attributes.getEffectColor() == null || attributes.getEffectType() == null);
        }
    }
}
