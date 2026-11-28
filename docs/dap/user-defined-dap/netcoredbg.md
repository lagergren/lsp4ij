# netcoredbg (.NET)

To debug [.NET](https://dotnet.microsoft.com/) programs written in C#, F# or Visual Basic, you can use
[netcoredbg](https://github.com/Samsung/netcoredbg), the open source debugger of Samsung for .NET, which speaks DAP:
`netcoredbg --interpreter=vscode` talks DAP over its standard input and output, and with `--server=<port>`, it listens
on that port instead.

## Configure DAP server

1. Install the [.NET SDK](https://dotnet.microsoft.com/download), and build the program to debug with `dotnet build`,
   in the `Debug` configuration, its default.

2. Create a DAP Run/Debug configuration, and in the `Server` tab, click on `create a new server`.

3. Select the `netcoredbg (.NET)` template, and click on `OK`. This fills:

   * the command, `<<insert base directory>>/netcoredbg --interpreter=vscode`, which the installer sets;
   * the file mappings, for `*.cs`, `*.fs`, `*.fsi`, `*.fsx` and `*.vb`;
   * the launch configuration `Launch .NET program`, which runs the `File` of the `Configuration` tab: set it to the
     `.dll` of the program, as `bin/Debug/net10.0/MyApp.dll`. netcoredbg starts it with the `dotnet` on your PATH.
   * the attach configuration `Attach to a .NET process`, see [Attach to a running process](#attach-to-a-running-process).

The first time the DAP server starts, the installer downloads the archive of your OS and CPU from the latest
[netcoredbg release](https://github.com/Samsung/netcoredbg/releases), extracts it into
`$USER_HOME$/.lsp4ij/dap/netcoredbg`, and sets the command to
`"$USER_HOME$/.lsp4ij/dap/netcoredbg/netcoredbg" --interpreter=vscode`. When the GitHub API gives no release, as
when its rate limit is reached, it downloads netcoredbg 3.2.0, see
[GitHub API rate limit](../../Installer.md#github-api-rate-limit). On a Mac with an Intel CPU, it downloads
netcoredbg 3.1.3, since 3.2.0 has no build for it.

To install netcoredbg yourself, extract the archive of a release into a folder of your choice, set the command to the
`netcoredbg` in it followed by `--interpreter=vscode`, and empty the `Installer` tab, so that LSP4IJ doesn't install the
DAP server.

## Attach to a running process

LSP4IJ's `Attach` debug mode connects to a debug adapter which already listens at an address and a port. To attach to a
running .NET process:

1. Start netcoredbg on a port, in a terminal:

   ```
   netcoredbg --interpreter=vscode --server=4711
   ```

2. In the `Configuration` tab, select `Attach`, and the `Attach to a .NET process` configuration, whose `port`, 4711,
   is the one LSP4IJ connects to. Set its `processId` to the id of the process, which `ps` gives.

## Language Support

If you need language support for C# (completion, validation, etc.), you can
[configure OmniSharp](../../user-defined-ls/omnisharp-roslyn.md).
