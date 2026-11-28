# GDB

To debug programs compiled from [Ada](https://ada-lang.io/), [C, C++](https://isocpp.org/) or
[Fortran](https://fortran-lang.org/), you can use the
[debug adapter of GDB](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Debug-Adapter-Protocol.html):
`gdb -i dap` talks DAP over its standard input and output. It needs GDB 16 or later, built with Python, as the GDB of
Linux distributions, of [MSYS2](https://www.msys2.org/) on Windows, and of GNAT for Ada are. GDB 14 and 15 have the
debug adapter too, but start the program as soon as they get the `launch` request, before LSP4IJ sets the breakpoints,
and then answer its `configurationDone` request with an error, which ends the debug session: Ubuntu 24.04's GDB is
GDB 15.

## Configure DAP server

1. Install GDB 16 or later, and make sure that `gdb` is on your PATH. On macOS, GDB must be code signed before it can
   debug a program; [CodeLLDB](./codelldb.md) debugs C and C++ there without that.

2. Compile the program with debug information and without optimizations, as `gcc -g -O0`, `gfortran -g -O0` or
   `gprbuild -g` (`-cargs -O0`).

3. Create a DAP Run/Debug configuration, and in the `Server` tab, click on `create a new server`.

4. Select the `GDB` template, and click on `OK`. This fills:

   * the command, `gdb -i dap`;
   * the file mappings, for the Ada (`*.adb`, `*.ads`, `*.ada`), Fortran (`*.f`, `*.for`, `*.f90`, `*.f95`, `*.f03`,
     `*.f08`), C and C++ files (`*.c`, `*.h`, `*.cpp`, `*.cc`, `*.cxx`, `*.hpp`, `*.hh`, `*.hxx`);
   * the launch configuration `Launch program`, which runs the `File` of the `Configuration` tab: set it to the
     program, not to a source file. See the
     [launch parameters of GDB](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Debug-Adapter-Protocol.html)
     for the others, as `stopAtBeginningOfMainSubprogram`, which stops at the start of `main` or of the main subprogram
     of an Ada program.

The installer doesn't install GDB: it checks that `gdb -i dap` starts, and otherwise says how to install GDB.

## Attach to a running process

GDB attaches to a process, or to a `gdbserver`, with an `attach` request, which it gets from the debug adapter that the
IDE started over its standard input and output. LSP4IJ's `Attach` debug mode instead connects to a debug adapter which
already listens at an address and a port, and starts none, so the template has no attach configuration.

## Language Support

If you need language support (completion, validation, etc.), you can configure
[clangd](../../user-defined-ls/clangd.md) for C and C++, and the
[Ada Language Server](../../user-defined-ls/ada-language-server.md) for Ada.
