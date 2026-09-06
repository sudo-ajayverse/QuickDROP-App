package com.quickdrop.sharetarget.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

class DownloadReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DownloadReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadId == -1L) return

            Log.i(TAG, "Download complete for ID: $downloadId")
            
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            
            if (cursor != null && cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex != -1) {
                    val status = cursor.getInt(statusIndex)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = downloadManager.getUriForDownloadedFile(downloadId)
                        if (uri != null) {
                            installApk(context, uri)
                        } else {
                            Log.e(TAG, "Download successful but URI is null")
                        }
                    } else {
                        Log.e(TAG, "Download failed or cancelled. Status: $status")
                    }
                }
                cursor.close()
            }
        }
    }

    private fun installApk(context: Context, uri: Uri) {
        try {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // For modern Android, we don't need FLAG_ACTIVITY_CLEAR_TOP usually, 
                // but let's ensure the installer stays on top.
            }

            context.startActivity(installIntent)
            Log.i(TAG, "Install intent started for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install intent", e)
        }
    }
}
