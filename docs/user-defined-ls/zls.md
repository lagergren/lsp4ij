# Zig Language Server (ZLS)

To enable [Zig](https://ziglang.org/) language support in your IDE, you can integrate the
[Zig Language Server](https://github.com/zigtools/zls) (ZLS, `zls`) by following these steps.

## Install the language server

1. **Install Zig** from [ziglang.org](https://ziglang.org/download/), and put `zig` on your PATH. ZLS runs it to build
   your project and to find Zig's standard library. To use a `zig` that isn't on your PATH, set `zls.zig_exe_path` in
   the `Configuration` tab.

2. **Open the New Language Server Dialog**. This can usually be found under the IDE settings related to Language Server
   Protocol (LSP). For more information, refer to the
   [New Language Server Dialog documentation](../UserDefinedLanguageServer.md#new-language-server-dialog).

3. **Select Zig Language Server (ZLS) as the template** from the available options. This will populate the command
   field with `zls`, and the mappings, which map `*.zig` and `*.zon` files to the `zig` language id.

4. **Click OK** to apply the changes. You should now have Zig language support enabled in your IDE, and you should see
   the language server in the [LSP console](../UserGuide.md#lsp-console).

The first time the server starts, the installer of the template checks that the command starts a language server: a
`zls` on your PATH, as a package manager installs it, is kept. Otherwise, it downloads the asset of the latest
[ZLS release](https://github.com/zigtools/zls/releases) for your OS and architecture, extracts `zls` (`zls.exe` on
Windows) into `$USER_HOME$/.lsp4ij/lsp/zls`, and sets the command to `"$USER_HOME$/.lsp4ij/lsp/zls/zls"`. The assets
which the installer selects are:

| OS      | Architecture  | Asset                      |
|---------|---------------|----------------------------|
| Linux   | x64           | `zls-x86_64-linux.tar.xz`  |
| Linux   | arm64         | `zls-aarch64-linux.tar.xz` |
| macOS   | Intel         | `zls-x86_64-macos.tar.xz`  |
| macOS   | Apple Silicon | `zls-aarch64-macos.tar.xz` |
| Windows | x64           | `zls-x86_64-windows.zip`   |
| Windows | x86           | `zls-x86-windows.zip`      |
| Windows | arm64         | `zls-aarch64-windows.zip`  |

When GitHub can't be reached, or its rate limit is reached, the installer downloads the same asset of ZLS 0.17.0, see
[GitHub API rate limit](../Installer.md#github-api-rate-limit).

## Use the ZLS of your Zig

A ZLS release works with the Zig release of the same version: ZLS 0.17.0 with Zig 0.17.x. The installer can only
take the latest ZLS release, so for another Zig, such as an older release or a nightly build, install the ZLS that
matches it, as [ZLS's install guide](https://zigtools.org/zls/install/) says. Put it on your PATH, or set its path in
the command, and the installer keeps it.

## Settings

The `Configuration` tab holds the settings that ZLS asks for, in its `zls` section, such as `zls.enable_build_on_save`,
`zls.semantic_tokens` and the `zls.inlay_hints_*` settings: their list and descriptions come with the tab's schema, from
[ZLS's `schema.json`](https://github.com/zigtools/zls/blob/master/schema.json). ZLS also reads a `zls.json`, as
[ZLS's configuration guide](https://zigtools.org/zls/configure/) says.

## Highlighting

IntelliJ has no TextMate grammar for Zig, so a Zig file opens as plain text, and its colors come from the semantic
tokens of ZLS. For TextMate highlighting too, add the grammar of
[vscode-zig](https://codeberg.org/ziglang/vscode-zig), `syntaxes/zig.tmLanguage.json`, in
`Settings | Editor | TextMate Bundles`. Zig and vscode-zig moved from GitHub to Codeberg; ZLS stays on GitHub.

## Debugging

To debug a Zig program, use the [CodeLLDB](../dap/user-defined-dap/codelldb.md) debug adapter template. It maps only
Rust files, so add `*.zig` in its `Mappings` tab, as its page says for C and C++, to set breakpoints in Zig files, and
set the program that `zig build` makes, such as `zig-out/bin/<name>`, in its configuration.

To install `zls` yourself, download and extract the asset of your OS from the
[ZLS releases](https://github.com/zigtools/zls/releases), or use your package manager, and set its path in the command,
or keep `zls` when it is on your PATH.
