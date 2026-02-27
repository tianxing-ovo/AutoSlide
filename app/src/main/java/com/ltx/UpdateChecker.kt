package com.ltx

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 检查应用更新的辅助工具类
 *
 * @author tianxing
 */
object UpdateChecker {
    private const val UPDATE_INFO_URL = "https://raw.githubusercontent.com/tianxing-ovo/AutoSlide/master/update.json"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 开始检查更新
     *
     * @param activity 宿主 Activity
     * @param showToastOnLatest 如果已经是最新版或请求失败，是否弹出吐司提示 (如果是用户主动点击触发的，传 true)
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
                // 检查响应状态码
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val responseStr = reader.readText()
                    reader.close()
                    // 解析JSON字符串
                    val jsonObject = JSONObject(responseStr)
                    val remoteVersionCode = jsonObject.optInt("versionCode", 0)
                    val remoteVersionName = jsonObject.optString("versionName", "")
                    val downloadUrl = jsonObject.optString("downloadUrl", "")
                    val updateLog = jsonObject.optString("updateLog", "")

                    // 获取本地版本号
                    val localVersionCode = getLocalVersionCode(activity)

                    mainHandler.post {
                        if (remoteVersionCode > localVersionCode) {
                            showUpdateDialog(activity, remoteVersionName, updateLog, downloadUrl)
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

    private fun showUpdateDialog(
        activity: Activity, versionName: String, updateLog: String, downloadUrl: String
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_found_title, versionName))
            .setMessage(updateLog.ifEmpty { activity.getString(R.string.update_found_default_message) })
            .setPositiveButton(R.string.update_now) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, downloadUrl.toUri())
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(activity, R.string.invalid_download_url, Toast.LENGTH_SHORT)
                        .show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * 通过PackageManager获取当前应用的版本号
     *
     * @param activity 宿主Activity
     * @return 当前应用的versionCode
     */
    private fun getLocalVersionCode(activity: Activity): Long {
        return activity.packageManager.getPackageInfo(
            activity.packageName, PackageManager.PackageInfoFlags.of(0)
        ).longVersionCode
    }
}
