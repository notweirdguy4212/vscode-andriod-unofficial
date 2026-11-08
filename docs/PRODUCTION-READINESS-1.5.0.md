# Production readiness — 1.5.0

## Implemented in this increment

- LSP transport request cancellation (`$/cancelRequest`) and bulk cancellation.
- LSP semantic-token request entry points (full, range, delta), richer completion capability advertisement, server capability retention and dynamic capability registration tracking.
- Local Git rebase, continue and abort service entry points. Continue is gated on all conflict paths being staged/resolved.
- Bounded, line-aware large-file reader with canonical workspace containment checks and configurable page size.
- CI SPDX JSON SBOM artifact generation.
- Optional release APK signing from externally supplied keystore environment variables; archive tasks use stable ordering and timestamps.

## Still required for the requested full feature set

| Area | Remaining work |
|---|---|
| Advanced editor | Wire LSP completion into Sora's native completion API; render diagnostic gutter markers, ranges and tooltips; apply semantic tokens to editor spans; support snippets and completion resolve end-to-end. |
| LSP | Implement all advertised/dynamically registered capabilities and server-to-client requests; robust request lifecycle integration with editor document changes; negotiate and validate semantic token legends/delta edits. |
| Git | SSH transport with protected key import, host-key verification and credential lifecycle; Git LFS filters/locking; visual three-way conflict editor and rebase conflict UX. |
| DAP | Bundle or validate Android ABI-compatible adapters, verify adapter provenance/signatures, and run protocol integration tests on supported Android versions/devices. |
| Runtime | Move Python, Node.js and Java into a genuinely isolated execution boundary with process-tree termination, filesystem/network policy and resource quotas. The existing shell isolated service is not sufficient. |
| Workspace | Persistent SAF URI grants, bidirectional synchronization, conflict policy, file observers and crash-safe recovery journal. |
| Performance | Connect pager to editor document model, UTF-8 boundary handling, memory/latency benchmarks, Android device profiling and stress tests. |
| Quality | Android instrumentation, accessibility audits, lifecycle/process-death tests, LSP/DAP/Git integration tests, and supported-device matrix. |
| Release | Provision protected signing secrets in release CI, signed provenance/attestation, reproducibility comparison across clean builders, dependency/license audit and SBOM review. |

## Verification performed here

Source-level edits and test cases were added. This environment does not provide Gradle, the Android SDK, `android.jar`, or an Android device; therefore Gradle compilation, unit tests, lint, APK assembly, instrumentation, debugger adapter compatibility and profiling have **not** been executed. CI is configured to run Android unit tests/lint/builds and emit an SPDX SBOM, but a successful CI run is still required before release.

## Security note

Do not execute untrusted code or treat this engineering preview as a hardened IDE. In particular, the Python/Node.js/Java isolation requirement is not met by this increment.
