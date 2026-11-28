# EmmyLua Debug Adapter (Lua)

To debug [Lua](https://www.lua.org/) programs, you can use [emmylua_dap](https://github.com/EmmyLuaLs/emmylua_dap), the
debug adapter of EmmyLua, which speaks DAP over its standard input and output. It debugs Lua 5.1 to 5.4 and LuaJIT
through [emmy_core](https://github.com/EmmyLua/EmmyLuaDebugger), a library which the program loads, and which the debug
adapter connects to.

## Prepare the Lua program

1. Download the archive of your OS from the latest
   [EmmyLuaDebugger release](https://github.com/EmmyLua/EmmyLuaDebugger/releases), and extract `emmy_core` from it:
   `emmy_core.dll` on Windows, `emmy_core.so` on Linux and `emmy_core.dylib` on macOS.

2. Load it at the start of the program, and listen for the debug adapter, here with `emmy_core.dylib` in the folder
   `/path/to/emmy`:

   ```lua
   package.cpath = package.cpath .. ";/path/to/emmy/?.dylib"
   local dbg = require("emmy_core")
   dbg.tcpListen("localhost", 9966)
   dbg.waitIDE()
   ```

   `waitIDE()` waits until the debug adapter connects, so that the breakpoints are set before the program goes on.

## Configure DAP server

1. Create a DAP Run/Debug configuration, and in the `Server` tab, click on `create a new server`.

2. Select the `EmmyLua Debug Adapter (Lua)` template, and click on `OK`. This fills:

   * the command, `<<insert base directory>>/emmylua_dap`, which the installer sets;
   * the file mappings, for `*.lua`;
   * the launch configuration `Debug a Lua program that loads emmy_core`, which connects to the `host` and `port` of
     `tcpListen` in the program, and finds the files of the program in its `sourcePaths`, the working directory of
     the `Configuration` tab.

3. Start the Lua program, which waits for the debug adapter, and then debug the run configuration.

The first time the DAP server starts, the installer downloads the archive of your OS and CPU from the latest
[emmylua_dap release](https://github.com/EmmyLuaLs/emmylua_dap/releases), extracts it into
`$USER_HOME$/.lsp4ij/dap/emmylua-dap`, and sets the command to `"$USER_HOME$/.lsp4ij/dap/emmylua-dap/emmylua_dap"`.
If GitHub can't be reached, it downloads emmylua_dap 0.5.0 instead.

To install emmylua_dap yourself, extract the archive of a release into a folder of your choice, set the command to the
`emmylua_dap` in it, and empty the `Installer` tab, so that LSP4IJ doesn't install the DAP server.
