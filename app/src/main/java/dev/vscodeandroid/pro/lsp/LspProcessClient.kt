package dev.vscodeandroid.pro.lsp

import dev.vscodeandroid.pro.toolchain.ToolchainAudit
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Minimal stdio JSON-RPC 2.0/LSP transport. It does not bundle a language server. */
class LspProcessClient(
    private val executable: File,
    private val args: List<String>,
    private val workspace: File,
    private val onWorkspaceEditApplied: (List<String>) -> Unit = {},
    private val currentDocumentVersion: (String) -> Int? = { null },
    private val onNotification: (String, JSONObject) -> Unit = { _, _ -> }
) : Closeable {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private val registrations = ConcurrentHashMap<String, JSONObject>()
    private val serverCapabilities = java.util.concurrent.atomic.AtomicReference(JSONObject())
    @Volatile private var io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lsp-reader-${executable.name}").apply { isDaemon = true }
    }
    @Volatile private var process: Process? = null
    @Volatile private var input: BufferedInputStream? = null
    @Volatile private var output: BufferedOutputStream? = null

    fun start(): Boolean {
        val allowedTools = File(workspace.parentFile?.parentFile, "toolchains").canonicalFile
        val allowedWorkspaces = File(workspace.parentFile?.parentFile, "workspaces").canonicalFile
        require(executable.canonicalFile.toPath().startsWith(allowedTools.toPath())) { "Server executable must be inside app-private toolchains" }
        require(executable.isFile && executable.canExecute()) { "Language server executable is unavailable" }
        require(workspace.canonicalFile.toPath().startsWith(allowedWorkspaces.toPath()) && workspace.isDirectory) { "Workspace must be app-private" }
        require(args.size <= 32 && args.all { it.length <= 1024 && '\u0000' !in it }) { "Invalid language-server arguments" }

        synchronized(this) {
            if (process?.isAlive == true) return true
            val appRoot = allowedWorkspaces.parentFile.canonicalFile
            val safeServerName = executable.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val processHome = File(appRoot, "runtime-home/${workspace.name}/lsp-$safeServerName").canonicalFile.apply { mkdirs() }
            val temp = File(processHome, "tmp").canonicalFile.apply { mkdirs() }
            require(processHome.toPath().startsWith(appRoot.toPath()) && temp.toPath().startsWith(processHome.toPath()))
            val logRoot = File(appRoot, "logs").apply { mkdirs() }
            val stderrFile = File(logRoot, "lsp-${workspace.name}-$safeServerName.log")
            val started = ProcessBuilder(listOf(executable.canonicalPath) + args)
                .directory(workspace)
                .apply {
                    environment().clear()
                    environment()["HOME"] = processHome.absolutePath
                    environment()["TMPDIR"] = temp.absolutePath
                    environment()["PATH"] = "${allowedTools.absolutePath}:/system/bin:/system/xbin"
                }
                .start()
            process = started
            Thread({ drainStderr(started.errorStream, stderrFile) }, "lsp-stderr-${executable.name}").apply { isDaemon = true; start() }
            input = BufferedInputStream(started.inputStream)
            output = BufferedOutputStream(started.outputStream)
            if (io.isShutdown) io = Executors.newSingleThreadExecutor { task ->
                Thread(task, "lsp-reader-${executable.name}").apply { isDaemon = true }
            }
            io.execute { readLoop() }
        }

        // Do not hold the transport monitor while waiting: the response reader must
        // be able to send the initialized notification from its callback.
        val initialized = CountDownLatch(1)
        val initializeError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        try {
            request("initialize", JSONObject().put("processId", android.os.Process.myPid())
                .put("rootUri", workspace.toURI().toString())
                .put("capabilities", JSONObject()
                    .put("textDocument", JSONObject()
                        .put("synchronization", JSONObject().put("dynamicRegistration", false).put("willSave", false)
                            .put("willSaveWaitUntil", false).put("didSave", true))
                        .put("completion", JSONObject().put("dynamicRegistration", false)
                            .put("completionItem", JSONObject().put("snippetSupport", true)
                                .put("deprecatedSupport", true).put("preselectSupport", true)
                                .put("insertReplaceSupport", true).put("labelDetailsSupport", true)
                                .put("documentationFormat", org.json.JSONArray().put("markdown").put("plaintext"))
                                .put("resolveSupport", JSONObject().put("properties", org.json.JSONArray()
                                    .put("documentation").put("detail").put("additionalTextEdits").put("command").put("data")))))
                        .put("publishDiagnostics", JSONObject().put("relatedInformation", true)
                            .put("tagSupport", JSONObject().put("valueSet", org.json.JSONArray().put(1).put(2))))
                        .put("codeAction", JSONObject().put("dynamicRegistration", false)
                            .put("resolveSupport", JSONObject().put("properties", org.json.JSONArray().put("edit").put("command")))
                            .put("codeActionLiteralSupport", JSONObject().put("codeActionKind", JSONObject()
                                .put("valueSet", org.json.JSONArray().put("quickfix").put("refactor").put("source")))))
                        .put("hover", JSONObject().put("contentFormat", org.json.JSONArray().put("markdown").put("plaintext")))
                        .put("definition", JSONObject()).put("references", JSONObject())
                        .put("rename", JSONObject().put("prepareSupport", true))
                        .put("formatting", JSONObject())
                        .put("semanticTokens", JSONObject()
                            .put("dynamicRegistration", true)
                            .put("requests", JSONObject().put("range", true).put("full", JSONObject().put("delta", true)))
                            .put("tokenTypes", org.json.JSONArray().put("namespace").put("type").put("class").put("enum")
                                .put("interface").put("struct").put("typeParameter").put("parameter").put("variable")
                                .put("property").put("enumMember").put("event").put("function").put("method").put("macro")
                                .put("keyword").put("modifier").put("comment").put("string").put("number").put("regexp").put("operator"))
                            .put("tokenModifiers", org.json.JSONArray().put("declaration").put("definition").put("readonly")
                                .put("static").put("deprecated").put("abstract").put("async").put("modification").put("documentation").put("defaultLibrary"))
                            .put("formats", org.json.JSONArray().put("relative"))))
                    .put("workspace", JSONObject().put("applyEdit", true).put("workspaceFolders", true)
                        .put("configuration", true).put("didChangeWatchedFiles", JSONObject().put("dynamicRegistration", true))
                        .put("workspaceEdit", JSONObject().put("documentChanges", true)
                            .put("resourceOperations", org.json.JSONArray().put("create").put("rename").put("delete")))))) { response ->
                try {
                    if (response.has("error")) initializeError.set(response.opt("error").toString())
                    else {
                        serverCapabilities.set(response.optJSONObject("result")?.optJSONObject("capabilities") ?: JSONObject())
                        notify("initialized", JSONObject())
                    }
                } catch (failure: Exception) {
                    initializeError.set(failure.message ?: "initialized notification failed")
                } finally {
                    initialized.countDown()
                }
            }
        } catch (failure: Exception) {
            close()
            throw IllegalStateException("Unable to send LSP initialize request: ${failure.message}", failure)
        }
        val responded = initialized.await(10, TimeUnit.SECONDS)
        if (!responded || initializeError.get() != null) {
            close()
            val reason = if (!responded) "initialize timed out" else "initialize failed: ${initializeError.get()}"
            throw IllegalStateException("Language server $reason")
        }
        return true
    }

    @Synchronized fun request(method: String, params: JSONObject, callback: (JSONObject) -> Unit = {}): Int {
        check(process?.isAlive == true && output != null) { "Language server is not running" }
        val id = ids.getAndIncrement()
        pending[id] = callback
        try {
            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        } catch (failure: Throwable) {
            pending.remove(id)
            throw failure
        }
        return id
    }

    /** Cancel an in-flight JSON-RPC request using the LSP cancellation notification. */
    fun cancelRequest(id: Int): Boolean {
        if (pending.remove(id) == null) return false
        if (process?.isAlive == true) runCatching {
            notify("$/cancelRequest", JSONObject().put("id", id))
        }
        return true
    }

    fun cancelAllRequests() {
        pending.keys.toList().forEach(::cancelRequest)
    }

    fun capabilities(): JSONObject = JSONObject(serverCapabilities.get().toString())

    fun semanticTokensFull(uri: String, callback: (JSONObject) -> Unit): Int =
        request("textDocument/semanticTokens/full",
            JSONObject().put("textDocument", JSONObject().put("uri", uri)), callback)

    fun semanticTokensRange(uri: String, startLine: Int, startCharacter: Int,
                            endLine: Int, endCharacter: Int, callback: (JSONObject) -> Unit): Int {
        require(startLine >= 0 && startCharacter >= 0 && endLine >= startLine && endCharacter >= 0)
        return request("textDocument/semanticTokens/range", JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))
            .put("range", JSONObject()
                .put("start", JSONObject().put("line", startLine).put("character", startCharacter))
                .put("end", JSONObject().put("line", endLine).put("character", endCharacter))), callback)
    }

    fun semanticTokensFullDelta(uri: String, previousResultId: String,
                                callback: (JSONObject) -> Unit): Int {
        require(previousResultId.length in 1..512)
        return request("textDocument/semanticTokens/full/delta", JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))
            .put("previousResultId", previousResultId), callback)
    }

    private fun handleCapabilityRegistration(method: String, params: JSONObject) {
        if (method == "client/registerCapability") {
            val items = params.optJSONArray("registrations") ?: return
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isNotBlank()) registrations[id] = item
            }
        } else {
            val items = params.optJSONArray("unregistrations") ?: params.optJSONArray("unregisterations") ?: return
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                registrations.remove(item.optString("id"))
            }
        }
    }

    fun registeredCapabilities(): List<JSONObject> =
        registrations.values.map { JSONObject(it.toString()) }

    @Synchronized fun notify(method: String, params: JSONObject) {
        check(process?.isAlive == true && output != null) { "Language server is not running" }
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
    }

    fun didOpen(uri: String, languageId: String, version: Int, text: String) = notify(
        "textDocument/didOpen", JSONObject().put("textDocument", JSONObject()
            .put("uri", uri).put("languageId", languageId).put("version", version).put("text", text))
    )

    fun didChange(uri: String, version: Int, text: String) = notify(
        "textDocument/didChange", JSONObject().put("textDocument", JSONObject().put("uri", uri).put("version", version))
            .put("contentChanges", org.json.JSONArray().put(JSONObject().put("text", text)))
    )

    fun didClose(uri: String) = notify("textDocument/didClose", JSONObject().put("textDocument", JSONObject().put("uri", uri)))

    fun completion(uri: String, line: Int, character: Int, callback: (JSONObject) -> Unit) = request(
        "textDocument/completion", JSONObject().put("textDocument", JSONObject().put("uri", uri))
            .put("position", JSONObject().put("line", line).put("character", character)), callback
    )

    fun codeActions(uri: String, range: JSONObject, diagnostics: org.json.JSONArray, callback: (JSONObject) -> Unit) = request(
        "textDocument/codeAction", JSONObject().put("textDocument", JSONObject().put("uri", uri))
            .put("range", range).put("context", JSONObject().put("diagnostics", diagnostics)
                .put("only", org.json.JSONArray().put("quickfix").put("refactor").put("source"))), callback
    )

    fun resolveCodeAction(action: JSONObject, callback: (JSONObject) -> Unit) = request("codeAction/resolve", action, callback)

    fun hover(uri: String, line: Int, character: Int, callback: (JSONObject) -> Unit) = request(
        "textDocument/hover", positionParams(uri, line, character), callback)

    fun definition(uri: String, line: Int, character: Int, callback: (JSONObject) -> Unit) = request(
        "textDocument/definition", positionParams(uri, line, character), callback)

    fun references(uri: String, line: Int, character: Int, includeDeclaration: Boolean, callback: (JSONObject) -> Unit) = request(
        "textDocument/references", positionParams(uri, line, character).put("context", JSONObject().put("includeDeclaration", includeDeclaration)), callback)

    fun rename(uri: String, line: Int, character: Int, newName: String, callback: (JSONObject) -> Unit) {
        require(newName.length in 1..256 && '\u0000' !in newName)
        request("textDocument/rename", positionParams(uri, line, character).put("newName", newName), callback)
    }

    fun formatting(uri: String, tabSize: Int, insertSpaces: Boolean, callback: (JSONObject) -> Unit) = request(
        "textDocument/formatting", JSONObject().put("textDocument", JSONObject().put("uri", uri))
            .put("options", JSONObject().put("tabSize", tabSize.coerceIn(1, 16)).put("insertSpaces", insertSpaces)), callback)

    fun didSave(uri: String, text: String? = null) = notify("textDocument/didSave", JSONObject()
        .put("textDocument", JSONObject().put("uri", uri)).apply { if (text != null) put("text", text) })

    private fun positionParams(uri: String, line: Int, character: Int): JSONObject {
        require(line >= 0 && character >= 0)
        return JSONObject().put("textDocument", JSONObject().put("uri", uri))
            .put("position", JSONObject().put("line", line).put("character", character))
    }

    fun codeActions(uri: String, startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int,
                    diagnostics: org.json.JSONArray = org.json.JSONArray(), callback: (JSONObject) -> Unit) = request(
        "textDocument/codeAction", JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))
            .put("range", JSONObject().put("start", JSONObject().put("line", startLine).put("character", startCharacter))
                .put("end", JSONObject().put("line", endLine).put("character", endCharacter)))
            .put("context", JSONObject().put("diagnostics", diagnostics)), callback
    )

    fun formatting(uri: String, callback: (JSONObject) -> Unit) = request(
        "textDocument/formatting", JSONObject().put("textDocument", JSONObject().put("uri", uri))
            .put("options", JSONObject().put("tabSize", 4).put("insertSpaces", true)), callback
    )

    fun executeCommand(command: String, arguments: org.json.JSONArray = org.json.JSONArray(), callback: (JSONObject) -> Unit = {}) = request(
        "workspace/executeCommand", JSONObject().put("command", command).put("arguments", arguments), callback
    )

    private fun send(message: JSONObject) {
        val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(this) {
            val stream = output ?: error("LSP server is not running")
            stream.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            stream.write(bytes); stream.flush()
        }
    }

    private fun drainStderr(stream: java.io.InputStream, destination: File) {
        val captured = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (count < 0) break
                val remaining = 1_048_576 - captured.size()
                if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
            }
        }
        runCatching { destination.writeBytes(captured.toByteArray()) }
    }

    private fun readLoop() {
        try {
            while (process?.isAlive == true) {
                val stream = input ?: break
                var length = -1
                while (true) {
                    val line = readAsciiLine(stream) ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                if (length !in 0..8_388_608) throw IllegalStateException("Invalid LSP frame length")
                val body = ByteArray(length); var offset = 0
                while (offset < length) {
                    val count = stream.read(body, offset, length - offset)
                    if (count < 0) return
                    offset += count
                }
                val json = JSONObject(String(body, StandardCharsets.UTF_8))
                if (json.has("id") && (json.has("result") || json.has("error"))) {
                    val result = if (json.has("error")) JSONObject().put("error", json.opt("error")) else json.opt("result")
                    val envelope = if (result is JSONObject) result else JSONObject().put("value", result)
                    runCatching { pending.remove(json.getInt("id"))?.invoke(envelope) }
                } else if (json.has("method")) {
                    val method = json.getString("method")
                    val params = json.optJSONObject("params") ?: JSONObject()
                    if (json.has("id")) {
                        // Respond to standard client requests conservatively. Unknown methods
                        // receive MethodNotFound rather than a misleading successful null.
                        val id = json.get("id")
                        if (method == "workspace/configuration") {
                            val items = params.optJSONArray("items")
                            val result = org.json.JSONArray()
                            repeat(items?.length() ?: 0) { result.put(JSONObject.NULL) }
                            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
                        } else if (method == "workspace/applyEdit") {
                            try {
                                val edit = params.optJSONObject("edit") ?: error("Missing workspace edit")
                                val changedPaths = LspWorkspaceEditApplier(workspace).apply(edit,
                                    read = { path -> File(workspace, path).canonicalFile.readText(Charsets.UTF_8) },
                                    write = { path, content -> File(workspace, path).canonicalFile.writeText(content, Charsets.UTF_8) },
                                    validateVersion = { uri, version -> currentDocumentVersion(uri) == version })
                                runCatching { onWorkspaceEditApplied(changedPaths) }
                                send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                                    .put("result", JSONObject().put("applied", true)))
                            } catch (failure: Exception) {
                                send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                                    .put("result", JSONObject().put("applied", false).put("failureReason", failure.message ?: "Workspace edit rejected")))
                            }
                        } else if (method == "workspace/workspaceFolders") {
                            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", org.json.JSONArray()
                                .put(JSONObject().put("uri", workspace.toURI().toString()).put("name", workspace.name))))
                        } else if (method == "window/showMessageRequest") {
                            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject.NULL))
                        } else if (method == "window/workDoneProgress/create") {
                            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject.NULL))
                        } else if (method == "client/registerCapability" || method == "client/unregisterCapability") {
                            handleCapabilityRegistration(method, params)
                            send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject.NULL))
                        } else {
                            send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                                .put("error", JSONObject().put("code", -32601).put("message", "Method not found")))
                        }
                    } else runCatching { onNotification(method, params) }
                }
            }
        } catch (_: Exception) {
            // The process stderr file is retained for an actionable user-visible error.
        } finally {
            pending.clear()
        }
    }

    private fun readAsciiLine(stream: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = stream.read(); if (c < 0) return if (out.isEmpty()) null else out.toString()
            if (c == 10) return out.toString().removeSuffix("\r")
            if (out.length >= 16_384) throw IllegalStateException("LSP header too large")
            out.append(c.toChar())
        }
    }

    @Synchronized override fun close() {
        if (process?.isAlive == true) {
            val shutdown = CountDownLatch(1)
            runCatching { request("shutdown", JSONObject()) { shutdown.countDown() } }
            runCatching { shutdown.await(750, TimeUnit.MILLISECONDS) }
            runCatching { notify("exit", JSONObject()) }
            process?.destroy()
            if (process?.waitFor(750, TimeUnit.MILLISECONDS) == false) process?.destroyForcibly()
        }
        pending.clear(); registrations.clear(); serverCapabilities.set(JSONObject())
        io.shutdownNow(); input = null; output = null; process = null
    }
}
