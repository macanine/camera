package com.photoanswer.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.FileProvider
import com.nic.photoanswer.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val rc: Int? = null) {
    val code: Int get() = major * 1_000_000 + minor * 10_000 + patch * 100 + (rc ?: 99)
    companion object {
        fun parse(value: String): ReleaseVersion {
            val m = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-rc\\.([1-9][0-9]*))?")
                .matchEntire(value) ?: error("版本格式无效")
            val parts = m.groupValues.drop(1).take(3).map(String::toInt)
            val rc = m.groupValues[4].takeIf(String::isNotEmpty)?.toInt()
            require(parts[0] in 0..2099 && parts[1] in 0..99 && parts[2] in 0..99 && (rc == null || rc in 1..98))
            return ReleaseVersion(parts[0], parts[1], parts[2], rc)
        }
    }
}

internal data class AppRelease(val versionName: String, val versionCode: Int, val minSdk: Int,
    val apkUrl: String, val sha256: String, val size: Long, val notes: String,
    val signingCertificateSha256: String) {
    companion object {
        const val MAX_APK_BYTES = 150L * 1024 * 1024
        fun parse(raw: String, repository: String): AppRelease {
            require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository))
            val json = JSONObject(raw)
            require(json.getInt("schemaVersion") == 1 && json.getString("applicationId") == BuildConfig.APPLICATION_ID)
            val name = json.getString("versionName")
            val version = ReleaseVersion.parse(name)
            val code = json.getInt("versionCode")
            require(code == version.code)
            val apk = json.getJSONObject("apk")
            val url = apk.getString("url")
            require(url == "https://github.com/$repository/releases/download/v$name/camera-v$name.apk")
            val hash = apk.getString("sha256").lowercase()
            require(Regex("[0-9a-f]{64}").matches(hash))
            val size = apk.getLong("size")
            require(size in 1..MAX_APK_BYTES)
            val minSdk = json.getInt("minSdk")
            require(minSdk >= 26)
            val signer = json.getString("signingCertificateSha256").lowercase()
            require(Regex("[0-9a-f]{64}").matches(signer))
            return AppRelease(name, code, minSdk, url, hash, size,
                json.optString("notes").take(8_000), signer)
        }
    }
}

internal data class UpdateState(val checking: Boolean = false, val release: AppRelease? = null,
    val downloading: Boolean = false, val progress: Int = 0, val ready: File? = null,
    val prompt: Boolean = false, val message: String = "", val automatic: Boolean = true)

internal fun UpdateState.found(available: AppRelease): UpdateState = copy(
    release = available, ready = if (release == available) ready else null,
    progress = if (release == available) progress else 0, prompt = true,
    message = "发现新版本 ${available.versionName}"
)

internal fun UpdateState.checked(available: AppRelease?, currentCode: Int, sdk: Int, manual: Boolean): UpdateState = when {
    available == null || available.versionCode <= currentCode -> copy(release = null, ready = null,
        progress = 0, prompt = false, message = if (manual) "当前已是最新版本" else "")
    available.minSdk > sdk -> copy(release = null, ready = null, progress = 0, prompt = false,
        message = "新版本需要更高的 Android 系统版本")
    else -> found(available)
}

internal fun shouldCheckForUpdates(now: Long, last: Long, automatic: Boolean, manual: Boolean): Boolean =
    manual || (automatic && (last <= 0 || now < last || now - last >= 6 * 60 * 60 * 1000L))

/** Public GitHub metadata is checked over HTTPS. APK identity and signing are checked independently. */
internal object AppUpdates {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES)
        // GitHub release assets redirect to a signed asset URL. The exact host
        // remains allowlisted, and the APK digest and signer are checked separately.
        .followRedirects(true).followSslRedirects(true).addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!isGitHubUpdateUrl(url)) throw IOException("Unsupported update host")
            chain.proceed(chain.request())
        }.build()
    private val metadataHttp = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    fun check(context: Context, manual: Boolean = false) = AppRuntime.scope.launch {
        checkMutex.withLock {
            if (BuildConfig.DEBUG) {
                if (manual) mutableState.update { it.copy(message = "调试版本不参与更新，请使用发布版本") }
                return@withLock
            }
            if (state.value.downloading) return@withLock
            val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
            val automatic = prefs.getBoolean("automatic", true)
            mutableState.update { it.copy(automatic = automatic) }
            val now = System.currentTimeMillis()
            val last = prefs.getLong("lastCheck", 0)
            if (!shouldCheckForUpdates(now, last, automatic, manual)) return@withLock
            mutableState.update { it.copy(checking = true, message = "") }
            try {
                val repository = BuildConfig.UPDATE_REPOSITORY
                val release = read("https://github.com/$repository/releases/latest/download/update.json", metadataHttp) { response ->
                    if (response.code == 404) return@read null
                    check(response.isSuccessful)
                    val source = response.body?.source() ?: error("missing metadata")
                    val buffer = okio.Buffer()
                    while (source.read(buffer, 8192) != -1L) require(buffer.size <= 64 * 1024)
                    AppRelease.parse(buffer.readUtf8(), repository)
                }
                prefs.edit { putLong("lastCheck", now) }
                mutableState.update { it.checked(release, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, manual) }
                if (state.value.release == null) {
                    File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(message = if (manual) "暂时无法检查更新，请稍后重试" else "") } }
            finally { mutableState.update { it.copy(checking = false) } }
        }
    }

    fun setAutomatic(context: Context, enabled: Boolean) = AppRuntime.scope.launch {
        val stored = context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean("automatic", enabled).commit()
        mutableState.update { if (stored) it.copy(automatic = enabled) else it.copy(message = "更新设置未能保存，请重试") }
    }
    fun dismiss() { mutableState.update { it.copy(prompt = false) } }
    fun show() { mutableState.update { it.copy(prompt = it.release != null) } }

    fun download(context: Context) = AppRuntime.scope.launch {
        if (!downloadMutex.tryLock()) return@launch
        var temporary: File? = null
        try {
            val release = checkMutex.withLock {
                val available = state.value.release ?: return@launch
                mutableState.update { it.copy(downloading = true, progress = 0, message = "正在下载更新") }
                available
            }
            val directory = File(context.cacheDir, "updates").apply { check(mkdirs() || isDirectory) }
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "camera-${release.versionCode}.apk")
            val part = File(directory, "camera-${release.versionCode}.part")
            temporary = part
            read(release.apkUrl) { response ->
                check(response.isSuccessful)
                val body = response.body ?: error("empty APK")
                val declared = body.contentLength()
                require(declared == -1L || declared == release.size)
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                part.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= release.size && received <= AppRelease.MAX_APK_BYTES)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val progress = (received * 100 / release.size).toInt()
                            if (progress != state.value.progress) mutableState.update { it.copy(progress = progress) }
                        }
                    }
                }
                require(received == release.size && digest.digest().hex() == release.sha256)
            }
            validateApk(context, part, release)
            check(part.renameTo(target))
            mutableState.update { it.copy(ready = target, message = "更新已就绪，确认后安装") }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableState.update { it.copy(ready = null, message = "更新下载或校验失败，请重试") } }
        finally { temporary?.delete(); mutableState.update { it.copy(downloading = false) }; downloadMutex.unlock() }
    }

    @Suppress("DEPRECATION")
    internal fun validateApk(context: Context, file: File, release: AppRelease) {
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flag) ?: error("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flag)
        validateIdentity(archive, installed, release, context.packageName)
    }

    @Suppress("DEPRECATION")
    internal fun validateIdentity(archive: android.content.pm.PackageInfo, installed: android.content.pm.PackageInfo,
        release: AppRelease, applicationId: String) {
        require(archive.packageName == applicationId && archive.versionName == release.versionName)
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(code == release.versionCode.toLong() && code > installedCode)
        fun signers(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }?.toSet().orEmpty()
        val current = signers(installed)
        require(current.isNotEmpty() && signers(archive) == current)
        require(current == setOf(release.signingCertificateSha256))
    }

    suspend fun installIntent(context: Context): Intent = withContext(Dispatchers.IO) {
        try {
        val snapshot = state.value
        val file = snapshot.ready ?: error("更新文件尚未就绪")
        val release = snapshot.release ?: error("更新信息已失效")
        require(file.length() == release.size)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        require(digest.digest().hex() == release.sha256)
        validateApk(context, file, release)
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,
            "${context.packageName}.updates", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutableState.update { it.copy(ready = null, message = "更新文件已失效，请重新下载") }
            throw e
        }
    }

    private suspend fun <T> read(url: String, client: OkHttpClient = http, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url.toHttpUrl()).header("User-Agent", "Camera/${BuildConfig.VERSION_NAME}").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use(consume)
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }

    internal fun isGitHubUpdateUrl(url: HttpUrl): Boolean = url.isHttps &&
        (url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
