/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.dap.descriptors.templates;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.LaunchConfiguration;

import java.util.List;
import java.util.stream.Stream;

/**
 * The bundled CodeLLDB template, as the New Debug Adapter Server dialog offers it: it maps Rust and Zig files, so that
 * breakpoints can be set in them, and has the launch configuration of each, Rust's first, which the dialog fills.
 */
public class CodeLLDBTemplateTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The bundled templates are read from the jar of LSP4IJ in the test sandbox, outside the roots that a test may
        // read
        String templates = PathManager.getResourceRoot(DAPTemplateManager.class, "/templates/dap");
        assertNotNull(templates);
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), templates);
    }

    public void testTheTemplateMapsZigFilesAndHasAZigLaunchConfiguration() {
        DAPTemplate template = DAPTemplateManager.getInstance().findTemplateById("codelldb");
        assertNotNull("The CodeLLDB template is bundled", template);

        List<String> patterns = template.getFileTypeMappings().stream()
                .flatMap(mapping -> mapping.getFileNamePatterns() != null ? mapping.getFileNamePatterns().stream() : Stream.empty())
                .toList();
        assertContainsElements(patterns, "*.rs", "*.zig");
        List<String> launches = template.getLaunchConfigurations().stream()
                .filter(configuration -> configuration.getType() == DebugMode.LAUNCH)
                .map(LaunchConfiguration::getName)
                .toList();
        // In any order: the configurations are in the order of the files of the template's folder
        assertSameElements(launches, "Launch Rust file", "Launch Zig program");
    }
}
