package com.photoanswer.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val allowFallback: Boolean = true,
    val maxConcurrent: Int = 1,
    val fallbackOnly: Boolean = false,
    val allowVerification: Boolean = true,
    val streaming: Boolean = true,
    val imageMaxEdge: Int = 4096,
    val progressTimeoutSeconds: Int = 300
) {
    val isConfigured: Boolean get() = baseUrl.toHttpUrlOrNull()?.let {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    } == true && model.isNotBlank() && apiKey.isNotBlank()
}

/** All requests, including reviews, reserve both a model slot and a credential-group slot. */
internal class RequestCoordinator(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val leases = mutableMapOf<String, ProviderConfig>()
    private val cooldowns = mutableMapOf<String, Long>()
    private val failures = mutableMapOf<String, Int>()
    private val blockedCredentials = mutableSetOf<String>()
    private val blockedEndpoints = mutableSetOf<String>()
    private fun group(p: ProviderConfig): String {
        val origin = p.baseUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" } ?: p.baseUrl
        return "$origin|${p.apiKey}"
    }
    private fun endpoint(p: ProviderConfig) = "${group(p)}|${p.baseUrl.trimEnd('/')}|${p.model}|${p.streaming}"
    val size: Int @Synchronized get() = leases.size

    @Synchronized fun usable(p: ProviderConfig): Boolean = group(p) !in blockedCredentials && endpoint(p) !in blockedEndpoints
    @Synchronized fun readyAt(p: ProviderConfig): Long = maxOf(cooldowns[group(p)] ?: 0, cooldowns[endpoint(p)] ?: 0)
    @Synchronized fun acquire(owner: String, p: ProviderConfig, providers: List<ProviderConfig>): Boolean {
        if (owner in leases || leases.size >= 4 || !usable(p) || readyAt(p) > now()) return false
        if (leases.values.count { endpoint(it) == endpoint(p) } >= p.maxConcurrent) return false
        val groupLimit = providers.filter { group(it) == group(p) }.minOfOrNull { it.maxConcurrent } ?: p.maxConcurrent
        if (leases.values.count { group(it) == group(p) } >= groupLimit) return false
        leases[owner] = p
        return true
    }
    @Synchronized fun release(owner: String) { leases.remove(owner) }
    @Synchronized fun success(p: ProviderConfig) { failures.remove(endpoint(p)) }
    @Synchronized fun failure(p: ProviderConfig, error: ModelException): Long {
        val key = endpoint(p)
        when (error.code) {
            401, 403 -> { blockedCredentials += group(p); return 0 }
            400, 404, 405, 415, 422 -> { blockedEndpoints += key; return 0 }
        }
        if (!error.retryable) return 0
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        val backoff = maxOf(error.retryAfterMs,
            if (count >= 3) 60_000 else (1_000L shl (count - 1).coerceAtMost(5)) + kotlin.random.Random.nextLong(0, 500))
        cooldowns[if (error.code == 429) group(p) else key] = now() + backoff
        return backoff
    }
}

internal object RetryPolicy {
    fun candidates(providers: List<ProviderConfig>, counts: Map<String, Int>, retryable: Set<String>): List<ProviderConfig> {
        val eligible = ProviderRouting.ordered(providers).filter {
            val count = counts[it.id] ?: 0
            if (counts.isEmpty()) !it.fallbackOnly
            else (count == 0 && it.allowFallback) || (count == 1 && it.id in retryable)
        }
        return eligible.sortedBy { counts[it.id] ?: 0 }
    }
}

object ProviderRouting {
    const val MAX_ATTEMPTS = 3

    fun ordered(providers: List<ProviderConfig>): List<ProviderConfig> =
        providers.filter { it.enabled && it.isConfigured }.sortedBy { it.priority }

    fun candidates(providers: List<ProviderConfig>, used: Set<String>): List<ProviderConfig> =
        ordered(providers).filter {
            it.id !in used && if (used.isEmpty()) !it.fallbackOnly else it.allowFallback
        }

    fun verifiers(providers: List<ProviderConfig>, producerId: String): List<ProviderConfig> =
        ordered(providers).filter { it.id != producerId && !it.fallbackOnly && it.allowVerification }

    fun move(providers: List<ProviderConfig>, id: String, offset: Int): List<ProviderConfig> {
        val list = providers.sortedBy { it.priority }.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0 || from + offset !in list.indices) return list
        val item = list.removeAt(from)
        list.add(from + offset, item)
        return list.mapIndexed { index, provider -> provider.copy(priority = index) }
    }
}
