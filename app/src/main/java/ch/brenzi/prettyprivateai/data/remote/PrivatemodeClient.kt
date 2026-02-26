package ch.brenzi.prettyprivateai.data.remote

import android.util.Log
import ch.brenzi.prettyprivateai.data.model.ApiModel
import ch.brenzi.prettyprivateai.data.model.Message
import ch.brenzi.prettyprivateai.data.model.ModelsResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class PrivatemodeClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    private val TAG = "PrivatemodeClient"
    private val gson = Gson()

    companion object {
        /** Shared OkHttpClient across all PrivatemodeClient instances for connection reuse. */
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Privatemode-Version", "v1.23.0")
                    .addHeader("Privatemode-Client", "App")
                    .build()
                chain.proceed(request)
            })
            .build()
    }

    suspend fun fetchModels(): List<ApiModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        val response = sharedClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = try { response.body?.string() } catch (_: Exception) { null }
            throw parseApiError(response.code, response.message, errorBody)
        }

        val body = response.body?.string() ?: throw ApiException("Response body is null")
        val modelsResponse = gson.fromJson(body, ModelsResponse::class.java)
        modelsResponse.data
    }

    fun streamChatCompletion(
        model: String,
        messages: List<Message>,
        systemPrompt: String? = null,
        reasoningEffort: String? = null,
        searchContext: String? = null,
        supportsSystemRole: Boolean = true,
    ): Flow<String> = callbackFlow {
        val apiMessages = mutableListOf<JsonObject>()

        if (systemPrompt != null) {
            apiMessages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
        }

        if (searchContext != null) {
            apiMessages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", searchContext)
            })
        }

        for (msg in messages) {
            val imageFiles = msg.attachedFiles?.filter { it.imageBase64 != null } ?: emptyList()
            val textFiles = msg.attachedFiles?.filter { it.imageBase64 == null } ?: emptyList()

            textFiles.forEach { file ->
                apiMessages.add(JsonObject().apply {
                    addProperty("role", msg.role.toApiString())
                    addProperty("content", "[File: ${file.name}]\n\n${file.content}")
                })
            }

            if (imageFiles.isNotEmpty()) {
                val contentParts = com.google.gson.JsonArray()
                for (img in imageFiles) {
                    contentParts.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            addProperty("url", "data:${img.mimeType ?: "image/jpeg"};base64,${img.imageBase64}")
                        })
                    })
                }
                contentParts.add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", msg.content)
                })
                apiMessages.add(JsonObject().apply {
                    addProperty("role", msg.role.toApiString())
                    add("content", contentParts)
                })
            } else {
                apiMessages.add(JsonObject().apply {
                    addProperty("role", msg.role.toApiString())
                    addProperty("content", msg.content)
                })
            }
        }

        val consolidatedMessages = consolidateMessages(apiMessages, supportsSystemRole)

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            add("messages", gson.toJsonTree(consolidatedMessages))
            addProperty("stream", true)
            if (reasoningEffort != null) {
                addProperty("reasoning_effort", reasoningEffort)
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = sharedClient.newCall(request)
        Log.i(TAG, "Sending chat completion to $baseUrl/v1/chat/completions model=$model messages=${messages.size}")

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Chat completion network error", e)
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.i(TAG, "Chat completion response: ${response.code} ${response.message}")

                if (!response.isSuccessful) {
                    val errorBody = try { response.body?.string() } catch (_: Exception) { null }
                    Log.e(TAG, "Chat completion failed: ${response.code} body=$errorBody")
                    close(parseApiError(response.code, response.message, errorBody))
                    return
                }

                val body = response.body ?: run {
                    Log.e(TAG, "Response body is null")
                    close(ApiException("Response body is null"))
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    var line: String?
                    var chunkCount = 0

                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim() ?: continue
                        if (trimmed.isEmpty()) continue
                        if (trimmed == "data: [DONE]") {
                            Log.d(TAG, "SSE stream done after $chunkCount chunks")
                            continue
                        }

                        if (trimmed.startsWith("data: ")) {
                            val data = trimmed.substring(6)
                            try {
                                val chunk = gson.fromJson(data, JsonObject::class.java)
                                val choices = chunk.getAsJsonArray("choices")
                                if (choices != null && choices.size() > 0) {
                                    val delta = choices[0].asJsonObject
                                        .getAsJsonObject("delta")
                                    val contentElement = delta?.get("content")
                                    if (contentElement != null && contentElement.isJsonPrimitive) {
                                        val content = contentElement.asString
                                        chunkCount++
                                        trySend(content)
                                    }
                                } else if (chunkCount == 0) {
                                    Log.w(TAG, "SSE chunk has no choices: $data")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "SSE parse error for chunk: $data", e)
                            }
                        } else {
                            Log.w(TAG, "SSE unexpected line: $trimmed")
                        }
                    }

                    Log.i(TAG, "SSE stream completed, total chunks: $chunkCount")
                    close()
                } catch (e: Exception) {
                    Log.e(TAG, "SSE stream read error", e)
                    close(e)
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }

    suspend fun uploadFile(file: File, fileName: String): List<UnstructuredElement> =
        withContext(Dispatchers.IO) {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("strategy", "fast")
                .addFormDataPart(
                    "files",
                    fileName,
                    file.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/unstructured/general/v0/general")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = sharedClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = try { response.body?.string() } catch (_: Exception) { null }
                throw parseApiError(response.code, response.message, errorBody)
            }

            val body = response.body?.string() ?: throw ApiException("Response body is null")
            val elements = gson.fromJson(body, Array<UnstructuredElement>::class.java)
            elements.toList()
        }

    /**
     * Ensures the message list conforms to model expectations:
     * 1. If [supportsSystemRole] is false, convert system messages to user role.
     * 2. Merge consecutive messages that share the same role.
     */
    private fun consolidateMessages(
        messages: List<JsonObject>,
        supportsSystemRole: Boolean,
    ): List<JsonObject> {
        if (messages.isEmpty()) return messages

        val normalized = if (supportsSystemRole) {
            messages
        } else {
            messages.map { msg ->
                if (msg.get("role").asString == "system") {
                    JsonObject().apply {
                        addProperty("role", "user")
                        // System messages are always plain text
                        addProperty("content", msg.get("content").asString)
                    }
                } else {
                    msg
                }
            }
        }

        val result = mutableListOf<JsonObject>()
        for (msg in normalized) {
            val role = msg.get("role").asString
            val last = result.lastOrNull()
            if (last == null || last.get("role").asString != role) {
                result.add(msg.deepCopy())
                continue
            }
            val lastContent = last.get("content")
            val msgContent = msg.get("content")
            if (lastContent.isJsonPrimitive && msgContent.isJsonPrimitive) {
                // Both text — concatenate
                last.addProperty("content", lastContent.asString + "\n\n" + msgContent.asString)
            } else {
                // At least one is an array — merge into a single array
                val parts = com.google.gson.JsonArray()
                if (lastContent.isJsonPrimitive) {
                    parts.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", lastContent.asString) })
                } else {
                    lastContent.asJsonArray.forEach { parts.add(it) }
                }
                if (msgContent.isJsonPrimitive) {
                    parts.add(JsonObject().apply { addProperty("type", "text"); addProperty("text", msgContent.asString) })
                } else {
                    msgContent.asJsonArray.forEach { parts.add(it) }
                }
                last.add("content", parts)
            }
        }
        return result
    }

    private fun parseApiError(code: Int, message: String, errorBody: String?): ApiException {
        if (errorBody != null) {
            try {
                val errorJson = gson.fromJson(errorBody, JsonObject::class.java)
                val errorMsg = errorJson?.getAsJsonObject("error")?.get("message")?.asString
                if (errorMsg != null) {
                    if (errorMsg.contains("invalid message format") || errorMsg.contains("nonce:iv:cipher")) {
                        return ApiException(
                            "The secure proxy is required but not running. " +
                                "Please restart the app to establish a secure connection.",
                            code
                        )
                    }
                    if (errorMsg.contains("minimum client version")) {
                        return ApiException(
                            "Please update the app to the latest version.",
                            code
                        )
                    }
                    return ApiException(errorMsg, code)
                }
            } catch (_: Exception) { }
        }
        return ApiException("Request failed: $code $message", code)
    }
}

data class UnstructuredElement(
    val type: String = "",
    val element_id: String = "",
    val text: String = "",
)

class ApiException(
    message: String,
    val statusCode: Int = 0,
) : Exception(message)
