package com.quickdrop.sharetarget.updater

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.quickdrop.sharetarget.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object AutoUpdater {
    private const val TAG = "AutoUpdater"
    
    // Use repo info from BuildConfig (defined in build.gradle.kts from gradle.properties)
    private val REPO_OWNER = BuildConfig.REPO_OWNER
    private val REPO_NAME = BuildConfig.REPO_NAME
    private val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private val client = OkHttpClient()

    data class UpdateInfo(
        val version: String,
        val releaseNotes: String,
        val downloadUrl: String
    )

    fun checkForUpdates(
        context: Context,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onError: ((String) -> Unit)? = null,
        onUpToDate: (() -> Unit)? = null
    ) {
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to check for updates", e)
                onError?.invoke("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "GitHub API returned error: ${response.code}")
                    onError?.invoke("API error: ${response.code}")
                    return
                }

                try {
                    val responseBody = response.body?.string()
                    if (responseBody == null) {
                        onError?.invoke("Empty response")
                        return
                    }

                    val json = JSONObject(responseBody)
                    val tagName = json.getString("tag_name")
                    val body = json.optString("body", "No release notes provided.")
                    
                    val assets = json.getJSONArray("assets")
                    var downloadUrl: String? = null
                    
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    if (downloadUrl == null) {
                        Log.e(TAG, "No APK asset found in the latest release.")
                        onError?.invoke("No APK asset found")
                        return
                    }

                    val currentVersion = getCurrentVersion(context)
                    if (isNewerVersion(currentVersion, tagName)) {
                        onUpdateAvailable(UpdateInfo(tagName, body, downloadUrl))
                    } else {
                        Log.i(TAG, "App is up to date (current: $currentVersion, remote: $tagName)")
                        onUpToDate?.invoke()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse release JSON", e)
                    onError?.invoke("Parse error: ${e.message}")
                }
            }
        })
    }

    fun downloadApk(context: Context, downloadUrl: String, versionTag: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("Downloading QuickDrop Update")
                .setDescription("Version $versionTag is downloading...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "QuickDrop-$versionTag.apk")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Log.i(TAG, "Download enqueued for $downloadUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue download", e)
        }
    }

    internal fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    internal fun isNewerVersion(current: String, remote: String): Boolean {
        val currentClean = current.replace(Regex("[^0-9.]"), "")
        val remoteClean = remote.replace(Regex("[^0-9.]"), "")

        val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = remoteClean.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLength) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (c > r) return false
        }
        return false
    }
}
