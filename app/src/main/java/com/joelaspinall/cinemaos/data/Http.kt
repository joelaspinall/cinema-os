package com.joelaspinall.cinemaos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val quickClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), quick: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            (if (quick) quickClient else client).newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${url.substringBefore('?')}")
                res.body?.string() ?: ""
            }
        }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), quick: Boolean = false): JsonElement =
        JSON.parseToJsonElement(getText(url, headers + ("Accept" to "application/json"), quick))

    suspend fun postJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .post(ByteArray(0).toRequestBody(null))
                .header("Accept", "application/json")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${url.substringBefore('?')}")
                JSON.parseToJsonElement(res.body?.string() ?: "{}")
            }
        }
}

// Small helpers for reading loosely-typed JSON.
fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())
fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() && it != "null" }
fun JsonObject?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonObject?.dbl(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
fun JsonElement.asStringOrNull(): String? = runCatching { jsonPrimitive.content }.getOrNull()
