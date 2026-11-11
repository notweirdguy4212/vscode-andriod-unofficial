# VS Code Android Pro

An Android-native coding workbench and mobile IDE inspired by Visual Studio Code, built with Kotlin and Jetpack Compose for phones and tablets.

<p align="center">
  <a href="https://github.com/notweirdguy4212/vscode-andriod-unofficial/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/notweirdguy4212/vscode-andriod-unofficial/android.yml?style=flat-square" alt="CI Status" /></a>
  <a href="https://github.com/notweirdguy4212/vscode-andriod-unofficial/stargazers"><img src="https://img.shields.io/github/stars/notweirdguy4212/vscode-andriod-unofficial?style=flat-square" alt="GitHub Stars" /></a>
  <a href="https://github.com/notweirdguy4212/vscode-andriod-unofficial/commits/main"><img src="https://img.shields.io/github/last-commit/notweirdguy4212/vscode-andriod-unofficial?style=flat-square" alt="Last Commit" /></a>
  <a href="https://github.com/notweirdguy4212/vscode-andriod-unofficial"><img src="https://img.shields.io/github/languages/top/notweirdguy4212/vscode-andriod-unofficial?style=flat-square" alt="Top Language" /></a>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white" alt="Gradle" />
</p>

## Overview

VS Code Android Pro delivers a desktop-grade IDE experience on mobile form factors without relying on web wrappers or remote browser streams. It pairs an Android-native editor engine with standard developer protocols (LSP and DAP), embedded version control via JGit, and a multi-project workspace store. 

Non-native runtime execution is managed through sandboxed Android process boundaries, giving developers a structured environment to write, navigate, debug, and commit code directly on Android devices.

## Features

- **TextMate syntax highlighting:** Bundled syntax grammars for C, C++, CSS, Dart, Go, HTML, Java, JavaScript, JSON, Kotlin, Markdown, PHP, Python, Ruby, Rust, Swift, and TypeScript with dark theme support.
- **Language Server Protocol (LSP):** Stdio-based LSP client with caret-position autocompletion overlays, diagnostic collection in the Problems panel, code actions, formatting, semantic tokens (full, range, delta), request cancellation (`$/cancelRequest`), and guarded multi-file `WorkspaceEdit` execution.
- **Debug Adapter Protocol (DAP):** Stdio client supporting custom launch profiles (such as Python and Node.js), breakpoints, thread and stack frame inspection, scopes, variable evaluation, and step/pause/continue execution.
- **Integrated Git version control:** Pure-Java Git engine powered by JGit supporting local initialization, status, staging, commits, branch creation/checkout/merge, stashing, patch diffs, log history, local rebasing with conflict detection, and HTTPS remote push/pull/fetch.
- **Process-isolated shell runtime:** Executes shell scripts (`.sh`) within an Android `isolatedProcess` service under an independent OS UID with bounded I/O and wall-clock execution timeouts.
- **Workspace file manager:** Multi-project management in private storage, atomic file saves via temporary files with `fsync`, directory traversal guards, and a bounded line-aware large file pager (4 KiB to 2 MiB) to inspect large assets safely.
- **Toolchain binary audit:** Performs SHA-256 fingerprinting and ELF machine/ABI verification on user-imported toolchain executables