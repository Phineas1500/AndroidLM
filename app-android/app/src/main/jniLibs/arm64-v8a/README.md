# jniLibs/arm64-v8a

The engine binaries are **not** committed. Build and stage them with:

```powershell
pwsh scripts/build-android.ps1
```

That cross-compiles `bmoe-cli` and copies it here as `libbmoe-cli.so` alongside the
`libllama.so` / `libggml*.so` it links. Android only extracts and lets an app execute
files named `lib*.so` from its `nativeLibraryDir`, which is why the CLI is shipped
under that name and launched via `ProcessBuilder` (no JNI).

Expected files after staging:

```
libbmoe-cli.so
libllama.so
libggml.so
libggml-base.so
libggml-cpu.so
```
