package com.photoanswer.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

/** Keep unsaved connection edits across rotation without serializing API keys into saved state. */
internal class SettingsDraft : ViewModel() {
    var saved by mutableStateOf<List<ProviderConfig>>(emptyList())
    var providers by mutableStateOf<List<ProviderConfig>>(emptyList())
    private var initialized = false
    var loading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf("")
        private set
    var saving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf("")
        private set
    private var saveJob: Deferred<Unit>? = null
    fun initialize(context: Context) {
        if (!initialized) {
            initialized = true
            viewModelScope.launch {
                try {
                    saved = withContext(Dispatchers.IO) { SecurePrefs.load(context) }
                    providers = saved
                } catch (e: CancellationException) { throw e }
                catch (e: SettingsStorageException) { loadError = e.message.orEmpty() }
                catch (e: Exception) { loadError = "连接配置暂时无法读取，诊断编号 ${LocalDiagnostics.record("settings-load", e)}" }
                finally { loading = false }
            }
        }
    }
    // Owned by the ViewModel: rotation or leaving composition cannot cancel a durable save.
    fun save(context: Context, value: List<ProviderConfig>): Deferred<Unit> {
        saveJob?.takeIf { it.isActive }?.let { return it }
        saving = true
        saveError = ""
        return viewModelScope.async {
            try {
                withContext(Dispatchers.IO) { SecurePrefs.save(context.applicationContext, value) }
                saved = value
                providers = value
                acknowledgeRepair()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                saveError = if (e is SettingsStorageException) e.message.orEmpty()
                    else "设置保存失败，诊断编号 ${LocalDiagnostics.record("settings-save", e)}"
                throw e
            } finally { saving = false }
        }.also { saveJob = it }
    }
    fun discard() { if (!saving) providers = saved }
    fun acknowledgeRepair() { loadError = "" }
}
