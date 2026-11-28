You can use the [Lua Language Server](https://github.com/LuaLS/lua-language-server) (LuaLS) by following these
instructions:
* Click **OK**: the first time the server starts, if `lua-language-server` isn't on your PATH, the installer of the
  template downloads the archive of the [latest LuaLS release](https://github.com/LuaLS/lua-language-server/releases)
  for your OS and architecture, and extracts all of it into `$USER_HOME$/.lsp4ij/lsp/lua-language-server`: the program
  needs the `main.lua`, `script`, `meta` and `locale` that come with it. It sets the command to
  `"$USER_HOME$/.lsp4ij/lsp/lua-language-server/bin/lua-language-server"` (`lua-language-server.exe` on Windows).

The **Configuration** tab holds the `Lua.*` settings, such as `Lua.runtime.version` (`Lua 5.4` by default) and the
`Lua.diagnostics.globals` of your environment. A `.luarc.json` in the project, when there is one, takes their place.

The Luau Language Server template maps `*.lua` files too: use one of the two for a project.
