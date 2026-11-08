# Release notes — 1.5.0 engineering increment

- LSP initialize now advertises richer completion item support, semantic token full/range/delta support, workspace configuration and watched-file registration capabilities.
- LSP server capabilities are retained; dynamic client capability registrations/unregistrations are tracked; requests can be cancelled using `$/cancelRequest`.
- Added semantic-token full, range and delta request helpers.
- Added local Git rebase, continue and abort service operations with conflict gating.
- Added bounded line-aware large-file paging with workspace path validation.
- Added unit tests for paging/path traversal and local rebase.
- CI now generates an SPDX JSON SBOM and includes it with build/test artifacts.
- Android version is 1.5.0 (versionCode 150).

This is an engineering increment, not a production certification. See `PRODUCTION-READINESS-1.5.0.md` for limitations and unverified areas.
