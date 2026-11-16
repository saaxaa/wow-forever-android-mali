package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import timber.log.Timber

object AppUpdater {
    private const val REPO = "saaxaa/wow-forever-android-mali"
    internal const val MALI_APK = "WoW-Forever-Mali-side-by-side-debug.apk"
    private val releasesHttp by lazy { Net.http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build() }
    private val maliTag = Regex("""v?[0-9]+(?:\.[0-9]+)+-mali\.([0-9]+)""")
    private val packageAsset = if (BuildConfig.APPLICATION_ID.endsWith(".mali")) MALI_APK else "WoW-Forever.apk"

    data class Release(
        val version: String,
        val name: String,
        val notes: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    )

    fun isNewer(latest: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val parse = { v: String ->
            v.trimStart('v', 'V')
                .split('-', '_', '+')[0]
                .split('.')
                .mapNotNull { it.toIntOrNull() }
        }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        fun maliRevision(version: String) =
            maliTag.matchEntire(version)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return maliRevision(latest) > maliRevision(current)
    }

    internal fun findUpdate(body: String, current: String, assetName: String): Release? {
        val releases = JSONArray(body)
        var newest: Release? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val candidate = parseRelease(release, newest?.version ?: current, assetName) ?: continue
            newest = candidate
        }
        return newest
    }

    internal fun parseRelease(json: JSONObject, currentVersion: String, assetName: String): Release? {
        val tag = json.optString("tag_name")
        if (json.optBoolean("draft") || !isNewer(tag, currentVersion)) return null
        if (assetName == MALI_APK && !maliTag.matches(tag)) return null
        if (assetName != MALI_APK && json.optBoolean("prerelease")) return null
        val assets = json.optJSONArray("assets") ?: return null
        val expectedUrl = "https://github.com/$REPO/releases/download/$tag/$assetName"
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == assetName && asset.optString("browser_download_url") == expectedUrl) {
                return Release(
                    version = tag.trimStart('v', 'V'),
                    name = json.optString("name", tag),
                    notes = json.optString("body"),
                    downloadUrl = expectedUrl,
                    sizeBytes = asset.optLong("size", 0L),
                )
            }
        }
        return null
    }

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases?per_page=100")
                .header("User-Agent", "WoW-Forever-Android")
                .header("Accept", "application/vnd.github+json")
                .build()
            releasesHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                findUpdate(body, currentVersion, packageAsset)
            }
        }.getOrElse {
            Timber.w(it, "AppUpdater check failed")
            null
        }
    }

    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "WoW-Forever-${release.version}.apk")
        if (dest.exists() && dest.length() == release.sizeBytes && release.sizeBytes > 0) {
            onProgress(1f)
            return@withContext dest
        }
        Net.fetchFile(release.downloadUrl, dest, onProgress)
        dest
    }

    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrElse {
        Timber.w(it, "canRequestPackageInstalls check failed")
        false
    }

    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    fun install(context: Context, apkFile: File) {
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
