package com.quickdrop.sharetarget

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.quickdrop.sharetarget.updater.AutoUpdater

class ShareReceiverActivity : ComponentActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var updateCheckSpinner: ProgressBar
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)

        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        versionText = findViewById(R.id.versionText)
        updateCheckSpinner = findViewById(R.id.updateCheckSpinner)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swipeRefresh.setOnRefreshListener {
            checkForUpdates(isManualRefresh = true)
        }

        // Show current version
        val currentVersion = AutoUpdater.getCurrentVersion(this)
        versionText.text = "v$currentVersion"

        val share = parseShareIntent(intent)
        if (share.uris.isEmpty()) {
            if (intent?.action == Intent.ACTION_MAIN) {
                showIdle(getString(R.string.status_share_hint))
                checkForUpdates(isManualRefresh = false)
            } else {
                showFailure(getString(R.string.status_no_file))
            }
            return
        }

        if (!isNetworkAvailable()) {
            showFailure(getString(R.string.status_no_internet))
            return
        }

        enqueueUploadWork(share)
        checkForUpdates(isManualRefresh = false)
    }

    private var pendingUpdate: AutoUpdater.UpdateInfo? = null

    private fun checkForUpdates(isManualRefresh: Boolean) {
        if (!isManualRefresh) {
            updateCheckSpinner.visibility = View.VISIBLE
        }

        AutoUpdater.checkForUpdates(
            context = this,
            onUpdateAvailable = { updateInfo ->
                runOnUiThread {
                    updateCheckSpinner.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    if (intent?.action == Intent.ACTION_MAIN) {
                        showUpdateDialog(updateInfo)
                    } else {
                        pendingUpdate = updateInfo
                    }
                }
            },
            onError = { _ ->
                runOnUiThread { 
                    updateCheckSpinner.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    if (isManualRefresh) {
                        Toast.makeText(this, "Update check failed.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onUpToDate = {
                runOnUiThread { 
                    updateCheckSpinner.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    if (isManualRefresh) {
                        Toast.makeText(this, "You have the latest version!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun showUpdateDialog(updateInfo: AutoUpdater.UpdateInfo) {
        if (isFinishing) return
        
        val cleanNotes = stripMarkdown(updateInfo.releaseNotes)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("New Update Available")
            .setIcon(android.R.drawable.ic_dialog_info)
            .setMessage("Version ${updateInfo.version} is ready to download.\n\nWhat's New:\n${cleanNotes.ifBlank { "Performance improvements and bug fixes." }}")
            .setPositiveButton("Update Now") { _, _ ->
                Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()
                AutoUpdater.downloadApk(this, updateInfo.downloadUrl, updateInfo.version)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("#{1,6}\\s*"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("^\\s*[*-]\\s+", RegexOption.MULTILINE), "• ")
            .trim()
    }

    private fun enqueueUploadWork(share: ShareData) {
        statusText.text = getString(R.string.status_uploading)
        progress.visibility = View.VISIBLE

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workData = Data.Builder()
            .putStringArray(UploadWorker.KEY_URIS, share.uris.map { it.toString() }.toTypedArray())
            .putStringArray(UploadWorker.KEY_MIME_TYPES, share.mimeTypes.toTypedArray())
            .putStringArray(UploadWorker.KEY_FILE_NAMES, share.fileNames.toTypedArray())
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setInputData(workData)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "upload_${System.currentTimeMillis()}",
            ExistingWorkPolicy.REPLACE,
            request
        )

        WorkManager.getInstance(applicationContext).getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            if (info == null) return@observe
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> showSuccess(getString(R.string.status_uploaded))
                WorkInfo.State.FAILED -> {
                    val msg = info.outputData.getString(UploadWorker.KEY_ERROR_MESSAGE) ?: getString(R.string.status_upload_failed)
                    showFailure(msg)
                }
                else -> Unit
            }
        }
    }

    private fun showSuccess(message: String) {
        progress.visibility = View.GONE
        statusText.text = message
        statusText.postDelayed({ finishOrShowUpdate() }, 1200)
    }

    private fun showFailure(message: String) {
        progress.visibility = View.GONE
        statusText.text = message
        statusText.postDelayed({ finishOrShowUpdate() }, 1800)
    }

    private fun finishOrShowUpdate() {
        val update = pendingUpdate
        if (update != null) {
            showUpdateDialog(update)
            pendingUpdate = null
        } else {
            finishAndRemoveTask()
        }
    }

    private fun showIdle(message: String) {
        progress.visibility = View.GONE
        statusText.text = message
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService<ConnectivityManager>() ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun parseShareIntent(intent: Intent?): ShareData {
        if (intent == null) return ShareData.EMPTY
        val action = intent.action
        val uris = mutableListOf<Uri>()

        if (Intent.ACTION_SEND == action) {
            getStreamUri(intent)?.let { uris += it }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            getStreamUriList(intent)?.let { uris += it }
        }

        val mimeTypes = uris.map { contentResolver.getType(it) ?: intent.type ?: "application/octet-stream" }
        val fileNames = uris.map { ContentResolverUtils.getDisplayName(contentResolver, it) ?: "shared_file" }

        return ShareData(uris, mimeTypes, fileNames)
    }

    private fun getStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun getStreamUriList(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    data class ShareData(val uris: List<Uri>, val mimeTypes: List<String>, val fileNames: List<String>) {
        companion object { val EMPTY = ShareData(emptyList(), emptyList(), emptyList()) }
    }
}
