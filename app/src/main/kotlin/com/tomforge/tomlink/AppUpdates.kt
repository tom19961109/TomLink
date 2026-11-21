package com.tomforge.tomlink

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class UpdateFileProvider : FileProvider()

internal class AppUpdates(private val context: Context) {
    data class Release(val code: Long, val name: String, val hash: String, val apkUrl: String)

    private val packages = context.packageManager
    private val installed = packages.getPackageInfo(context.packageName, SIGNING_FLAGS)
    val versionName: String get() = installed.versionName.orEmpty()
    private val directory = File(context.cacheDir, "updates")
    val apk = File(directory, APK_NAME)

    fun check(): Release? {
        val releases = JSONArray(readText("$API/releases?per_page=20", 2 * 1024 * 1024))
        return (0 until releases.length()).mapNotNull { index ->
            val release = releases.getJSONObject(index)
            val tag = release.getString("tag_name")
            if (release.optBoolean("draft") || !TAG.matches(tag)) return@mapNotNull null
            val assets = release.getJSONArray("assets")
            val base = "$DOWNLOAD/$tag"
            val urls = (0 until assets.length()).map {
                assets.getJSONObject(it).optString("browser_download_url")
            }.toSet()
            val manifestUrl = "$base/update.json"
            val apkUrl = "$base/$APK_NAME"
            if (manifestUrl !in urls || apkUrl !in urls) return@mapNotNull null
            val manifest = JSONObject(readText(manifestUrl, 4096))
            val code = manifest.getLong("versionCode")
            val name = manifest.getString("versionName")
            val hash = manifest.getString("sha256")
            check(code > 0 && tag == "v$name" && HASH.matches(hash)) { "更新資訊有誤" }
            Release(code, name, hash, apkUrl)
        }.maxByOrNull { it.code }?.takeIf { it.code > installed.code() }
    }

    fun download(release: Release) {
        directory.mkdirs()
        val partial = File(directory, "download.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            request(release.apkUrl) { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 100L * 1024 * 1024) { "更新檔案過大" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash.equals(release.hash, ignoreCase = true)) { "更新檔案驗證失敗，請重新下載" }
            check(partial.renameTo(apk)) { "無法儲存更新檔案" }
            try {
                validateApk(release.code)
            } catch (error: Exception) {
                apk.delete()
                throw error
            }
        } finally {
            partial.delete()
        }
    }

    fun validateApk(expectedCode: Long? = null) {
        val archive = packages.getPackageArchiveInfo(apk.path, SIGNING_FLAGS)
        check(archive != null && archive.packageName == installed.packageName &&
            archive.code() > installed.code() && (expectedCode == null || archive.code() == expectedCode)
        ) { "更新檔案的版本不符" }
        val signers = installed.signers()
        check(signers.isNotEmpty() && archive.signers() == signers) { "更新檔案的簽章與目前版本不同" }
    }

    private fun readText(url: String, limit: Int): String = request(url) { input ->
        val bytes = ByteArray(limit + 1)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) break
            offset += count
        }
        check(offset <= limit) { "更新資訊過大" }
        String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun <T> request(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "TomLink/$versionName")
            check(connection.responseCode == 200) { "無法連線至更新服務（HTTP ${connection.responseCode}）" }
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.code(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers() =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty().toSet()

    companion object {
        private const val API = "https://api.github.com/repos/tom19961109/TomLink"
        private const val DOWNLOAD = "https://github.com/tom19961109/TomLink/releases/download"
        private const val APK_NAME = "TomLink-Android-preview.apk"
        private val TAG = Regex("""v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
        private val HASH = Regex("[a-fA-F0-9]{64}")
        @Suppress("DEPRECATION")
        private val SIGNING_FLAGS = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }
}
