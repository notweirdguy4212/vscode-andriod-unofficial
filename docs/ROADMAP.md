# Roadmap after 1.3.0

1. Provision signed, reproducible ARM64/other-ABI Python, Node.js, Java, LSP and DAP packages; verify publisher signatures, dynamic dependencies, licenses, updates and rollback on physical devices.
2. Connect LSP completion to Sora's native completion component and map LSP diagnostics to Sora diagnostic markers/tooltips; add request cancellation, hover, rename, semantic tokens and robust server capability negotiation.
3. Extend OS isolation to all supported language runtimes using a brokered file-descriptor workspace, no-network policy, process-tree containment and kernel-enforced CPU/memory/PID limits. Do not treat the current isolated shell service as a hostile-code sandbox.
4. Add SAF live synchronization, file rename/move/delete/create-folder, file watchers, autosave, crash recovery and large-file paging to the multi-project workspace registry.
5. Complete Git workflows with rebase, SSH where appropriate, submodules/LFS, credential-manager integration, visual conflict editing, and extensive remote/malformed-repository tests.
6. Validate Python and JavaScript DAP adapters on Android; add source maps, breakpoint persistence, launch configurations, exception breakpoints and richer variable/watch views.
7. Add Android instrumentation, accessibility, performance, memory, lifecycle, fuzz, hostile-repository and security tests on physical ARM64 devices.
8. Add release signing, reproducible builds, SBOM, dependency/license/vulnerability scanning, privacy review and Play policy checks.


## 1.4.0 progress

Workspace file operations and atomic persistence have been added in source. All other roadmap items remain open unless explicitly listed in `RELEASE-NOTES-1.4.0.md`; do not infer completion from the version bump.
