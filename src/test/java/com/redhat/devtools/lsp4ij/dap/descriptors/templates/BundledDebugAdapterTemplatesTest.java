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
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.redhat.devtools.lsp4ij.dap.DebugMode;
import com.redhat.devtools.lsp4ij.dap.LaunchConfiguration;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * The bundled debug adapter templates, as the New Debug Adapter Server dialog offers them: the files in which they
 * set breakpoints, and their launch and attach configurations, in the order of the dialog, whose first one it fills.
 */
public class BundledDebugAdapterTemplatesTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The bundled templates are read from the jar of LSP4IJ in the test sandbox, outside the roots that a test may
        // read
        String templates = PathManager.getResourceRoot(DAPTemplateManager.class, "/templates/dap");
        assertNotNull(templates);
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), templates);
    }

    /**
     * GDB's debug adapter talks over the standard input and output of {@code gdb -i dap} only, and LSP4IJ's attach
     * connects to a debug adapter which already listens on a port, so the template only launches.
     */
    public void testGdbDebugsAdaFortranCAndCppAndOnlyLaunches() {
        var template = template("gdb");
        assertEquals("gdb -i dap", template.getOSProgramArgs());
        assertContainsElements(patterns(template), "*.adb", "*.ads", "*.f90", "*.c", "*.cpp", "*.h");
        assertEquals(List.of("Launch program"), names(template, DebugMode.LAUNCH));
        assertEmpty(names(template, DebugMode.ATTACH));
    }

    /**
     * netcoredbg launches over the standard input and output of its process, and attaches from the port on which a
     * netcoredbg started with {@code --server} listens.
     */
    public void testNetcoredbgDebugsDotNetAndLaunchesOrAttachesThroughItsServerPort() {
        var template = template("netcoredbg");
        assertTrue(template.getOSProgramArgs(), template.getOSProgramArgs().endsWith(" --interpreter=vscode"));
        assertEquals("$port", template.getAttachPort());
        assertContainsElements(patterns(template), "*.cs", "*.fs", "*.vb");
        assertEquals(List.of("Launch .NET program"), names(template, DebugMode.LAUNCH));
        assertEquals(List.of("Attach to a .NET process"), names(template, DebugMode.ATTACH));
    }

    public void testEmmyLuaDapDebugsLuaWithAConfigurationThatConnectsToTheProgram() {
        var template = template("emmylua-dap");
        assertContainsElements(patterns(template), "*.lua");
        assertEquals(List.of("Debug a Lua program that loads emmy_core"), names(template, DebugMode.LAUNCH));
        assertEmpty(names(template, DebugMode.ATTACH));
    }

    public void testJsDebugSetsBreakpointsInSvelteVueAndAstroFilesAndLaunchesChromeAfterNode() {
        var template = template("vscode-js-debug");
        assertContainsElements(patterns(template), "*.jsx", "*.ts", "*.svelte", "*.vue", "*.astro");
        // The dialog fills the first one: Node's, as before
        assertEquals(List.of("Launch JavaScript file", "Launch TypeScript file", "Launch Chrome"),
                names(template, DebugMode.LAUNCH));
    }

    public void testDebugpyLaunchesThroughItsAdapterAndStillAttaches() {
        var template = template("python-debugpy");
        assertTrue(template.getOSProgramArgs(), template.getOSProgramArgs().endsWith(" -m debugpy.adapter"));
        assertEquals(List.of("Launch Python file"), names(template, DebugMode.LAUNCH));
        assertEquals(List.of("Attach"), names(template, DebugMode.ATTACH));
    }

    /**
     * The launch configurations of a template are in the order of their file names, whatever the order of the files
     * in its folder, since the New Debug Adapter Server dialog fills the first one of each debug mode.
     */
    public void testTheConfigurationsOfATemplateAreInTheOrderOfTheirFileNames() throws IOException {
        VirtualFile folder = myFixture.getTempDirFixture().findOrCreateDir("template");
        for (String name : List.of("launch.web.json", "attach.json", "launch.js.json", "template.json")) {
            String json = name.equals("template.json")
                    ? "{\"id\": \"ordered\", \"name\": \"Ordered\"}"
                    : "{\"name\": \"" + name + "\"}";
            WriteAction.runAndWait(() -> VfsUtil.saveText(folder.createChildData(this, name), json));
        }

        DAPTemplate template = DAPTemplateManager.getInstance().createDapTemplate(folder);

        assertNotNull(template);
        assertEquals(List.of("launch.js.json", "launch.web.json"), names(template, DebugMode.LAUNCH));
        assertEquals(List.of("attach.json"), names(template, DebugMode.ATTACH));
    }

    private static @NotNull DAPTemplate template(@NotNull String id) {
        DAPTemplate template = DAPTemplateManager.getInstance().findTemplateById(id);
        assertNotNull("The template " + id + " is bundled", template);
        return template;
    }

    private static @NotNull List<String> patterns(@NotNull DAPTemplate template) {
        return template.getFileTypeMappings().stream()
                .flatMap(mapping -> mapping.getFileNamePatterns() != null ? mapping.getFileNamePatterns().stream() : Stream.empty())
                .toList();
    }

    private static @NotNull List<String> names(@NotNull DAPTemplate template, @NotNull DebugMode mode) {
        return template.getLaunchConfigurations().stream()
                .filter(configuration -> configuration.getType() == mode)
                .map(LaunchConfiguration::getName)
                .toList();
    }
}
