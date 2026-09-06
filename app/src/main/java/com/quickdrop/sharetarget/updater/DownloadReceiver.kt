package com.quickdrop.sharetarget.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

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
            
            val cursor: Cursor = downloadManager.query(query)
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex >= 0) {
                    val status = cursor.getInt(statusIndex)
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        // The download was successful, we can get the URI
                        val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriIndex >= 0) {
                            val localUriString = cursor.getString(uriIndex)
                            if (localUriString != null) {
                                val localUri = Uri.parse(localUriString)
                                installApk(context, localUri)
                            }
                        }
                    } else {
                        Log.e(TAG, "Download failed with status: $status")
                    }
                }
            }
            cursor.close()
        }
    }

    private fun installApk(context: Context, uri: Uri) {
        try {
            // Convert to file scheme if needed, or if it's already a file URI
            val file = File(uri.path!!)
            
            val contentUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            Log.i(TAG, "Install intent started for $contentUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install intent", e)
        }
    }
}
