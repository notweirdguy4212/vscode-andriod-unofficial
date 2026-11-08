# VS Code Android Pro — 1.5.0 engineering preview

An Android Studio / Kotlin / Jetpack Compose coding workbench for phones and tablets.

## 1.5.0 engineering changes

- **LSP protocol:** advertise semantic-token full/range/delta support and workspace configuration/file-operation capabilities; retain server capabilities; track dynamic capability registrations; expose request cancellation with `$/cancelRequest`; add semantic-token request helpers.
- **Git:** add local rebase, continue and abort operations with explicit conflict gating and structured status results.
- **Large files:** add a bounded, line-aware workspace page reader (4 KiB–2 MiB page limit) so callers can inspect large files without loading the entire file into memory.
- **Release pipeline:** advance app version to 1.5.0 and generate an SPDX JSON software bill of materials in CI.

## 1.4.0 engineering changes

- **Workspace file operations:** create nested folders, delete files, move/rename files and folders through the workspace store, and persist edits using same-directory temporary files with fsync and atomic replacement where supported. The Explorer exposes folder creation, refresh and file deletion. The workspace store rejects traversal and moving a folder into its own descendant.
- **Versioning:** Android application version advanced to 1.4.0 (versionCode 140).

## Retained 1.3.0 engineering changes

- **LSP:** caret-position completion requests with debounce and a selectable completion overlay; diagnostics surfaced in Problems; code-action resolve/execute; formatting; and guarded multi-file `WorkspaceEdit` application. The client advertises completion, diagnostics, code actions, formatting and workspace-edit capabilities.
- **Project management:** persistent named projects with create, open, rename and delete operations. Each project has an independent app-private workspace; SAF folder import targets the selected project.
- **Git:** local init/status/staging/unstaging/commit, branch create/checkout/merge, tags, stash/apply, patch diff, history, HTTPS remotes, fetch/pull/push, and conflict-file marking after manual resolution. HTTPS credentials are passed per operation and not saved to Git config.
- **Debugging:** DAP stdio transport, Python/Node launch profiles, breakpoints, continue/step/pause, threads, stack frames, scopes, variables and expression evaluation. Debug adapter binaries are user-supplied.
- **Runtime isolation:** `.sh` files can run through an Android `isolatedProcess` service with a separate OS UID, bounded input/output and a wall-clock timeout. The result displays the isolated UID so the boundary can be checked on-device.
- **Toolchain audit:** imported executables receive SHA-256 fingerprints and ELF machine/ABI matching against the running device. This is an architecture/integrity inventory, not publisher authentication.

## Important capability and security boundaries

This is still an **engineering preview, not a production-certified IDE**.

- LSP completion is requested at the caret and displayed in an app popup, but it is not yet wired into Sora's native completion component. Diagnostics appear in the Problems panel; native editor diagnostic markers/tooltips are not yet connected. Server-specific behavior still depends on the installed server and its dependencies.
- No runtime or language-server packages are bundled or publisher-signature verified. The audit can identify ELF machine compatibility and compute hashes, but does not validate dynamic libraries, Android API compatibility, licenses, provenance or runtime behavior. User-imported Linux binaries are not automatically Android-compatible.
- The isolated-UID service currently executes shell scripts only. Python, Node.js and Java execution remains under the application UID and requires an explicit trusted-code confirmation. Because the app requests Android Internet permission for HTTPS Git, these runtimes, language servers and debug adapters can also use the app's network permission. Even the shell service is not a hardened sandbox: it has no dedicated filesystem namespace, CPU/memory/PID quotas or guaranteed process-tree termination. Do not run hostile code.
- DAP support is a protocol client, not a bundled debugger. No Python or JavaScript adapter is included or verified; real launch, breakpoint and stepping behavior requires a compatible adapter build for the device ABI.
- Git supports common local and HTTPS workflows, but not SSH, rebase, submodules, LFS, credential-helper integration or an interactive conflict editor. Conflicts must be edited manually and then marked resolved.
- Projects are isolated local directories, but SAF is import-only rather than live-synchronized. File rename/move/delete/create-folder, external file watching and crash recovery are still incomplete.
- No VS Code extension host or Marketplace compatibility is provided.

See `docs/PRODUCTION-READINESS-1.3.0.md` and `docs/TEST-REPORT-1.3.0.md` before using this build with untrusted code or important repositories.

## Build

Requirements: JDK 17+, Android SDK platform 35/build tools 35, Android Gradle Plugin 8.9.2, Gradle 8.11.1 and network access to resolve Maven dependencies.

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
gradle :app:assembleRelease
```

## Documentation

- `docs/RELEASE-NOTES-1.3.0.md` — release changes.
- `docs/PRODUCTION-READINESS-1.3.0.md` — release blockers and security assessment.
- `docs/TEST-REPORT-1.3.0.md` — executed source checks versus Android/device tests still required.
- `docs/ARCHITECTURE.md` — architecture.
- `docs/EDITOR-LSP-RUNTIME-GIT.md` — subsystem design and security boundaries.
- `docs/LANGUAGES.md` — language mode and LSP mapping.
- `readmes/` — translated documentation from the 1.2.0 baseline.
- `licenses/` — third-party license notices.


## 1.4.0 verification status

This source update is not a production certification. The available build environment did not include Gradle, an Android SDK, or `android.jar`; therefore Android compilation, unit tests, lint, APK assembly, instrumentation, and device/ABI validation were not run. See `docs/PRODUCTION-READINESS-1.4.0.md` for the exact scope and remaining blockers.


## 1.5.0 scope and verification caveats

This increment adds protocol and service primitives, not a complete VS Code feature parity layer. Native Sora completion integration and native diagnostic gutter markers still require editor API integration and Android-device verification. Semantic-token requests are exposed, but token delta decoding/rendering is not yet connected to the editor. Dynamic LSP registrations are recorded; the client does not yet implement every registered feature or server request.

Git rebase is local-ref based. SSH transport, secure key management/known-host verification, Git LFS smudge/clean and locking, and an interactive three-way conflict UI are not included. DAP adapters remain user-provided and are not certified as Android-compatible. Python, Node.js and Java are not yet launched in a hardened isolated UID/service boundary. SAF live synchronization, OS file watching and crash-recovery journaling remain outstanding.

The large-file pager is a bounded I/O primitive and is not yet wired into the editor's document model. CI emits an SPDX SBOM. Release signing is enabled when `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` are supplied as protected build environment secrets; without them, the release APK is unsigned. Archive tasks use stable file ordering and timestamps, but clean-builder reproducibility comparison and signed provenance/attestation still require maintainer CI work. No Android build, instrumentation suite, or physical-device profiling was executed in this environment.
