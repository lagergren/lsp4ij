# Lua Language Server (LuaLS)

To enable [Lua](https://www.lua.org/) language support in your IDE, you can integrate the
[Lua Language Server](https://github.com/LuaLS/lua-language-server) (LuaLS, `lua-language-server`) by following these
steps.

## Install the language server

1. **Open the New Language Server Dialog**. This can usually be found under the IDE settings related to Language Server
   Protocol (LSP). For more information, refer to the
   [New Language Server Dialog documentation](../UserDefinedLanguageServer.md#new-language-server-dialog).

2. **Select Lua Language Server (LuaLS) as the template** from the available options. This will populate the command
   field with `lua-language-server`, and the mappings, which map `*.lua` files to the `lua` language id.

3. **Click OK** to apply the changes. You should now have Lua language support enabled in your IDE, and you should see
   the language server in the [LSP console](../UserGuide.md#lsp-console).

The first time the server starts, the installer of the template checks that the command starts a language server: a
`lua-language-server` on your PATH, as a package manager installs it, is kept. Otherwise, it downloads the archive of
the latest [LuaLS release](https://github.com/LuaLS/lua-language-server/releases) for your OS and architecture, and
extracts all of it into `$USER_HOME$/.lsp4ij/lsp/lua-language-server`: the program needs the `main.lua`, `script`,
`meta` and `locale` that come with it. It then sets the command to
`"$USER_HOME$/.lsp4ij/lsp/lua-language-server/bin/lua-language-server"` (`lua-language-server.exe` on Windows). The
assets which the installer selects are:

| OS      | Architecture  | Asset                                           |
|---------|---------------|-------------------------------------------------|
| Linux   | x64           | `lua-language-server-<version>-linux-x64.tar.gz`    |
| Linux   | arm64         | `lua-language-server-<version>-linux-arm64.tar.gz`  |
| macOS   | Intel         | `lua-language-server-<version>-darwin-x64.tar.gz`   |
| macOS   | Apple Silicon | `lua-language-server-<version>-darwin-arm64.tar.gz` |
| Windows | x64 and arm64 | `lua-language-server-<version>-win32-x64.zip`       |
| Windows | x86           | `lua-language-server-<version>-win32-ia32.zip`      |

LuaLS has no build for Windows on Arm, which runs the x64 one. When GitHub can't be reached, or its rate limit is
reached, the installer downloads the same asset of LuaLS 3.19.2, see
[GitHub API rate limit](../Installer.md#github-api-rate-limit).

## Settings

The `Configuration` tab holds the settings that LuaLS asks for, in its `Lua` section, with their list and descriptions
in the tab's schema. The template sets:

* `Lua.runtime.version` to `Lua 5.4`, LuaLS's default. Set the version of your Lua, such as `LuaJIT` or `Lua 5.1`.
* `Lua.hint.enable` to `true`, so that the editor shows the inlay hints of LuaLS.
* `Lua.completion.callSnippet` to `Disable`, LuaLS's default.

The globals of your environment, such as those of a game engine, go in `Lua.diagnostics.globals`, and its libraries in
`Lua.workspace.library`. LuaLS can also read its settings from a
[`.luarc.json`](https://luals.github.io/wiki/configuration/) in the project: when there is one, LuaLS takes its
settings from it instead of from the tab.

## Lua and Luau

The [Luau Language Server](./luau-lsp.md) template maps `*.lua` files too, for Roblox's Luau. Use one of the two for a
project: with both, both language servers report on its files.

To install `lua-language-server` yourself, download and extract the archive of your OS from the
[LuaLS releases](https://github.com/LuaLS/lua-language-server/releases), keeping its files together, or use your package
manager, and set the path of `bin/lua-language-server` in the command, or keep `lua-language-server` when it is on your
PATH.
