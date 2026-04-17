package com.quickdrop.sharetarget

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Streams shared content URIs directly to Supabase Storage (no local persistence), then inserts
 * a row in Supabase PostgREST (files table).
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val supabaseUrl = BuildConfigValues.getString("SUPABASE_URL").trim().trimEnd('/')
        val supabaseAnonKey = BuildConfigValues.getString("SUPABASE_ANON_KEY").trim()
        val bucket = BuildConfigValues.getString("SUPABASE_BUCKET").trim()
        val filesTable = BuildConfigValues.getString("SUPABASE_FILES_TABLE", "files").trim().ifBlank { "files" }

        if (supabaseUrl.isBlank() || supabaseUrl.contains("<project-ref>")) {
            return@withContext fail("Missing Supabase URL (set BuildConfig.SUPABASE_URL)")
        }
        if (!supabaseUrl.lowercase().startsWith("https://")) {
            return@withContext fail("Supabase URL must use HTTPS")
        }
        if (supabaseAnonKey.isBlank()) {
            return@withContext fail("Missing Supabase anon key (set BuildConfig.SUPABASE_ANON_KEY)")
        }
        if (bucket.isBlank()) {
            return@withContext fail("Missing Supabase bucket (set BuildConfig.SUPABASE_BUCKET)")
        }

        val uris = inputData.getStringArray(KEY_URIS)?.toList().orEmpty().mapNotNull {
            runCatching { Uri.parse(it) }.getOrNull()
        }
        val mimeTypes = inputData.getStringArray(KEY_MIME_TYPES)?.toList().orEmpty()
        val fileNames = inputData.getStringArray(KEY_FILE_NAMES)?.toList().orEmpty()

        if (uris.isEmpty()) return@withContext fail("No URIs")
        if (mimeTypes.size != uris.size || fileNames.size != uris.size) {
            return@withContext fail("Invalid metadata")
        }

        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()

        val base = supabaseUrl.toHttpUrl()

        // Upload files sequentially (simple + predictable).
        for (i in uris.indices) {
            val uri = uris[i]
            val mime = mimeTypes[i].ifBlank { "application/octet-stream" }
            val originalName = fileNames[i].ifBlank { "shared_file" }

            val objectKey = buildObjectKey(originalName)
            val uploadUrl = base.newBuilder()
                .addPathSegments("storage/v1/object")
                .addPathSegment(bucket)
                .addPathSegments(objectKey)
                .build()

            val publicUrl = base.newBuilder()
                .addPathSegments("storage/v1/object/public")
                .addPathSegment(bucket)
                .addPathSegments(objectKey)
                .build()
                .toString()

            val sizeBytes = ContentResolverUtils.getSizeBytes(applicationContext.contentResolver, uri)

            val uploadOk = uploadToSupabaseStorage(
                client = client,
                contentResolver = applicationContext.contentResolver,
                uri = uri,
                uploadUrl = uploadUrl.toString(),
                bucket = bucket,
                supabaseAnonKey = supabaseAnonKey,
                mimeType = mime,
            )

            if (!uploadOk.isSuccess) {
                return@withContext fail(uploadOk.errorMessage ?: "Upload failed")
            }

            val insertOk = insertFileRow(
                client = client,
                baseUrl = base,
                filesTable = filesTable,
                supabaseAnonKey = supabaseAnonKey,
                name = originalName,
                url = publicUrl,
                sizeBytes = sizeBytes,
            )

            if (!insertOk.isSuccess) {
                // Best-effort cleanup to avoid orphaned objects if DB insert fails.
                deleteSupabaseObject(
                    client = client,
                    uploadUrl = uploadUrl.toString(),
                    supabaseAnonKey = supabaseAnonKey,
                )
                return@withContext fail(insertOk.errorMessage ?: "DB insert failed")
            }
        }

        Result.success()
    }

    private data class OpResult(val isSuccess: Boolean, val errorMessage: String? = null)

    private fun uploadToSupabaseStorage(
        client: OkHttpClient,
        contentResolver: ContentResolver,
        uri: Uri,
        uploadUrl: String,
        bucket: String,
        supabaseAnonKey: String,
        mimeType: String,
    ): OpResult {
        val stream = try {
            contentResolver.openInputStream(uri)
        } catch (_: Throwable) {
            null
        } ?: return OpResult(false, "Unsupported file")

        // Stream body; do not buffer to disk.
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()

            override fun writeTo(sink: BufferedSink) {
                stream.use { input: InputStream ->
                    sink.writeAll(input.source())
                }
            }
        }

        val request = Request.Builder()
            .url(uploadUrl)
            .put(body)
            // Required Supabase headers
            .header("apikey", supabaseAnonKey)
            .header("Authorization", "Bearer $supabaseAnonKey")
            // Optional: overwrite if same name; harmless even if object is new.
            .header("x-upsert", "true")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val code = resp.code
                val bodyText = resp.body?.string()?.take(400)
                if (code == 404) {
                    return OpResult(
                        false,
                        "Supabase bucket '$bucket' not found (404). Create it in Supabase Storage or change SUPABASE_BUCKET." +
                            (if (bodyText.isNullOrBlank()) "" else " Details: $bodyText"),
                    )
                }
                return OpResult(false, "Storage upload failed ($code)${if (bodyText.isNullOrBlank()) "" else ": $bodyText"}")
            }
        }

        return OpResult(true)
    }

    private fun insertFileRow(
        client: OkHttpClient,
        baseUrl: okhttp3.HttpUrl,
        filesTable: String,
        supabaseAnonKey: String,
        name: String,
        url: String,
        sizeBytes: Long?,
    ): OpResult {
        val endpoint = baseUrl.newBuilder()
            .addPathSegments("rest/v1")
            .addPathSegment(filesTable)
            .build()

        fun jsonPayload(includeSize: Boolean): String = buildString {
            append('{')
            append("\"name\":\"").append(jsonEscape(name)).append('\"')
            append(',')
            append("\"url\":\"").append(jsonEscape(url)).append('\"')
            if (includeSize && sizeBytes != null && sizeBytes >= 0) {
                append(',')
                append("\"size\":").append(sizeBytes)
            }
            append('}')
        }

        fun doInsert(includeSize: Boolean): OpResult {
            val json = jsonPayload(includeSize)
            val request = Request.Builder()
                .url(endpoint)
                .post(json.toRequestBody("application/json".toMediaTypeOrNull()))
                .header("apikey", supabaseAnonKey)
                .header("Authorization", "Bearer $supabaseAnonKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val bodyText = resp.body?.string()?.take(800)
                    return OpResult(false, "DB insert failed ($code)${if (bodyText.isNullOrBlank()) "" else ": $bodyText"}")
                }
            }
            return OpResult(true)
        }

        // Attempt with size (nice to have). If the column doesn't exist (PGRST204), retry without it.
        val first = doInsert(includeSize = true)
        if (first.isSuccess) return OpResult(true)

        val msg = first.errorMessage.orEmpty()
        val sizeMissing = msg.contains("PGRST204", ignoreCase = true) && msg.contains("'size'", ignoreCase = true)
        if (sizeMissing) {
            return doInsert(includeSize = false)
        }
        return first
    }

    private fun deleteSupabaseObject(
        client: OkHttpClient,
        uploadUrl: String,
        supabaseAnonKey: String,
    ) {
        runCatching {
            val req = Request.Builder()
                .url(uploadUrl)
                .delete()
                .header("apikey", supabaseAnonKey)
                .header("Authorization", "Bearer $supabaseAnonKey")
                .build()
            client.newCall(req).execute().close()
        }
    }

    private fun buildObjectKey(originalName: String): String {
        // Avoid collisions and keep paths safe.
        val safeName = originalName
            .trim()
            .ifBlank { "shared_file" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "uploads/${System.currentTimeMillis()}_${UUID.randomUUID()}_$safeName"
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun fail(message: String): Result {
        val out = Data.Builder()
            .putString(KEY_ERROR_MESSAGE, message)
            .build()
        return Result.failure(out)
    }

    companion object {
        const val KEY_URIS = "uris"
        const val KEY_MIME_TYPES = "mimeTypes"
        const val KEY_FILE_NAMES = "fileNames"
        const val KEY_ERROR_MESSAGE = "errorMessage"
    }
}

