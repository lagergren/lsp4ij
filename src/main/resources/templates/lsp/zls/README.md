You can use the [Zig Language Server](https://github.com/zigtools/zls) (ZLS) by following these instructions:
* [Install Zig](https://ziglang.org/download/), and put `zig` on your PATH, or set `zig_exe_path` in the
  **Configuration** tab. ZLS runs it to build your project and to find Zig's standard library.
* Click **OK**: the first time the server starts, if `zls` isn't on your PATH, the installer of the template downloads
  the `zls` of the [latest ZLS release](https://github.com/zigtools/zls/releases) for your OS and architecture into
  `$USER_HOME$/.lsp4ij/lsp/zls`, and sets the command to `"$USER_HOME$/.lsp4ij/lsp/zls/zls"` (`zls.exe` on Windows).

A ZLS release works with the Zig release of the same version, such as ZLS 0.17.0 with Zig 0.17.x. For another Zig,
install the ZLS that matches it, as [ZLS's install guide](https://zigtools.org/zls/install/) says, and put it on your
PATH, or set its path in the command: the installer keeps a `zls` that starts.
