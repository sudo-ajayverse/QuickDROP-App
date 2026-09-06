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
import androidx.activity.ComponentActivity
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager

class ShareReceiverActivity : ComponentActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView
    private lateinit var updateCheckSpinner: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)

        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        versionText = findViewById(R.id.versionText)
        updateCheckSpinner = findViewById(R.id.updateCheckSpinner)

        // Show current version in the top bar
        val currentVersion = com.quickdrop.sharetarget.updater.AutoUpdater.getCurrentVersion(this)
        versionText.text = "v$currentVersion"

        val share = parseShareIntent(intent)
        if (share.uris.isEmpty()) {
            if (intent?.action == Intent.ACTION_MAIN) {
                showIdle(getString(R.string.status_share_hint))
                checkForUpdates()
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

        // Check for updates in the background during share flow
        checkForUpdates()
    }

    private var pendingUpdate: com.quickdrop.sharetarget.updater.AutoUpdater.UpdateInfo? = null

    private fun checkForUpdates() {
        // Show spinner in the top bar while the API call is in-flight
        updateCheckSpinner.visibility = View.VISIBLE

        com.quickdrop.sharetarget.updater.AutoUpdater.checkForUpdates(
            context = this,
            onUpdateAvailable = { updateInfo ->
                runOnUiThread {
                    updateCheckSpinner.visibility = View.GONE
                    if (intent?.action == Intent.ACTION_MAIN) {
                        showUpdateDialog(updateInfo)
                    } else {
                        pendingUpdate = updateInfo
                    }
                }
            },
            onError = { error ->
                runOnUiThread { updateCheckSpinner.visibility = View.GONE }
                android.util.Log.e("ShareReceiverActivity", "Update check failed: $error")
            },
            onUpToDate = {
                runOnUiThread { updateCheckSpinner.visibility = View.GONE }
            }
        )
    }

    private fun showUpdateDialog(updateInfo: com.quickdrop.sharetarget.updater.AutoUpdater.UpdateInfo) {
        if (isFinishing) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage("Version ${updateInfo.version} is available.\n\n${updateInfo.releaseNotes}")
            .setPositiveButton("Update Now") { _, _ ->
                com.quickdrop.sharetarget.updater.AutoUpdater.downloadApk(
                    this,
                    updateInfo.downloadUrl,
                    updateInfo.version
                )
                finishAndRemoveTask()
            }
            .setNegativeButton("Later") { _, _ ->
                finishAndRemoveTask()
            }
            .setOnCancelListener {
                finishAndRemoveTask()
            }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
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

        val uniqueName = "quickdrop_upload_${System.currentTimeMillis()}"
        val wm = WorkManager.getInstance(applicationContext)
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)

        wm.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    showSuccess(getString(R.string.status_uploaded))
                }
                WorkInfo.State.FAILED -> {
                    val message = info.outputData.getString(UploadWorker.KEY_ERROR_MESSAGE)
                        ?: getString(R.string.status_upload_failed)
                    showFailure(message)
                }
                WorkInfo.State.CANCELLED -> showFailure(getString(R.string.status_upload_cancelled))
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
            pendingUpdate = null // Clear to avoid showing multiple times
        } else {
            finishAndRemoveTask()
        }
    }

    private fun showIdle(message: String) {
        progress.visibility = View.GONE
        statusText.text = message
        // Do not auto-close on a normal launcher open.
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
        val type = intent.type

        val uris = mutableListOf<Uri>()

        if (Intent.ACTION_SEND == action) {
            val uri = getStreamUri(intent)
            if (uri != null) {
                uris += uri
            } else {
                val clip = intent.clipData
                if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).uri?.let { uris += it }
                }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val list = getStreamUriList(intent)
            if (list != null) {
                uris += list
            } else {
                val clip = intent.clipData
                if (clip != null && clip.itemCount > 0) {
                    for (idx in 0 until clip.itemCount) {
                        clip.getItemAt(idx).uri?.let { uris += it }
                    }
                }
            }
        }

        // For multiple, Android commonly uses a single wildcard type like "image/*" or "*/*".
        val mimeTypes = uris.map { contentResolver.getType(it) ?: type ?: "application/octet-stream" }
        val fileNames = uris.map { ContentResolverUtils.getDisplayName(contentResolver, it) ?: "shared_file" }

        return ShareData(uris, mimeTypes, fileNames)
    }

    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun getStreamUriList(intent: Intent): ArrayList<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
    }

    data class ShareData(
        val uris: List<Uri>,
        val mimeTypes: List<String>,
        val fileNames: List<String>,
    ) {
        companion object {
            val EMPTY = ShareData(emptyList(), emptyList(), emptyList())
        }
    }
}

