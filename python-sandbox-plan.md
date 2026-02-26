# Python Code Execution Sandbox

## TOC
1. Context
2. Executive Summary
3. Architecture
4. New Files
5. Modified Files
6. Chaquopy Setup
7. Verification

## Context

The Privatemode Android app currently supports text chat with streaming, web search (via prompt-based `[SEARCH:]` markers), file uploads, and image input. There is no code execution capability. The backend API supports OpenAI-style tool calling — the Go proxy explicitly handles `tools` in requests and `tool_calls` in responses (encrypted via HPKE). We add a Python sandbox via Chaquopy so the model can execute code on-device when it decides to use the tool.

## Executive Summary

4 new files, 5 modified files. Core changes:

- **Chaquopy** Gradle plugin embeds CPython into the app (~15-20MB per ABI)
- **PythonExecutor** — restricted-globals sandbox with timeout, stdout/stderr capture
- **Tool calling protocol** — extend `PrivatemodeClient` SSE parsing to handle `delta.tool_calls`; extend `Message` model with `toolCalls`/`toolCallId` fields
- **Tool execution loop** in `ChatViewModel` — detect tool_call → execute Python → append tool result → continue streaming
- **UI** — render tool call/result blocks inline in chat (collapsible code + output)

## Architecture

```
User sends message
  → ChatViewModel.sendMessage()
    → PrivatemodeClient.streamChatCompletion(tools=[python_executor])
      → SSE stream: delta.content chunks + delta.tool_calls chunks
        → If tool_call detected:
          1. Pause streaming
          2. Show "Running code..." in UI
          3. PythonExecutor.execute(code) → stdout + result
          4. Append TOOL role message with result
          5. Resume: send follow-up request with tool result
          6. Model generates final response using output
        → If content only:
          Normal text streaming (existing behavior)
```

## New Files

### 4.1 `app/src/main/python/executor.py`

Chaquopy's Python entry point. Runs in CPython embedded in the app process.

```python
import sys
import io
import traceback

def run_code(code, timeout_hint=30):
    """Execute code string in restricted environment. Returns dict with stdout, result, error."""
    stdout_capture = io.StringIO()
    stderr_capture = io.StringIO()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = stdout_capture, stderr_capture

    restricted_globals = {"__builtins__": _safe_builtins()}
    # Pre-import safe stdlib modules
    for mod in ("math", "json", "re", "collections", "itertools", "functools",
                "statistics", "decimal", "fractions", "random", "string",
                "textwrap", "datetime", "hashlib", "base64", "csv", "io"):
        try:
            restricted_globals[mod] = __import__(mod)
        except ImportError:
            pass

    result = None
    error = None
    try:
        compiled = compile(code, "<sandbox>", "exec")
        exec(compiled, restricted_globals)
        # Capture last expression value if "_result" is set
        result = restricted_globals.get("_result")
    except Exception:
        error = traceback.format_exc()
    finally:
        sys.stdout, sys.stderr = old_stdout, old_stderr

    return {
        "stdout": stdout_capture.getvalue(),
        "stderr": stderr_capture.getvalue(),
        "result": str(result) if result is not None else None,
        "error": error,
    }

def _safe_builtins():
    """Builtins whitelist — remove file/import/exec/eval access."""
    import builtins
    allowed = [
        "abs", "all", "any", "bin", "bool", "bytes", "chr", "dict",
        "divmod", "enumerate", "filter", "float", "format", "frozenset",
        "hex", "int", "isinstance", "issubclass", "iter", "len", "list",
        "map", "max", "min", "next", "oct", "ord", "pow", "print",
        "range", "repr", "reversed", "round", "set", "slice", "sorted",
        "str", "sum", "tuple", "type", "zip", "True", "False", "None",
    ]
    return {k: getattr(builtins, k) for k in allowed if hasattr(builtins, k)}
```

Key restrictions: no `open()`, no `__import__()`, no `exec()`/`eval()`/`compile()` in sandbox globals, no `os`/`subprocess`/`shutil`/`socket`. The sandbox is best-effort — Android OS sandbox is the real security boundary.

### 4.2 `app/src/main/java/ai/privatemode/android/python/PythonExecutor.kt`

Kotlin wrapper around Chaquopy. Manages Python initialization and code execution.

```kotlin
package ai.privatemode.android.python

import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val result: String?,
    val error: String?,
    val timedOut: Boolean = false,
)

class PythonExecutor(context: Context) {
    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    suspend fun execute(code: String, timeoutMs: Long = 30_000): ExecutionResult =
        withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(timeoutMs) {
                val py = Python.getInstance()
                val module = py.getModule("executor")
                val pyResult = module.callAttr("run_code", code)
                ExecutionResult(
                    stdout = pyResult["stdout"]?.toString() ?: "",
                    stderr = pyResult["stderr"]?.toString() ?: "",
                    result = pyResult["result"]?.toString(),
                    error = pyResult["error"]?.toString(),
                )
            }
            result ?: ExecutionResult("", "", null, null, timedOut = true)
        }
}
```

### 4.3 `app/src/main/java/ai/privatemode/android/data/model/ToolCall.kt`

Data classes for tool calling protocol.

```kotlin
package ai.privatemode.android.data.model

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,  // JSON string
)

data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction,
)

data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
)
```

### 4.4 `app/src/main/java/ai/privatemode/android/python/PythonToolProvider.kt`

Defines the tool schema and handles execution. Single source of truth for the Python tool definition sent to the API.

```kotlin
package ai.privatemode.android.python

import ai.privatemode.android.data.model.ToolDefinition
import ai.privatemode.android.data.model.ToolFunction
import com.google.gson.Gson

object PythonToolProvider {
    const val TOOL_NAME = "python"

    val toolDefinition = ToolDefinition(
        function = ToolFunction(
            name = TOOL_NAME,
            description = "Execute Python code. Use for calculations, data processing, or any task requiring computation. Print output with print(). Set _result for a return value.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "code" to mapOf(
                        "type" to "string",
                        "description" to "Python code to execute",
                    ),
                ),
                "required" to listOf("code"),
            ),
        ),
    )

    fun extractCode(argumentsJson: String): String {
        val map = Gson().fromJson(argumentsJson, Map::class.java)
        return map["code"] as? String ?: ""
    }
}
```

## Modified Files

### 5.1 `app/build.gradle.kts`

Add Chaquopy plugin:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")  // ADD
}
```

Inside `defaultConfig {}`:
```kotlin
python {
    version = "3.12"
    pip {
        // no packages for now — stdlib only
    }
}
```

In `settings.gradle.kts` (or project-level `build.gradle.kts`), add the Chaquopy plugin repository and classpath.

### 5.2 `app/src/main/java/ai/privatemode/android/data/model/Chat.kt`

Extend `Message` with optional tool-calling fields:

```kotlin
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachedFiles: List<AttachedFile>? = null,
    val toolCalls: List<ToolCall>? = null,   // ADD: present when assistant requests tool use
    val toolCallId: String? = null,          // ADD: present when role=TOOL (result message)
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL;           // ADD: TOOL role
    // ...
}
```

### 5.3 `app/src/main/java/ai/privatemode/android/data/remote/PrivatemodeClient.kt`

Two changes:

**a) Request body** — accept optional `tools` parameter in `streamChatCompletion()`:
```kotlin
fun streamChatCompletion(..., tools: List<ToolDefinition>? = null): Flow<StreamChunk>
```
Add to request JSON:
```kotlin
if (!tools.isNullOrEmpty()) {
    add("tools", gson.toJsonTree(tools))
}
```

**b) SSE parsing** — change `Flow<String>` to `Flow<StreamChunk>` where:
```kotlin
sealed class StreamChunk {
    data class Content(val text: String) : StreamChunk()
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val arguments: String) : StreamChunk()
    data object Done : StreamChunk()
}
```

In the SSE loop, after checking `delta.content`, also check `delta.tool_calls`:
```kotlin
val toolCallsArray = delta?.getAsJsonArray("tool_calls")
if (toolCallsArray != null) {
    for (tc in toolCallsArray) {
        val obj = tc.asJsonObject
        val index = obj.get("index")?.asInt ?: 0
        val id = obj.get("id")?.asString
        val fn = obj.getAsJsonObject("function")
        val name = fn?.get("name")?.asString
        val args = fn?.get("arguments")?.asString ?: ""
        trySend(StreamChunk.ToolCallDelta(index, id, name, args))
    }
}
```

Tool call arguments are streamed incrementally (partial JSON across chunks), so the ViewModel accumulates them.

### 5.4 `app/src/main/java/ai/privatemode/android/ui/chat/ChatViewModel.kt`

**a) Constructor** — add `pythonExecutor: PythonExecutor` parameter.

**b) `streamToAssistantMessage()`** — refactor to handle `StreamChunk`:
- Accumulate `Content` chunks into `accumulatedContent` (existing behavior)
- Accumulate `ToolCallDelta` chunks: buffer `id`, `name`, `arguments` per tool call index
- Return both content and accumulated tool calls

**c) Tool execution loop** in `sendMessage()`:
```
After streaming completes:
  if (toolCalls.isNotEmpty()):
    for each toolCall:
      - Update assistant message with toolCalls field
      - Show execution indicator in UI
      - val code = PythonToolProvider.extractCode(toolCall.arguments)
      - val result = pythonExecutor.execute(code)
      - Format result as tool message content
      - Add TOOL role message to chat (toolCallId = toolCall.id)
    - Send follow-up request with full message history (including tool results)
    - Stream the model's final response
```

This is a loop — the model may request multiple sequential tool calls.

**d) Pass `tools` parameter** to `repository.streamChatCompletion()`:
```kotlin
val tools = listOf(PythonToolProvider.toolDefinition)
```

### 5.5 `app/src/main/java/ai/privatemode/android/ui/chat/ChatScreen.kt`

Extend `MessageBubble` to render tool calls and results:

- **Tool call** (assistant message with `toolCalls`): show collapsible code block with "Python" header and a status indicator (running/done/error)
- **Tool result** (TOOL role message): show output in a styled block — stdout in monospace, errors in red, collapsible if long
- Reuse existing Markwon code block styling for consistency

## Chaquopy Setup

### Project-level `build.gradle.kts` (or `settings.gradle.kts`)

```kotlin
plugins {
    id("com.chaquo.python") version "16.0.0" apply false
}
```

And in `settings.gradle.kts` pluginManagement:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven") }
    }
}
```

### ProGuard

```
-keep class com.chaquo.python.** { *; }
```

### APK size impact

- CPython runtime: ~15MB per ABI (arm64-v8a + x86_64)
- After APK splitting by ABI: ~15MB per variant
- No additional pip packages initially (stdlib only)

## Verification

1. `./gradlew assembleDebug` — builds with Chaquopy, includes Python runtime
2. Send "What is 2**100?" to the model → model uses python tool → code executes → result shown inline
3. Send "Generate a list of prime numbers under 100" → model writes Python → stdout captured → displayed
4. Timeout test: model generates `while True: pass` → 30s timeout → error shown
5. Sandbox test: model tries `import os; os.listdir('/')` → blocked (no `os` in restricted globals)
6. Tool result rendered inline: collapsible code block + output block
7. Conversation continues normally after tool use — model references the output
8. Unit tests pass: `./gradlew :app:testDebugUnitTest`
9. Non-tool-calling models still work normally (tools param ignored or not sent based on model capability)
