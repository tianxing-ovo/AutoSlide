package com.ltx

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI

/**
 * 应用更新检查
 *
 * @author tianxing
 */
object UpdateChecker {

    /**
     * 更新信息数据类
     *
     * @param versionName 版本名称
     * @param updateLog 更新日志
     * @param downloadUrl 下载URL
     */
    private data class UpdateInfo(
        val versionName: String, val updateLog: String, val downloadUrl: String
    )

    private const val UPDATE_INFO_URL =
        "https://api.github.com/repos/tianxing-ovo/AutoSlide/contents/update.json?ref=master"

    // 用于加速下载的GitHub代理前缀
    private const val GITHUB_PROXY_PREFIX = "https://ghfast.top/"
    private const val TAG = "UpdateChecker"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingUpdateInfo: UpdateInfo? = null
    private var updateDialog: AlertDialog? = null
    private var currentDownloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null

    /**
     * 代理加速GitHub URL
     *
     * @param url 原始url
     * @return 代理加速后的url
     */
    private fun proxyGitHubUrl(url: String): String {
        return if (url.startsWith("https://github.com/") || url.startsWith("https://raw.githubusercontent.com/")) {
            GITHUB_PROXY_PREFIX + url
        } else {
            url
        }
    }

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
        val activityRef = WeakReference(activity)
        val appContext = activity.applicationContext
        scope.launch {
            fetchUpdateInfo(appContext).onSuccess { updateInfo ->
                val act = activityRef.get() ?: return@launch
                if (act.isFinishing || act.isDestroyed) return@launch
                if (updateInfo != null) {
                    pendingUpdateInfo = updateInfo
                    tryShowPendingUpdateDialog(act)
                } else if (showToastOnLatest) {
                    Toast.makeText(act, R.string.already_latest_version, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Log.e(TAG, "check update failed", it)
                if (!showToastOnLatest) return@onFailure
                val act = activityRef.get() ?: return@onFailure
                if (act.isFinishing || act.isDestroyed) return@onFailure
                Toast.makeText(act, R.string.check_update_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 从远端获取更新信息
     *
     * @param context 上下文
     * @return 更新信息
     */
    private suspend fun fetchUpdateInfo(context: Context): Result<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URI.create(UPDATE_INFO_URL).toURL()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.setRequestProperty("User-Agent", "AutoSlide-App")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "HTTP ${connection.responseCode}"
                    }
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    // Base64解码content字段
                    val apiResponse = JSONObject(responseStr)
                    val encodedContent =
                        apiResponse.optString("content", "").replace("\n", "")
                    val decodedContent =
                        String(Base64.decode(encodedContent, Base64.DEFAULT))
                    // 解析实际的update.json
                    val json = JSONObject(decodedContent)
                    val remoteVersionCode = json.optInt("versionCode", 0)
                    val localVersionCode = getLocalVersionCode(context)
                    if (remoteVersionCode > localVersionCode) {
                        UpdateInfo(
                            versionName = json.optString("versionName", ""),
                            updateLog = json.optString("updateLog", ""),
                            downloadUrl = proxyGitHubUrl(json.optString("downloadUrl", ""))
                        )
                    } else {
                        null
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }

    /**
     * 当宿主页面恢复可见时尝试展示待处理更新弹窗
     *
     * @param activity 活动
     */
    fun onHostResumed(activity: Activity) {
        tryShowPendingUpdateDialog(activity)
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
                val lifecycleObserver = object : LifecycleEventObserver {
                    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                        if (event == Lifecycle.Event.ON_DESTROY) {
                            dialog.dismiss()
                            if (updateDialog == dialog) {
                                updateDialog = null
                            }
                            source.lifecycle.removeObserver(this)
                        }
                    }
                }
                lifecycleOwner?.lifecycle?.addObserver(lifecycleObserver)
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
                    lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
                    updateDialog = null
                    Log.d(TAG, "dialog dismissed, pending=${pendingUpdateInfo != null}")
                    if (pendingUpdateInfo != null && !activity.isFinishing && !activity.isDestroyed) {
                        tryShowPendingUpdateDialog(activity)
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
        // 使用ApplicationContext注册/注销广播接收器
        val appContext = activity.applicationContext
        // 注销已有的下载广播接收器
        downloadReceiver?.let {
            runCatching { appContext.unregisterReceiver(it) }
        }
        // 创建下载完成广播接收器
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != currentDownloadId) return
                runCatching { appContext.unregisterReceiver(this) }
                if (downloadReceiver == this) {
                    downloadReceiver = null
                }
                installApk(context, downloadManager, id)
            }
        }
        downloadReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    /**
     * 安装已下载的APK文件
     *
     * @param context 上下文
     * @param downloadManager 下载管理器
     * @param downloadId 下载ID
     */
    private fun installApk(context: Context, downloadManager: DownloadManager, downloadId: Long) {
        val uri = downloadManager.getUriForDownloadedFile(downloadId)
        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                it.printStackTrace()
                Toast.makeText(context, R.string.install_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 获取当前应用的版本号
     *
     * @param context 上下文
     * @return 当前应用的版本号
     */
    private fun getLocalVersionCode(context: Context): Long {
        return context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }
}
