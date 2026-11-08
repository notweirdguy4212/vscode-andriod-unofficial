package dev.vscodeandroid.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import dev.vscodeandroid.pro.editor.CodeEditorView
import dev.vscodeandroid.pro.debug.DebugAdapterClient
import dev.vscodeandroid.pro.git.GitRepositoryService
import dev.vscodeandroid.pro.lsp.LspManager
import dev.vscodeandroid.pro.lsp.LspTextEdits
import dev.vscodeandroid.pro.lsp.LspWorkspaceEditApplier
import dev.vscodeandroid.pro.runtime.RuntimeBroker
import dev.vscodeandroid.pro.runtime.IsolatedShellRunner
import dev.vscodeandroid.pro.workspace.WorkspaceProjectStore
import dev.vscodeandroid.pro.workspace.WorkspaceRegistry
import dev.vscodeandroid.pro.toolchain.ToolchainInstaller
import dev.vscodeandroid.pro.toolchain.ToolchainAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val EditorBg = Color(0xFF1E1E1E)
private val PanelBg = Color(0xFF252526)
private val ActivityBg = Color(0xFF333333)
private val EditorText = Color(0xFFD4D4D4)
private val Accent = Color(0xFF007ACC)
private val Muted = Color(0xFF858585)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent, background = EditorBg, surface = PanelBg,
                onBackground = EditorText, onSurface = EditorText
            )) { ProWorkspace() }
        }
    }
}

private data class EditorTab(val id: Long, val name: String, val uri: Uri?, val content: String, val dirty: Boolean = false, val workspacePath: String? = null)
private data class LspDiagnostic(val severity: Int, val line: Int, val column: Int, val message: String, val raw: org.json.JSONObject)
private data class LspCompletionSuggestion(val label: String, val insertText: String, val raw: org.json.JSONObject)

@Composable
private fun ProWorkspace() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tabs by remember { mutableStateOf(listOf(EditorTab(1L, "Welcome.md", null,
        "# VS Code Android Pro\n\nWelcome! Open a file or start a new document.\n\n- Multi-tab workspace\n- Native Android file access\n- Search in the current document\n- Save and Save As\n"))) }
    var activeId by remember { mutableLongStateOf(1L) }
    var nextId by remember { mutableLongStateOf(2L) }
    var explorerVisible by remember { mutableStateOf(true) }
    var panelVisible by remember { mutableStateOf(false) }
    var panelTab by remember { mutableStateOf("TERMINAL") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready") }
    var confirmUnsafeRuntime by remember { mutableStateOf(false) }
    var activeProjectId by remember { mutableStateOf("default") }
    var projectMenuVisible by remember { mutableStateOf(false) }
    var createProjectDialog by remember { mutableStateOf(false) }
    var deleteProjectDialog by remember { mutableStateOf(false) }
    var renameProjectDialog by remember { mutableStateOf(false) }\n    var createFolderDialog by remember { mutableStateOf(false) }\n    var newFolderPath by remember { mutableStateOf("new-folder") }
    var newProjectName by remember { mutableStateOf("") }
    var projectIds by remember { mutableStateOf(emptyList<String>()) }
    var projectDisplayNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var renameProjectName by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("Markdown") }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var gitOutput by remember { mutableStateOf("No repository status loaded.") }
    var gitStagedFiles by remember { mutableStateOf(emptyList<String>()) }
    var gitChangedFiles by remember { mutableStateOf(emptyList<String>()) }
    var gitUntrackedFiles by remember { mutableStateOf(emptyList<String>()) }
    var gitConflicts by remember { mutableStateOf(emptyList<String>()) }
    var commitMessage by remember { mutableStateOf("Update project") }
    var gitAuthorName by remember { mutableStateOf("Android IDE User") }
    var gitAuthorEmail by remember { mutableStateOf("user@example.com") }
    var remoteName by remember { mutableStateOf("origin") }
    var remoteUrl by remember { mutableStateOf("") }
    var remoteBranch by remember { mutableStateOf("main") }
    var gitUsername by remember { mutableStateOf("") }
    var gitToken by remember { mutableStateOf("") }
    var branchName by remember { mutableStateOf("feature/new-work") }
    var tagName by remember { mutableStateOf("") }
    var branches by remember { mutableStateOf(emptyList<String>()) }
    var gitRemotes by remember { mutableStateOf(emptyList<GitRepositoryService.RemoteInfo>()) }
    var showBranchMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val workspaceStore = remember { WorkspaceProjectStore(context.filesDir) }
    val workspaceRegistry = remember { WorkspaceRegistry(context.filesDir) }
    val workspaceRoot = remember(workspaceStore, activeProjectId) { workspaceStore.projectWorkspace(activeProjectId) }
    LaunchedEffect(workspaceRegistry) {
        val projects = withContext(Dispatchers.IO) { workspaceRegistry.list() }
        projectIds = projects.map { it.id }
        projectDisplayNames = projects.associate { it.id to it.displayName }
    }
    val toolchainInstaller = remember { ToolchainInstaller(context.filesDir) }
    var installedTools by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(toolchainInstaller) { installedTools = withContext(Dispatchers.IO) { toolchainInstaller.installedExecutables() } }
    var workspaceEntries by remember { mutableStateOf(emptyList<WorkspaceProjectStore.WorkspaceEntry>()) }
    LaunchedEffect(workspaceStore, activeProjectId) { workspaceEntries = withContext(Dispatchers.IO) { workspaceStore.listFiles(activeProjectId) } }
    val gitService = remember { GitRepositoryService() }
    val runtimeBroker = remember { RuntimeBroker(context.filesDir) }
    val lspManager = remember { LspManager(context.filesDir) }
    val documentSyncMutex = remember { Mutex() }
    var lspSessionKey by remember { mutableStateOf<String?>(null) }
    var lspUri by remember { mutableStateOf<String?>(null) }
    var lspOutput by remember { mutableStateOf("No language server connected.") }
    var completionItems by remember { mutableStateOf(emptyList<LspCompletionSuggestion>()) }
    var lspDocuments by remember { mutableStateOf(emptyMap<Long, Pair<String, String>>()) }
    var completionStartOffset by remember { mutableIntStateOf(0) }
    var cursorLine by remember { mutableIntStateOf(0) }
    var cursorColumn by remember { mutableIntStateOf(0) }
    var diagnostics by remember { mutableStateOf(emptyList<LspDiagnostic>()) }
    var diagnosticsByTab by remember { mutableStateOf(emptyMap<Long, List<LspDiagnostic>>()) }
    var codeActions by remember { mutableStateOf(emptyList<org.json.JSONObject>()) }
    var debugClient by remember { mutableStateOf<DebugAdapterClient?>(null) }
    var debugThreadId by remember { mutableIntStateOf(0) }
    var breakpointLines by remember { mutableStateOf(emptySet<Int>()) }
    var debugExpression by remember { mutableStateOf("") }
    var debugOutput by remember { mutableStateOf("No active debug session. Import debugpy-adapter or js-debug-adapter into Toolchains.") }
    DisposableEffect(lspManager) { onDispose { lspManager.close(); debugClient?.close() } }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.first()
    LaunchedEffect(active.id, lspDocuments) {
        val binding = lspDocuments[active.id]
        lspSessionKey = binding?.first
        lspUri = binding?.second
        lspOutput = if (binding == null) "No language server connected for this tab." else "Connected: ${binding.first}"
        completionItems = emptyList()
        diagnostics = diagnosticsByTab[active.id].orEmpty()
        codeActions = emptyList()
    }

    fun workspacePathFor(tab: EditorTab): String = tab.workspacePath
        ?: tab.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun syncOpenTabsToWorkspace(openTabs: List<EditorTab>) {
        openTabs.filter { it.workspacePath != null }.forEach { tab ->
            workspaceStore.write(activeProjectId, workspacePathFor(tab), tab.content)
        }
    }

    fun refreshGit() {
        val openTabs = tabs
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                syncOpenTabsToWorkspace(openTabs)
                gitService.status(workspaceRoot)
            } }
            result.fold({ snap ->
                gitStagedFiles = snap.staged
                gitChangedFiles = snap.changed
                gitUntrackedFiles = snap.untracked
                gitConflicts = snap.conflicts
                gitOutput = "Branch: ${snap.branch}\n${if (snap.clean) "Working tree clean" else "Select files below to stage or unstage."}"
            }, { gitOutput = "Git: ${it.message ?: "operation failed"}" })
        }
    }

    fun stageGitFile(path: String) {
        scope.launch {
            gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.stage(workspaceRoot, listOf(path)); "Staged $path" }.getOrElse { "Stage failed: ${it.message}" } }
            refreshGit()
        }
    }

    fun unstageGitFile(path: String) {
        scope.launch {
            gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.unstage(workspaceRoot, listOf(path)); "Unstaged $path" }.getOrElse { "Unstage failed: ${it.message}" } }
            refreshGit()
        }
    }

    fun unstageAllGitFiles() {
        val paths = gitStagedFiles
        if (paths.isEmpty()) return
        scope.launch {
            gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.unstage(workspaceRoot, paths); "All staged changes unstaged." }.getOrElse { "Unstage all failed: ${it.message}" } }
            refreshGit()
        }
    }
    fun initializeGit() {
        val openTabs = tabs
        scope.launch {
            gitOutput = withContext(Dispatchers.IO) {
                runCatching {
                    syncOpenTabsToWorkspace(openTabs)
                    if (!java.io.File(workspaceRoot, ".git").exists()) gitService.init(workspaceRoot)
                    val snap = gitService.status(workspaceRoot)
                    "Repository ready\nBranch: ${snap.branch}\nWorking tree: ${if (snap.clean) "clean" else "has changes"}"
                }.getOrElse { "Git init failed: ${it.message}" }
            }
        }
    }
    fun loadGitMetadata() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { gitService.branches(workspaceRoot) to gitService.remotes(workspaceRoot) } }
            result.fold({ (b, r) -> branches = b; gitRemotes = r }, { gitOutput = "Git metadata: ${it.message}" })
        }
    }

    fun configureGitRemote() {
        scope.launch {
            gitOutput = withContext(Dispatchers.IO) {
                runCatching { gitService.setHttpsRemote(workspaceRoot, remoteName, remoteUrl); "Remote '$remoteName' configured." }
                    .getOrElse { "Remote configuration failed: ${it.message}" }
            }
            loadGitMetadata()
        }
    }

    fun performGitNetwork(operation: String) {
        val username = gitUsername.takeIf { it.isNotBlank() }
        val token = gitToken.takeIf { it.isNotBlank() }?.toCharArray()
        scope.launch {
            gitOutput = "Running Git $operation…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (operation) {
                        "fetch" -> { gitService.fetch(workspaceRoot, remoteName, username, token); "Fetch completed." }
                        "pull" -> gitService.pull(workspaceRoot, remoteName, remoteBranch, username, token)
                        else -> gitService.push(workspaceRoot, remoteName, remoteBranch, username, token).joinToString("\n").ifBlank { "Push completed." }
                    }
                }.getOrElse { "Git $operation failed: ${it.message}" }
            }
            token?.fill('\u0000')
            gitToken = ""
            gitOutput = result
            refreshGit()
        }
    }

    fun createGitBranch() {
        scope.launch {
            gitOutput = withContext(Dispatchers.IO) {
                runCatching { gitService.createBranch(workspaceRoot, branchName); gitService.checkout(workspaceRoot, branchName); "Created and checked out $branchName" }
                    .getOrElse { "Branch operation failed: ${it.message}" }
            }
            loadGitMetadata(); refreshGit()
        }
    }

    fun connectLanguageServer() {
        scope.launch {
            lspOutput = "Starting language server…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val safeName = workspacePathFor(active)
                    workspaceStore.write(activeProjectId, safeName, active.content)
                    val session = lspManager.open(
                        safeName, workspaceRoot, active.content,
                        onWorkspaceEditApplied = { changedPaths ->
                            Handler(Looper.getMainLooper()).post {
                                tabs = tabs.map { tab ->
                                    val path = tab.workspacePath ?: if (tab.id == active.id) safeName else null
                                    if (path != null && path in changedPaths) {
                                        val updated = runCatching { workspaceStore.read(activeProjectId, path) }.getOrNull()
                                        if (updated != null) tab.copy(content = updated, dirty = true) else tab
                                    } else tab
                                }
                                if (safeName in changedPaths) {
                                    val updated = runCatching { workspaceStore.read(activeProjectId, safeName) }.getOrNull()
                                    if (updated != null) updateActive(updated)
                                }
                            }
                        }
                    ) { method, params ->
                        if (method == "textDocument/publishDiagnostics") {
                            val raw = params.optJSONArray("diagnostics") ?: org.json.JSONArray()
                            val parsed = buildList {
                                for (i in 0 until raw.length()) {
                                    val d = raw.optJSONObject(i) ?: continue
                                    val range = d.optJSONObject("range")?.optJSONObject("start")
                                    add(LspDiagnostic(d.optInt("severity", 3), range?.optInt("line", 0) ?: 0,
                                        range?.optInt("character", 0) ?: 0, d.optString("message"), d))
                                }
                            }
                            Handler(Looper.getMainLooper()).post {
                                diagnosticsByTab = diagnosticsByTab + (active.id to parsed)
                                if (activeId == active.id) {
                                    diagnostics = parsed
                                    lspOutput = "${parsed.size} diagnostic(s) reported by server."
                                }
                            }
                        }
                    }
                    session to java.io.File(workspaceRoot, safeName).canonicalFile.toURI().toString()
                }
            }
            result.fold({ (session, uri) ->
                lspDocuments = lspDocuments + (active.id to (session to uri))
                lspSessionKey = session; lspUri = uri; lspOutput = "Connected: $session"
            },
                { lspOutput = "LSP unavailable: ${it.message}" })
        }
    }

    LaunchedEffect(active.id, active.content, cursorLine, cursorColumn, lspSessionKey, lspUri) {
        val session = lspSessionKey
        val uri = lspUri
        if (session != null && uri != null) {
            delay(280)
            val lines = active.content.split('\n')
            val line = cursorLine.coerceIn(0, lines.lastIndex)
            val column = cursorColumn.coerceIn(0, lines[line].length)
            val prefix = lines[line].take(column).takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
            val startOffset = lines.take(line).sumOf { it.length + 1 } + column - prefix.length
            withContext(Dispatchers.IO) {
                runCatching {
                    documentSyncMutex.withLock {
                    lspManager.complete(session, uri, line, column) { response ->
                        val rawItems = response.optJSONArray("items") ?: response.optJSONArray("value")
                            ?: response.optJSONObject("value")?.optJSONArray("items")
                        val parsed = buildList {
                            if (rawItems != null) for (index in 0 until rawItems.length()) {
                                val item = rawItems.optJSONObject(index) ?: continue
                                val label = item.optString("label").takeIf { it.isNotBlank() } ?: continue
                                val edit = item.optJSONObject("textEdit")
                                val insert = edit?.optString("newText")?.takeIf { it.isNotBlank() }
                                    ?: item.optString("insertText").takeIf { it.isNotBlank() } ?: label
                                add(LspCompletionSuggestion(label, insert, item))
                            }
                        }.take(20)
                        Handler(Looper.getMainLooper()).post {
                            if (activeId == active.id && cursorLine == line && cursorColumn == column && active.content == lines.joinToString("\n")) {
                                completionStartOffset = startOffset
                                completionItems = parsed
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    fun requestCodeActions() {
        val session = lspSessionKey ?: return
        val uri = lspUri ?: return
        val rawDiagnostics = org.json.JSONArray().apply { diagnostics.forEach { put(it.raw) } }
        codeActions = emptyList()
        runCatching {
            lspManager.codeActions(session, uri, cursorLine, cursorColumn, cursorLine, cursorColumn, rawDiagnostics) { response ->
                val array = response.optJSONArray("value") ?: response.optJSONArray("items") ?: response.optJSONArray("result")
                val parsed = buildList { if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(it) } }
                Handler(Looper.getMainLooper()).post {
                    codeActions = parsed.take(30)
                    lspOutput = if (parsed.isEmpty()) "No code actions available at this position." else "${parsed.size} code action(s) available."
                }
            }
        }.onFailure { lspOutput = "Code action request failed: ${it.message}" }
    }

    fun applyCodeAction(action: org.json.JSONObject) {
        val session = lspSessionKey
        val uri = lspUri
        val edit = action.optJSONObject("edit")
        if (edit == null && !action.has("command") && action.has("data") && session != null) {
            runCatching { lspManager.resolveCodeAction(session, action) { resolved ->
                Handler(Looper.getMainLooper()).post { applyCodeAction(resolved) }
            } }.onFailure { lspOutput = "Code action resolution failed: ${it.message}" }
            return
        }
        if (edit != null) {
            runCatching {
                val changedPaths = LspWorkspaceEditApplier(workspaceRoot).apply(edit,
                    read = { path -> workspaceStore.read(activeProjectId, path) },
                    write = { path, content -> workspaceStore.write(activeProjectId, path, content) },
                    validateVersion = { documentUri, version -> session != null && lspManager.documentVersion(session, documentUri) == version })
                tabs = tabs.map { tab ->
                    val path = tab.workspacePath
                    if (path != null && path in changedPaths) tab.copy(content = workspaceStore.read(activeProjectId, path), dirty = true) else tab
                }
                val activePath = active.workspacePath ?: workspacePathFor(active)
                if (activePath in changedPaths) {
                    val refreshed = workspaceStore.read(activeProjectId, activePath)
                    updateActive(refreshed, syncLsp = false)
                }
                changedPaths.forEach { path ->
                    val fileUri = java.io.File(workspaceRoot, path).canonicalFile.toURI().toString()
                    lspDocuments.forEach { (_, binding) ->
                        if (binding.second == fileUri) runCatching { lspManager.change(binding.first, fileUri, workspaceStore.read(activeProjectId, path)) }
                    }
                }
            }.fold({ lspOutput = "Applied workspace edit: ${action.optString("title")}" },
                { failure -> lspOutput = "Could not apply server edit: ${failure.message}" })
        }
        val command = action.optJSONObject("command")
        val commandName = command?.optString("command") ?: action.optString("command").takeIf { it.isNotBlank() }
        if (commandName != null && session != null) {
            val args = command?.optJSONArray("arguments") ?: action.optJSONArray("arguments") ?: org.json.JSONArray()
            runCatching { lspManager.executeCommand(session, commandName, args) { } }
                .onFailure { lspOutput = "Command failed: ${it.message}" }
        }
        codeActions = emptyList()
    }

    fun requestFormatting() {
        val session = lspSessionKey ?: return
        val uri = lspUri ?: return
        runCatching {
            lspManager.formatting(session, uri) { response ->
                val edits = response.optJSONArray("value") ?: response.optJSONArray("result")
                if (edits != null) {
                    val updated = runCatching { LspTextEdits.apply(active.content, edits) }.getOrNull()
                    if (updated != null) Handler(Looper.getMainLooper()).post {
                        updateActive(updated)
                        lspOutput = "Document formatting applied."
                    }
                }
            }
        }.onFailure { lspOutput = "Formatting request failed: ${it.message}" }
    }

    fun toggleBreakpointAtCursor() {
        val line = cursorLine + 1
        breakpointLines = if (line in breakpointLines) breakpointLines - line else breakpointLines + line
        debugOutput = "Breakpoints: ${breakpointLines.sorted().joinToString().ifBlank { "none" }}"
        val client = debugClient ?: return
        val file = java.io.File(workspaceRoot, workspacePathFor(active)).canonicalFile
        runCatching { client.setBreakpoints(file, breakpointLines.sorted()) { response ->
            Handler(Looper.getMainLooper()).post {
                val body = response.optJSONObject("body")?.optJSONArray("breakpoints")
                debugOutput = if (response.optBoolean("success", false)) "${body?.length() ?: 0} breakpoint(s) acknowledged" else "Breakpoint update failed: ${response.optString("message")}" 
            }
        } }.onFailure { debugOutput = "Breakpoint update failed: ${it.message}" }
    }

    fun setDebugBreakpoints() {
        val client = debugClient ?: return
        val file = java.io.File(workspaceRoot, workspacePathFor(active)).canonicalFile
        runCatching { client.setBreakpoints(file, breakpointLines.sorted()) { response ->
            Handler(Looper.getMainLooper()).post {
                val count = response.optJSONObject("body")?.optJSONArray("breakpoints")?.length() ?: 0
                debugOutput = if (response.optBoolean("success", false)) "$count breakpoint(s) acknowledged" else "Breakpoint update failed: ${response.optString("message")}" 
            }
        } }.onFailure { debugOutput = "Breakpoint update failed: ${it.message}" }
    }

    fun evaluateDebugExpression() {
        val client = debugClient ?: return
        runCatching { client.evaluate(debugExpression) { response ->
            Handler(Looper.getMainLooper()).post {
                val body = response.optJSONObject("body")
                debugOutput = if (response.optBoolean("success", false)) body?.optString("result").orEmpty()
                    else response.optString("message", "Evaluation failed")
            }
        } }.onFailure { debugOutput = "Evaluation failed: ${it.message}" }
    }

    fun inspectDebugStack() {
        val client = debugClient ?: return
        client.control("threads") { response ->
            val threads = response.optJSONObject("body")?.optJSONArray("threads")
            val thread = threads?.optJSONObject(0)
            if (thread == null) {
                Handler(Looper.getMainLooper()).post { debugOutput = "No debugger thread is available." }
                return@control
            }
            val id = thread.optInt("id", 0)
            debugThreadId = id
            client.control("stackTrace", id) { stack ->
                val frames = stack.optJSONObject("body")?.optJSONArray("stackFrames")
                val frame = frames?.optJSONObject(0)
                Handler(Looper.getMainLooper()).post {
                    debugOutput = if (frame == null) "Thread ${thread.optString("name")} has no stack frame." else
                        "${thread.optString("name")}\n${frame.optString("name")} — ${frame.optJSONObject("source")?.optString("path") ?: "unknown source"}:${frame.optInt("line", 0)}"
                }
            }
        }
    }

    fun inspectDebugVariables() {
        val client = debugClient ?: return
        client.control("threads") threadsCallback@{ threadResponse ->
            val thread = threadResponse.optJSONObject("body")?.optJSONArray("threads")?.optJSONObject(0)
            if (thread == null) {
                Handler(Looper.getMainLooper()).post { debugOutput = "No debugger thread is available." }
                return@threadsCallback
            }
            client.control("stackTrace", thread.optInt("id", 0)) stackCallback@{ stackResponse ->
                val frame = stackResponse.optJSONObject("body")?.optJSONArray("stackFrames")?.optJSONObject(0)
                if (frame == null) {
                    Handler(Looper.getMainLooper()).post { debugOutput = "No stack frame is available." }
                    return@stackCallback
                }
                client.control("scopes", frame.optInt("id", 0)) scopesCallback@{ scopeResponse ->
                    val scopes = scopeResponse.optJSONObject("body")?.optJSONArray("scopes")
                    val scope = scopes?.optJSONObject(0)
                    if (scope == null) {
                        Handler(Looper.getMainLooper()).post { debugOutput = "No variables are available in this frame." }
                        return@scopesCallback
                    }
                    client.control("variables", scope.optInt("variablesReference", 0)) variablesCallback@{ variablesResponse ->
                        val variables = variablesResponse.optJSONObject("body")?.optJSONArray("variables")
                        val rendered = buildList {
                            if (variables != null) for (index in 0 until minOf(variables.length(), 60)) {
                                val item = variables.optJSONObject(index) ?: continue
                                add("${item.optString("name")} = ${item.optString("value")}")
                            }
                        }
                        Handler(Looper.getMainLooper()).post { debugOutput = rendered.joinToString("\n").ifBlank { "No variables in scope." } }
                    }
                }
            }
        }
    }

    fun startDebugSession() {
        val extension = active.name.substringAfterLast('.', "").lowercase()
        val adapterName = when (extension) { "py" -> "debugpy-adapter"; "js", "mjs", "cjs", "ts", "tsx" -> "js-debug-adapter"; else -> {
            debugOutput = "Debug launch is configured for Python and JavaScript adapters only."; panelTab = "DEBUG"; panelVisible = true; return
        } }
        scope.launch {
            debugOutput = "Starting $adapterName…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val relative = workspacePathFor(active)
                    workspaceStore.write(activeProjectId, relative, active.content)
                    val adapter = java.io.File(java.io.File(context.filesDir, "toolchains"), adapterName)
                    var adapterClient: DebugAdapterClient? = null
                    val client = DebugAdapterClient(adapter, emptyList(), workspaceRoot) { event, body ->
                        if (event == "initialized") {
                            val target = java.io.File(workspaceRoot, relative)
                            adapterClient?.setBreakpoints(target, breakpointLines.sorted()) { bp ->
                                if (bp.optBoolean("success", false)) adapterClient?.control("configurationDone")
                                else Handler(Looper.getMainLooper()).post { debugOutput = "Breakpoint setup failed: ${bp.optString("message")}" }
                            }
                        }
                        Handler(Looper.getMainLooper()).post {
                            when (event) {
                                "stopped" -> {
                                    debugThreadId = body.optInt("threadId", 0)
                                    debugOutput = "Paused: ${body.optString("reason", "breakpoint")}"
                                }
                                "continued" -> debugOutput = "Execution continued"
                                "output" -> debugOutput += "\n${body.optString("output")}".takeLast(12_000)
                                "terminated", "exited" -> debugOutput = "Debug session ended"
                                else -> debugOutput = "DAP event: $event"
                            }
                        }
                    }
                    adapterClient = client
                    client.start()
                    val adapterId = if (extension == "py") "python" else "pwa-node"
                    client.initialize(adapterId) { initialized ->
                        if (!initialized.optBoolean("success", false)) {
                            Handler(Looper.getMainLooper()).post { debugOutput = "Adapter initialization failed: ${initialized.optString("message")}" }
                        } else {
                            val adapterArguments = if (extension == "py") org.json.JSONObject()
                                .put("type", "python").put("request", "launch").put("justMyCode", true)
                            else org.json.JSONObject().put("type", "pwa-node").put("request", "launch")
                            client.launch(java.io.File(workspaceRoot, relative), stopOnEntry = true, adapterArguments = adapterArguments) { launched ->
                                Handler(Looper.getMainLooper()).post {
                                    debugOutput = if (launched.optBoolean("success", false)) "Debug session launched; waiting for stop/event." else "Launch failed: ${launched.optString("message")}" 
                                }
                            }
                        }
                    }
                    client
                }
            }
            result.fold({ client -> debugClient?.close(); debugClient = client; debugOutput = "Adapter connected; initializing…" },
                { debugOutput = "Debug adapter unavailable: ${it.message}" })
            panelTab = "DEBUG"; panelVisible = true
        }
    }

    fun debugControl(command: String) {
        val client = debugClient ?: run { debugOutput = "Start a debug session first."; return }
        runCatching { client.control(command, debugThreadId.takeIf { it > 0 }) { response ->
            Handler(Looper.getMainLooper()).post {
                if (!response.optBoolean("success", false)) debugOutput = "$command failed: ${response.optString("message")}" 
                else debugOutput = "$command requested"
            }
        } }.onFailure { debugOutput = "$command failed: ${it.message}" }
    }

    fun runActiveFile(allowUnsafeRuntime: Boolean = false) {
        val extension = active.name.substringAfterLast('.', "").lowercase()
        if (extension == "sh") {
            scope.launch {
                status = "Running shell script in Android isolated UID…"
                val result = IsolatedShellRunner.run(context, active.content, timeoutSeconds = 15)
                gitOutput = "Android isolated-process UID: ${result.executionUid} (app UID: ${android.os.Process.myUid()})\nExit: ${result.exitCode}${if (result.timedOut) " (timeout)" else ""}\n${result.stdout}\n${result.stderr}"
                panelTab = "OUTPUT"; panelVisible = true
                status = "Isolated shell execution finished"
            }
            return
        }
        val runtime = when (extension) {
            "py" -> "python"; "js","mjs","cjs" -> "node"; "java" -> "java"
            else -> { status = "Run supports .sh (isolated), .py, .js and .java"; return }
        }
        if (!allowUnsafeRuntime) {
            confirmUnsafeRuntime = true
            return
        }
        scope.launch {
            status = "Preparing $runtime run…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val safeName = workspacePathFor(active)
                    workspaceStore.write(activeProjectId, safeName, active.content)
                    runtimeBroker.run(runtime, activeProjectId, safeName, timeoutSeconds = 15)
                }
            }
            gitOutput = result.fold({ r ->
                "Exit: ${r.exitCode ?: "timeout"}${if (r.timedOut) " (terminated after timeout)" else ""}\n${r.stdout}\n${r.stderr}"
            }, { "Run unavailable: ${it.message}" })
            panelTab = "OUTPUT"; panelVisible = true
            status = if (result.isSuccess) "Execution finished" else "Runtime unavailable"
        }
    }

    fun updateActive(content: String, syncLsp: Boolean = true) {
        val target = tabs.firstOrNull { it.id == activeId } ?: return
        tabs = tabs.map { if (it.id == activeId) it.copy(content = content, dirty = true) else it }
        if (syncLsp) {
            val binding = lspDocuments[target.id]
            if (binding != null) {
                val projectId = activeProjectId
                val path = target.workspacePath ?: workspacePathFor(target)
                scope.launch(Dispatchers.IO) {
                    documentSyncMutex.withLock {
                        workspaceStore.write(projectId, path, content)
                        lspManager.change(binding.first, binding.second, content)
                    }
                }
            }
        }
        status = "Modified"
    }
    fun insertCompletion(suggestion: LspCompletionSuggestion) {
        val textEdit = suggestion.raw.optJSONObject("textEdit")
        val insertText = if (suggestion.raw.optInt("insertTextFormat", 1) == 2)
            LspTextEdits.plainSnippet(suggestion.insertText) else suggestion.insertText
        val updated = runCatching {
            if (textEdit != null) {
                val edit = org.json.JSONObject(textEdit.toString()).put("newText", insertText)
                LspTextEdits.apply(active.content, org.json.JSONArray().put(edit))
            } else {
                val start = completionStartOffset.coerceIn(0, active.content.length)
                val end = (start + active.content.substring(start).takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }.length).coerceAtMost(active.content.length)
                active.content.substring(0, start) + insertText + active.content.substring(end)
            }
        }.getOrElse { active.content }
        updateActive(updated)
        completionItems = emptyList()
        lspOutput = "Inserted completion: ${suggestion.label}"
    }
    val toolchainFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                status = "Importing toolchains…"
                val result = withContext(Dispatchers.IO) { runCatching { toolchainInstaller.importDirectory(context, uri) } }
                result.fold({ summary ->
                    installedTools = withContext(Dispatchers.IO) { toolchainInstaller.installedExecutables() }
                    val audit = withContext(Dispatchers.IO) { toolchainInstaller.auditInstalledToolchains() }
                    val abi = audit.joinToString("; ") { finding ->
                        val compatibility = when {
                            finding.kind == "script-or-data" -> "script/data; dependencies unchecked"
                            finding.compatible -> "ELF ABI match"
                            else -> "ELF ABI mismatch"
                        }
                        "${finding.name}: $compatibility, SHA-256 ${finding.sha256.take(12)}"
                    }
                    status = "Imported ${summary.files} toolchain files; detected: ${summary.executables.joinToString().ifBlank { "none" }}. $abi. Authenticity and dynamic dependencies are not verified."
                }, { status = "Toolchain import failed: ${it.message}" })
            }
        }
    }
    val projectFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                status = "Importing project…"
                val result = withContext(Dispatchers.IO) {
                    runCatching { workspaceStore.importTree(context, uri, projectId = activeProjectId) }
                }
                result.fold({ summary ->
                    workspaceEntries = withContext(Dispatchers.IO) { workspaceStore.listFiles(activeProjectId) }
                    status = "Imported ${summary.files} files (${summary.skipped} skipped)"
                }, { status = "Project import failed: ${it.message}" })
            }
        }
    }
    fun openWorkspaceFile(relativePath: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { workspaceStore.read(activeProjectId, relativePath) } }
            result.fold({ body ->
                val existing = tabs.firstOrNull { it.workspacePath == relativePath }
                if (existing != null) activeId = existing.id else {
                    val tab = EditorTab(nextId, relativePath.substringAfterLast('/'), null, body, workspacePath = relativePath)
                    tabs = tabs + tab; activeId = nextId; nextId++
                }
                language = languageFor(relativePath)
                status = "Opened $relativePath"
            }, { status = "Unable to open file: ${it.message}" })
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch (_: Exception) {}
            val body = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Untitled"
            val existing = tabs.firstOrNull { it.uri == uri }
            if (existing != null) activeId = existing.id else {
                val tab = EditorTab(nextId, name, uri, body)
                tabs = tabs + tab; activeId = nextId; nextId++
            }
            status = "Opened $name"
            language = languageFor(name)
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(active.content) }
                    ?: error("Unable to open destination")
            }.isSuccess
            if (ok) {
                tabs = tabs.map { if (it.id == activeId) it.copy(uri = uri, dirty = false) else it }
                lspDocuments[activeId]?.let { (session, documentUri) -> runCatching { lspManager.save(session, documentUri, active.content) } }
                status = "Saved ${active.name}"
            } else status = "Save failed"
        }
    }

    if (confirmUnsafeRuntime) {
        AlertDialog(
            onDismissRequest = { confirmUnsafeRuntime = false },
            title = { Text("Run without OS isolation?") },
            text = { Text("This Python, Node or Java process runs under the app UID and may access app-private project files, credentials and the network (the app has Internet permission). Android isolated-process execution is currently available only for .sh scripts. Continue only with trusted code.") },
            confirmButton = { TextButton(onClick = { confirmUnsafeRuntime = false; runActiveFile(allowUnsafeRuntime = true) }) { Text("Run trusted code") } },
            dismissButton = { TextButton(onClick = { confirmUnsafeRuntime = false }) { Text("Cancel") } }
        )
    }

    if (createProjectDialog) {
        AlertDialog(
            onDismissRequest = { createProjectDialog = false },
            title = { Text("Create project") },
            text = { OutlinedTextField(newProjectName, { newProjectName = it }, singleLine = true, label = { Text("Project name") }) },
            confirmButton = { TextButton(onClick = {
                runCatching { workspaceRegistry.create(newProjectName.trim()) }.fold({ created ->
                    lspManager.close()
                    lspDocuments = emptyMap()
                    activeProjectId = created.id
                    val registered = workspaceRegistry.list()
                    projectIds = registered.map { it.id }
                    projectDisplayNames = registered.associate { it.id to it.displayName }
                    tabs = listOf(EditorTab(nextId++, "Welcome.md", null, "# ${created.displayName}\n\n"))
                    activeId = tabs.first().id
                    workspaceEntries = emptyList()
                    lspSessionKey = null; lspUri = null; completionItems = emptyList(); diagnostics = emptyList(); diagnosticsByTab = emptyMap()
                    lspManager.close(); debugClient?.close(); debugClient = null
                    status = "Created project ${created.displayName}"
                    createProjectDialog = false
                }, { status = it.message ?: "Unable to create project" })
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { createProjectDialog = false }) { Text("Cancel") } }
        )
    }

    if (renameProjectDialog) {
        AlertDialog(
            onDismissRequest = { renameProjectDialog = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(renameProjectName, { renameProjectName = it }, singleLine = true, label = { Text("Display name") }) },
            confirmButton = { TextButton(onClick = {
                runCatching { workspaceRegistry.rename(activeProjectId, renameProjectName) }.fold({ renamed ->
                    projectDisplayNames = workspaceRegistry.list().associate { it.id to it.displayName }
                    status = "Renamed project to ${renamed.displayName}"
                    renameProjectDialog = false
                }, { status = it.message ?: "Unable to rename project" })
            }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameProjectDialog = false }) { Text("Cancel") } }
        )
    }

    if (createFolderDialog) {
        AlertDialog(
            onDismissRequest = { createFolderDialog = false },
            title = { Text("Create workspace folder") },
            text = {
                OutlinedTextField(
                    value = newFolderPath,
                    onValueChange = { newFolderPath = it },
                    label = { Text("Folder path") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { workspaceStore.createDirectory(activeProjectId, newFolderPath.trim()) }
                        .onSuccess {
                            createFolderDialog = false
                            scope.launch { workspaceEntries = withContext(Dispatchers.IO) { workspaceStore.listFiles(activeProjectId) } }
                            status = "Folder created"
                        }.onFailure { status = it.message ?: "Unable to create folder" }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createFolderDialog = false }) { Text("Cancel") } }
        )
    }

    if (deleteProjectDialog) {
        AlertDialog(
            onDismissRequest = { deleteProjectDialog = false },
            title = { Text("Delete project") },
            text = { Text("Permanently delete '$activeProjectId' and its local files? This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                runCatching { lspManager.close(); debugClient?.close(); workspaceRegistry.delete(activeProjectId) }.fold({
                    lspManager.close()
                    lspDocuments = emptyMap()
                    activeProjectId = "default"
                    tabs = listOf(EditorTab(nextId++, "Welcome.md", null, "# Default project\n"))
                    activeId = tabs.first().id
                    lspSessionKey = null; lspUri = null; debugClient = null; diagnosticsByTab = emptyMap()
                    val registered = workspaceRegistry.list()
                    projectIds = registered.map { it.id }
                    projectDisplayNames = registered.associate { it.id to it.displayName }
                    workspaceEntries = workspaceStore.listFiles("default")
                    status = "Project deleted"
                }, { status = it.message ?: "Unable to delete project" })
                deleteProjectDialog = false
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteProjectDialog = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().background(EditorBg)) {
        Row(Modifier.fillMaxWidth().height(48.dp).background(PanelBg), verticalAlignment = Alignment.CenterVertically) {
            Text("⟨⟩", color = Color(0xFF29B6F6), fontSize = 23.sp, modifier = Modifier.padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text("VS Code Android Pro", color = EditorText, fontSize = 14.sp)
                Text("Unofficial community IDE", color = Muted, fontSize = 9.sp)
            }
            Box {
                Text("${projectDisplayNames[activeProjectId] ?: activeProjectId}  ▾", color = Color(0xFF9CDCFE), fontSize = 11.sp,
                    modifier = Modifier.clickable { projectMenuVisible = true }.padding(horizontal = 6.dp, vertical = 8.dp))
                DropdownMenu(expanded = projectMenuVisible, onDismissRequest = { projectMenuVisible = false }) {
                    projectIds.forEach { id ->
                        DropdownMenuItem(text = { Text(projectDisplayNames[id] ?: id) }, onClick = {
                            lspManager.close()
                            lspDocuments = emptyMap()
                            activeProjectId = id
                            projectMenuVisible = false
                            tabs = listOf(EditorTab(nextId++, "Welcome.md", null, "# $id\n\nProject workspace\n"))
                            activeId = tabs.first().id
                            lspSessionKey = null; lspUri = null; completionItems = emptyList(); diagnostics = emptyList(); diagnosticsByTab = emptyMap()
                            lspManager.close(); debugClient?.close(); debugClient = null
                            status = "Opened project $id"
                        })
                    }
                    Divider()
                    DropdownMenuItem(text = { Text("＋ New project") }, onClick = {
                        projectMenuVisible = false; newProjectName = ""; createProjectDialog = true
                    })
                    DropdownMenuItem(text = { Text("Rename current project") }, onClick = {
                        projectMenuVisible = false
                        renameProjectName = projectDisplayNames[activeProjectId] ?: activeProjectId
                        renameProjectDialog = true
                    })
                    if (activeProjectId != "default") DropdownMenuItem(text = { Text("Delete current project") }, onClick = {
                        projectMenuVisible = false; deleteProjectDialog = true
                    })
                }
            }
            IconButton(onClick = { projectFolderLauncher.launch(null) }) { Icon(Icons.Default.Folder, "Import project folder", tint = EditorText) }
            IconButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, "Open file", tint = EditorText) }
            IconButton(onClick = {
                val target = active.uri
                if (active.workspacePath != null) {
                    val saved = runCatching { workspaceStore.write(activeProjectId, active.workspacePath, active.content) }.isSuccess
                    if (saved) {
                        tabs = tabs.map { if (it.id == activeId) it.copy(dirty = false) else it }
                        lspDocuments[activeId]?.let { (session, documentUri) -> runCatching { lspManager.save(session, documentUri, active.content) } }
                        status = "Saved ${active.workspacePath}"
                    } else status = "Save failed"
                } else if (target == null) saveLauncher.launch(active.name)
                else {
                    val saved = runCatching {
                        context.contentResolver.openOutputStream(target, "wt")?.bufferedWriter()?.use { it.write(active.content) }
                            ?: error("Unable to open destination")
                    }.isSuccess
                    if (saved) {
                        tabs = tabs.map { if (it.id == activeId) it.copy(dirty = false) else it }
                        lspDocuments[activeId]?.let { (session, documentUri) -> runCatching { lspManager.save(session, documentUri, active.content) } }
                        status = "Saved ${active.name}"
                    } else status = "Save failed — use Save As"
                }
            }) { Icon(Icons.Default.Save, "Save", tint = EditorText) }
            IconButton(onClick = { searchVisible = !searchVisible }) { Icon(Icons.Default.Search, "Find", tint = EditorText) }
            IconButton(onClick = { panelVisible = !panelVisible }) { Icon(Icons.Default.Terminal, "Toggle panel", tint = EditorText) }
        }
        if (searchVisible) {
            Column(Modifier.fillMaxWidth().background(PanelBg).padding(8.dp)) {
                OutlinedTextField(searchText, { searchText = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(replaceText, { replaceText = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (searchText.isNotEmpty()) {
                            val count = active.content.windowed(searchText.length).count { it == searchText }
                            status = "$count match(es)"
                        }
                    }) { Text("Find all") }
                    TextButton(onClick = {
                        if (searchText.isNotEmpty()) updateActive(active.content.replace(searchText, replaceText))
                    }) { Text("Replace all") }
                    TextButton(onClick = { searchVisible = false }) { Text("Close") }
                }
            }
        }
        Row(Modifier.weight(1f)) {
            Column(Modifier.width(46.dp).fillMaxHeight().background(ActivityBg), horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { explorerVisible = !explorerVisible }) { Icon(Icons.Default.Folder, "Explorer", tint = if (explorerVisible) Color.White else Muted) }
                IconButton(onClick = { searchVisible = true }) { Icon(Icons.Default.Search, "Search", tint = Muted) }
                IconButton(onClick = { panelTab = "GIT"; panelVisible = true; refreshGit() }) { Icon(Icons.Default.AccountTree, "Source control", tint = Muted) }
                IconButton(onClick = { runActiveFile() }) { Icon(Icons.Default.PlayArrow, "Run", tint = Muted) }
                IconButton(onClick = { startDebugSession() }) { Icon(Icons.Default.BugReport, "Debug", tint = Muted) }
                IconButton(onClick = { status = "Extension host is not included in this starter" }) { Icon(Icons.Default.Extension, "Extensions", tint = Muted) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { toolchainFolderLauncher.launch(null) }) { Icon(Icons.Default.Settings, "Install runtimes and language servers", tint = Muted) }
            }
            if (explorerVisible) {
                Column(Modifier.width(154.dp).fillMaxHeight().background(PanelBg).padding(9.dp)) {
                    Text("EXPLORER", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("⌄  OPEN EDITORS", color = EditorText, fontSize = 10.sp)
                    tabs.forEach { tab ->
                        Text((if (tab.id == activeId) "› " else "  ") + tab.name + if (tab.dirty) " ●" else "",
                            color = if (tab.id == activeId) Color(0xFF9CDCFE) else EditorText,
                            fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { activeId = tab.id }.padding(vertical = 6.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("WORKSPACE", color = EditorText, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            scope.launch { workspaceEntries = withContext(Dispatchers.IO) { workspaceStore.listFiles(activeProjectId) } }
                        }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, "Refresh workspace", tint = Muted) }
                    }
                    workspaceEntries.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (entry.directory) "▸ " else "  ") + entry.relativePath,
                                color = if (entry.directory) Muted else EditorText, fontSize = 10.sp,
                                modifier = Modifier.weight(1f).clickable(enabled = !entry.directory) { openWorkspaceFile(entry.relativePath) }.padding(vertical = 5.dp))
                            if (!entry.directory) {
                                IconButton(onClick = {
                                    val project = activeProjectId
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { workspaceStore.delete(project, entry.relativePath) } }
                                            .onSuccess {
                                                workspaceEntries = withContext(Dispatchers.IO) { workspaceStore.listFiles(project) }
                                                status = "Deleted ${entry.relativePath}"
                                            }.onFailure { status = it.message ?: "Unable to delete file" }
                                    }
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, "Delete ${entry.relativePath}", tint = Muted, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("＋  NEW FOLDER", color = Color(0xFF9CDCFE), fontSize = 11.sp,
                        modifier = Modifier.clickable { newFolderPath = "new-folder"; createFolderDialog = true }.padding(vertical = 6.dp))
                    Text("＋  NEW FILE", color = Color(0xFF9CDCFE), fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            val name = "Untitled-${nextId}.txt"
                            runCatching { workspaceStore.createFile(activeProjectId, name) }.onSuccess {
                                val t = EditorTab(nextId, name, null, "", workspacePath = name)
                                tabs = tabs + t; activeId = nextId; nextId++; language = "Plain Text"
                                scope.launch { workspaceEntries = withContext(Dispatchers.IO) { workspaceStore.listFiles(activeProjectId) } }
                            }.onFailure { status = it.message ?: "Unable to create file" }
                        }.padding(vertical = 6.dp))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(40.dp).background(PanelBg).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { tab ->
                        Row(Modifier.background(if (tab.id == activeId) EditorBg else PanelBg)
                            .clickable { activeId = tab.id; language = languageFor(tab.name) }.padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text((if (tab.dirty) "● " else "") + tab.name, color = if (tab.id == activeId) Color(0xFF9CDCFE) else Muted, fontSize = 11.sp)
                            Text(" × ", color = Muted, modifier = Modifier.clickable {
                                if (tab.dirty) {
                                    status = "Save ${tab.name} before closing"
                                } else {
                                lspDocuments[tab.id]?.let { (session, uri) ->
                                    scope.launch(Dispatchers.IO) { lspManager.closeDocument(session, uri) }
                                    lspDocuments = lspDocuments - tab.id
                                }
                                diagnosticsByTab = diagnosticsByTab - tab.id
                                val remaining = tabs.filterNot { it.id == tab.id }
                                tabs = if (remaining.isEmpty()) listOf(EditorTab(nextId, "Welcome.md", null, "# Welcome\n")) else remaining
                                if (activeId == tab.id) {
                                    activeId = tabs.first().id
                                    language = languageFor(tabs.first().name)
                                }
                                }
                            })
                        }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        key(active.id) {
                            CodeEditorView(
                                text = active.content,
                                fileName = active.name,
                                languageName = language,
                                onTextChanged = { changed ->
                                    if (changed != active.content) updateActive(changed)
                                },
                                onCursorChanged = { line, column ->
                                    cursorLine = line
                                    cursorColumn = column
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (completionItems.isNotEmpty()) {
                            Column(Modifier.align(Alignment.TopStart).width(300.dp).heightIn(max = 168.dp)
                                .background(PanelBg, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(6.dp)) {
                                completionItems.take(10).forEach { suggestion ->
                                    Text(suggestion.label, color = Color(0xFF9CDCFE), fontSize = 12.sp,
                                        modifier = Modifier.fillMaxWidth().clickable { insertCompletion(suggestion) }.padding(vertical = 5.dp, horizontal = 4.dp))
                                }
                            }
                        }
                    }
                }
                if (panelVisible) {
                    Column(Modifier.fillMaxWidth().height(300.dp).background(PanelBg).verticalScroll(rememberScrollState()).padding(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf("PROBLEMS", "OUTPUT", "TERMINAL", "GIT", "DEBUG").forEach { label ->
                                Text(label, color = if (panelTab == label) Color.White else Muted, fontSize = 10.sp,
                                    modifier = Modifier.clickable { panelTab = label }.padding(end = 12.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { panelVisible = false }) { Icon(Icons.Default.Close, null, tint = Muted) }
                        }
                        if (panelTab == "GIT") {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { initializeGit() }) { Text("Init") }
                                TextButton(onClick = { refreshGit(); loadGitMetadata() }) { Text("Refresh") }
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.stash(workspaceRoot, "IDE stash"); "Changes stashed." }.getOrElse { it.message ?: "Stash failed" } }; refreshGit() } }) { Text("Stash") }
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.applyStash(workspaceRoot); "Latest stash applied." }.getOrElse { it.message ?: "Apply stash failed" } }; refreshGit() } }) { Text("Apply stash") }
                                TextButton(onClick = {
                                    val openTabs = tabs
                                    scope.launch {
                                        gitOutput = withContext(Dispatchers.IO) {
                                            runCatching {
                                                syncOpenTabsToWorkspace(openTabs)
                                                gitService.stageAll(workspaceRoot)
                                                gitService.commit(workspaceRoot, commitMessage, gitAuthorName, gitAuthorEmail)
                                                "Commit created successfully\n" + gitService.status(workspaceRoot).let { "Branch: ${it.branch}\nClean: ${it.clean}" }
                                            }.getOrElse { "Commit failed: ${it.message}" }
                                        }
                                    }
                                }) { Text("Commit all") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.stageAll(workspaceRoot); "All changes staged." }.getOrElse { "Stage all failed: ${it.message}" } }; refreshGit() } }) { Text("Stage all") }
                                TextButton(onClick = { unstageAllGitFiles() }, enabled = gitStagedFiles.isNotEmpty()) { Text("Unstage all") }
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.diffPatch(workspaceRoot, staged = true) }.getOrElse { "Diff failed: ${it.message}" } } } }) { Text("Staged diff") }
                            }
                            Text("STAGED", color = Muted, fontSize = 9.sp)
                            gitStagedFiles.take(20).forEach { path -> Text("−  $path", color = Color(0xFF9CDCFE), fontSize = 10.sp, modifier = Modifier.clickable { unstageGitFile(path) }) }
                            Text("CHANGES", color = Muted, fontSize = 9.sp)
                            (gitChangedFiles + gitUntrackedFiles).distinct().take(20).forEach { path -> Text("＋  $path", color = EditorText, fontSize = 10.sp, modifier = Modifier.clickable { stageGitFile(path) }) }
                            if (gitConflicts.isNotEmpty()) {
                                Text("MERGE CONFLICTS", color = Color(0xFFF48771), fontSize = 9.sp)
                                gitConflicts.forEach { path ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(path, color = Color(0xFFF48771), fontSize = 10.sp, modifier = Modifier.weight(1f).clickable { openWorkspaceFile(path) })
                                        TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.markConflictResolved(workspaceRoot, path); "Marked $path resolved" }.getOrElse { "Resolve failed: ${it.message}" } }; refreshGit() } }) { Text("Mark resolved") }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(gitAuthorName, { gitAuthorName = it }, singleLine = true, label = { Text("Author name") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(gitAuthorEmail, { gitAuthorEmail = it }, singleLine = true, label = { Text("Author email") }, modifier = Modifier.weight(1f))
                            }
                            OutlinedTextField(commitMessage, { commitMessage = it }, singleLine = true, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(branchName, { branchName = it }, singleLine = true, label = { Text("New branch") }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { createGitBranch() }) { Text("Create") }
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.merge(workspaceRoot, branchName) }.getOrElse { "Merge failed: ${it.message}" } }; refreshGit() } }) { Text("Merge") }
                                Box {
                                    TextButton(onClick = { loadGitMetadata(); showBranchMenu = true }) { Text("Branches ▾") }
                                    DropdownMenu(expanded = showBranchMenu, onDismissRequest = { showBranchMenu = false }) {
                                        branches.forEach { branch -> DropdownMenuItem(text = { Text(branch) }, onClick = {
                                            showBranchMenu = false
                                            scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.checkout(workspaceRoot, branch); "Checked out $branch" }.getOrElse { it.message ?: "Checkout failed" } }; refreshGit() }
                                        }) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(tagName, { tagName = it }, singleLine = true, label = { Text("Tag name") }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.createTag(workspaceRoot, tagName); "Created tag $tagName" }.getOrElse { "Tag failed: ${it.message}" } }; loadGitMetadata() } }) { Text("Create tag") }
                                TextButton(onClick = { scope.launch { gitOutput = withContext(Dispatchers.IO) { runCatching { gitService.tags(workspaceRoot).joinToString() }.getOrElse { it.message ?: "Tag listing failed" } } } }) { Text("List tags") }
                            }
                            OutlinedTextField(remoteUrl, { remoteUrl = it }, singleLine = true, label = { Text("HTTPS remote URL") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(remoteName, { remoteName = it }, singleLine = true, label = { Text("Remote") }, modifier = Modifier.weight(0.7f))
                                OutlinedTextField(remoteBranch, { remoteBranch = it }, singleLine = true, label = { Text("Remote branch") }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { configureGitRemote() }) { Text("Set URL") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(gitUsername, { gitUsername = it }, singleLine = true, label = { Text("HTTPS user") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(gitToken, { gitToken = it }, singleLine = true, label = { Text("Token (memory only)") }, modifier = Modifier.weight(1f), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { performGitNetwork("fetch") }) { Text("Fetch") }
                                TextButton(onClick = { performGitNetwork("pull") }) { Text("Pull") }
                                TextButton(onClick = { performGitNetwork("push") }) { Text("Push") }
                            }
                            Text("Remotes: ${gitRemotes.joinToString { "${it.name}=${it.fetchUrl}" }.ifBlank { "none" }}", color = Muted, fontSize = 9.sp)
                            Text(gitOutput, color = Color(0xFF9CDCFE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        } else if (panelTab == "PROBLEMS") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { connectLanguageServer() }) { Text("Connect LSP") }
                                TextButton(onClick = { requestCodeActions() }, enabled = lspSessionKey != null) { Text("Code actions") }
                                TextButton(onClick = { requestFormatting() }, enabled = lspSessionKey != null) { Text("Format") }
                            }
                            Text(lspOutput, color = Color(0xFF9CDCFE), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            diagnostics.take(8).forEach { diagnostic ->
                                val severity = when (diagnostic.severity) { 1 -> "Error"; 2 -> "Warning"; 3 -> "Info"; else -> "Hint" }
                                Text("$severity  ${diagnostic.line + 1}:${diagnostic.column + 1}  ${diagnostic.message}",
                                    color = if (diagnostic.severity == 1) Color(0xFFF48771) else Color(0xFFCEB67A), fontSize = 10.sp)
                            }
                            completionItems.forEach { suggestion ->
                                Text("↳ ${suggestion.label}", color = Color(0xFF9CDCFE), fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { insertCompletion(suggestion) }.padding(vertical = 3.dp))
                            }
                            codeActions.forEach { action ->
                                Text("⚡ ${action.optString("title")}", color = Color(0xFF9CDCFE), fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth().clickable { applyCodeAction(action) }.padding(vertical = 3.dp))
                            }
                        } else if (panelTab == "DEBUG") {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { startDebugSession() }) { Text("Start") }
                                TextButton(onClick = { debugControl("continue") }, enabled = debugClient != null) { Text("Continue") }
                                TextButton(onClick = { debugControl("next") }, enabled = debugClient != null) { Text("Step over") }
                                TextButton(onClick = { debugControl("stepIn") }, enabled = debugClient != null) { Text("Step in") }
                                TextButton(onClick = { debugControl("stepOut") }, enabled = debugClient != null) { Text("Step out") }
                                TextButton(onClick = { debugControl("pause") }, enabled = debugClient != null) { Text("Pause") }
                                TextButton(onClick = { toggleBreakpointAtCursor() }) { Text("Toggle BP") }
                                TextButton(onClick = { inspectDebugStack() }, enabled = debugClient != null) { Text("Stack") }
                                TextButton(onClick = { inspectDebugVariables() }, enabled = debugClient != null) { Text("Variables") }
                                TextButton(onClick = { debugClient?.close(); debugClient = null; debugOutput = "Debug session stopped." }) { Text("Stop") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(breakpointLines.sorted().joinToString(","), { value ->
                                    breakpointLines = value.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.toSet()
                                }, singleLine = true, label = { Text("Breakpoint lines") }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { setDebugBreakpoints() }, enabled = debugClient != null) { Text("Set") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(debugExpression, { debugExpression = it }, singleLine = true,
                                    label = { Text("Evaluate expression") }, modifier = Modifier.weight(1f))
                                TextButton(onClick = { evaluateDebugExpression() }, enabled = debugClient != null && debugExpression.isNotBlank()) { Text("Evaluate") }
                                TextButton(onClick = { debugControl("threads") }, enabled = debugClient != null) { Text("Threads") }
                                TextButton(onClick = { debugControl("stackTrace") }, enabled = debugClient != null && debugThreadId > 0) { Text("Stack") }
                            }
                            Text(debugOutput, color = Color(0xFF9CDCFE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        } else Text(when(panelTab) {
                            "TERMINAL" -> "Shell scripts run in a separate Android isolated-process UID (no app-private workspace access). Python/Node/Java still run under the app UID and are not sandboxed. Imported toolchains: ${installedTools.joinToString().ifBlank { "none detected" }}. Use Settings to import a folder containing python, node, java or language-server executables."
                            "OUTPUT" -> gitOutput
                            else -> ""
                        }, color = Color(0xFF9CDCFE), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(26.dp).background(Accent), verticalAlignment = Alignment.CenterVertically) {
            Text("  ⌁  $status", color = Color.White, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Box {
                Text("$language  ▾", color = Color.White, fontSize = 10.sp, modifier = Modifier.clickable { showLanguageMenu = true }.padding(horizontal = 8.dp))
                DropdownMenu(expanded = showLanguageMenu, onDismissRequest = { showLanguageMenu = false }) {
                    listOf("Plain Text","Kotlin","Java","Python","JavaScript","TypeScript","C","C++","C#","Go","Rust","PHP","Ruby","Swift","Dart","HTML","CSS","JSON","Markdown").forEach {
                        DropdownMenuItem(text = { Text(it) }, onClick = { language = it; showLanguageMenu = false })
                    }
                }
            }
            Text("UTF-8   LF   Android  ", color = Color.White, fontSize = 10.sp)
        }
    }
}

internal fun languageFor(file: String): String = when (file.substringAfterLast('.', "").lowercase()) {
    "kt","kts" -> "Kotlin"; "java" -> "Java"; "py" -> "Python"; "js","jsx" -> "JavaScript"
    "ts","tsx" -> "TypeScript"; "c","h" -> "C"; "cpp","cc","hpp" -> "C++"; "cs" -> "C#"
    "go" -> "Go"; "rs" -> "Rust"; "php" -> "PHP"; "rb" -> "Ruby"; "swift" -> "Swift"
    "dart" -> "Dart"; "html","htm" -> "HTML"; "css" -> "CSS"; "json" -> "JSON"
    "md" -> "Markdown"; else -> "Plain Text"
}
