# Release notes — 1.4.0 engineering update

## Implemented
- Bumped Android version metadata to `1.4.0` / version code `140`.
- Added workspace directory creation, recursive deletion, in-project move/rename, and atomic editor-file persistence.
- Added Explorer controls for creating folders, refreshing the workspace listing, and deleting files.
- Added traversal and descendant-move protections for workspace operations.
- Added unit tests for the new store operations and path constraints.

## Verification
The Android build toolchain is not present in the execution environment (no Gradle executable or wrapper, Android SDK, or `android.jar`). Consequently the application and test suite could not be built or executed here. No APK is claimed as produced.
