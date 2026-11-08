# Production readiness — 1.4.0

## Scope of this update

This is an incremental engineering update over 1.3.0, not a claim that every roadmap item has reached production readiness.

| Area | 1.4.0 change | Status |
|---|---|---|
| Workspace file management | Nested folder creation, refresh, file deletion, move/rename API | Implemented in source; UI includes create-folder, refresh and file-delete controls. Move/rename API is not yet exposed in UI. |
| Workspace persistence | Same-directory temporary write, file descriptor sync, atomic replacement fallback | Implemented in source; not device-tested. |
| Path safety | Canonical root containment, traversal rejection, folder-descendant move rejection | Implemented in source; unit tests added but not executed here. |
| LSP/editor | Existing 1.3.0 functionality retained | Native Sora completion/diagnostic integration and other roadmap items remain incomplete. |
| Git/remotes | Existing 1.3.0 functionality retained | SSH, rebase, submodules, LFS, credential vault and visual conflict editor remain incomplete. |
| DAP | Existing 1.3.0 functionality retained | No bundled or device-verified adapters; live debug sessions remain unverified. |
| Runtime security | Existing 1.3.0 functionality retained | Python/Node/Java execute under app UID; shell isolated service is not a hardened hostile-code sandbox. |
| SAF/workspace sync | Existing snapshot import retained | Live SAF synchronization, watchers, autosave recovery and full move/rename UI remain incomplete. |
| Release engineering | Version metadata updated | Signing, reproducible build, SBOM, dependency scanning, instrumentation and Play review remain outstanding. |

## Build and test environment

The execution environment has Java 21 and Kotlin compiler 1.9, but no Gradle executable/wrapper and no Android SDK or `android.jar`. Therefore the following were **not run**:
- Gradle dependency resolution / Android compilation
- JVM unit test suite
- Android lint
- Debug or release APK assembly
- Instrumentation, lifecycle, accessibility, performance or physical-device tests
- ARM/ARM64/x86 ABI validation

No APK is included, and no build success is asserted.

## Release decision

**Not approved for production distribution.** Complete the outstanding items above and run the full Android build and device matrix before publishing. The 1.4.0 source should be treated as an engineering increment only.
