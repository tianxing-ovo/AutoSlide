package com.ltx

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 应用更新检查
 *
 * @author tianxing
 */
object UpdateChecker {
    private data class UpdateInfo(
        val versionName: String, val updateLog: String, val downloadUrl: String
    )

    private const val UPDATE_INFO_URL =
        "https://api.github.com/repos/tianxing-ovo/AutoSlide/contents/update.json?ref=master"
    private const val TAG = "UpdateChecker"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingUpdateInfo: UpdateInfo? = null
    private var updateDialog: AlertDialog? = null
    private var currentDownloadId: Long = -1

    /**
     * 开始检查更新
     *
     * @param activity 活动
     * @param showToastOnLatest 是否在最新版或请求失败时显示吐司提示
     */
    fun checkUpdate(activity: Activity, showToastOnLatest: Boolean = true) {
        if (showToastOnLatest) {
            Toast.makeText(activity, R.string.checking_update, Toast.LENGTH_SHORT).show()
        }
        // 异步请求远端JSON文件
        executor.execute {
            try {
                val url = URL(UPDATE_INFO_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                // 设置User-Agent
                connection.setRequestProperty("User-Agent", "AutoSlide-App")
                // 检查响应状态码
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val responseStr = reader.readText()
                    reader.close()
                    // Base64解码content字段
                    val apiResponse = JSONObject(responseStr)
                    val encodedContent = apiResponse.optString("content", "").replace("\n", "")
                    val decodedContent = String(Base64.decode(encodedContent, Base64.DEFAULT))
                    // 解析实际的update.json
                    val jsonObject = JSONObject(decodedContent)
                    val remoteVersionCode = jsonObject.optInt("versionCode", 0)
                    val remoteVersionName = jsonObject.optString("versionName", "")
                    val downloadUrl = jsonObject.optString("downloadUrl", "")
                    val updateLog = jsonObject.optString("updateLog", "")
                    // 获取本地版本号
                    val localVersionCode = getLocalVersionCode(activity)
                    // 对比版本号并显示对话框
                    mainHandler.post {
                        if (remoteVersionCode > localVersionCode) {
                            Log.d(
                                TAG,
                                "update available: remote=$remoteVersionCode local=$localVersionCode"
                            )
                            pendingUpdateInfo = UpdateInfo(
                                versionName = remoteVersionName,
                                updateLog = updateLog,
                                downloadUrl = downloadUrl
                            )
                            tryShowPendingUpdateDialog(activity)
                        } else if (showToastOnLatest) {
                            Toast.makeText(
                                activity, R.string.already_latest_version, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    mainHandler.post {
                        if (showToastOnLatest) {
                            Toast.makeText(
                                activity, R.string.check_update_failed, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    if (showToastOnLatest) {
                        Toast.makeText(activity, R.string.check_update_failed, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    /**
     * 当宿主页面恢复可见时尝试展示待处理更新弹窗
     *
     * @param activity 活动
     */
    fun onHostResumed(activity: Activity) {
        mainHandler.post {
            tryShowPendingUpdateDialog(activity)
        }
    }

    /**
     * 尝试展示待处理更新弹窗
     *
     * @param activity 活动
     */
    private fun tryShowPendingUpdateDialog(activity: Activity) {
        val updateInfo = pendingUpdateInfo ?: return
        if (updateDialog?.isShowing == true) {
            Log.d(TAG, "dialog already showing, skip")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            Log.d(TAG, "activity finishing/destroyed, skip dialog")
            return
        }
        val lifecycleOwner = activity as? LifecycleOwner
        if (lifecycleOwner != null && !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "activity not resumed, skip dialog")
            return
        }
        Log.d(TAG, "show update dialog")
        updateDialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_found_title, updateInfo.versionName))
            .setMessage(
                updateInfo.updateLog.ifEmpty {
                    activity.getString(R.string.update_found_default_message)
                }).setPositiveButton(R.string.update_now, null)
            .setNegativeButton(R.string.cancel, null).setCancelable(false).create().also { dialog ->
                val shownAt = SystemClock.elapsedRealtime()
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val shownDuration = SystemClock.elapsedRealtime() - shownAt
                        Log.d(TAG, "positive clicked after ${shownDuration}ms")
                        pendingUpdateInfo = null
                        dialog.dismiss()
                        downloadAndInstall(activity, updateInfo.downloadUrl, updateInfo.versionName)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        val shownDuration = SystemClock.elapsedRealtime() - shownAt
                        if (shownDuration < 500L) {
                            Log.d(
                                TAG, "ignore early negative click after ${shownDuration}ms"
                            )
                            return@setOnClickListener
                        }
                        Log.d(TAG, "negative clicked after ${shownDuration}ms")
                        pendingUpdateInfo = null
                        dialog.dismiss()
                    }
                }
                dialog.setOnDismissListener {
                    updateDialog = null
                    Log.d(TAG, "dialog dismissed, pending=${pendingUpdateInfo != null}")
                    if (pendingUpdateInfo != null && !activity.isFinishing && !activity.isDestroyed) {
                        mainHandler.post {
                            tryShowPendingUpdateDialog(activity)
                        }
                    }
                }
                dialog.show()
            }
    }

    /**
     * 使用DownloadManager下载APK并在完成后自动安装
     *
     * @param activity 活动
     * @param downloadUrl 下载URL
     * @param versionName 版本名称
     */
    private fun downloadAndInstall(activity: Activity, downloadUrl: String, versionName: String) {
        // 检查是否有安装未知应用的权限
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.install_permission_required, Toast.LENGTH_SHORT)
                .show()
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${activity.packageName}".toUri()
            )
            activity.startActivity(intent)
            return
        }
        val fileName = "AutoSlide-v$versionName.apk"
        val request = DownloadManager.Request(downloadUrl.toUri())
            .setTitle(activity.getString(R.string.app_name))
            .setDescription(activity.getString(R.string.downloading_update))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        currentDownloadId = downloadManager.enqueue(request)
        Toast.makeText(activity, R.string.downloading_update, Toast.LENGTH_SHORT).show()
        // 注册下载完成广播接收器
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == currentDownloadId) {
                    activity.unregisterReceiver(this)
                    installApk(activity, downloadManager, id)
                }
            }
        }
        // 注册下载完成广播
        activity.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }

    /**
     * 安装已下载的APK文件
     *
     * @param activity 活动
     * @param downloadManager 下载管理器
     * @param downloadId 下载ID
     */
    private fun installApk(activity: Activity, downloadManager: DownloadManager, downloadId: Long) {
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } else {
            Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 获取当前应用的版本号
     *
     * @param activity 活动
     * @return 当前应用的版本号
     */
    private fun getLocalVersionCode(activity: Activity): Long {
        return activity.packageManager.getPackageInfo(
            activity.packageName, PackageManager.PackageInfoFlags.of(0)
        ).longVersionCode
    }
}
