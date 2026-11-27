package com.dallycontrol.core.net

import com.dallycontrol.core.config.ServerConfigStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Rewrites each request's scheme/host/port to the currently-configured server
 * ([ServerConfigStore.baseUrl]) so the Retrofit base URL can be a static placeholder and the real
 * target is resolved per-request — the server URL is only known after provisioning. Paths/queries
 * are preserved (deployments are hosted at the domain root). With [ServerEndpoints] as the source, requests follow
 * the agent to a backup URL while the primary one keeps failing.
 */
class BaseUrlInterceptor(private val baseUrl: () -> String) : Interceptor {
    constructor(serverConfig: ServerConfigStore) : this(serverConfig::baseUrl)

    override fun intercept(chain: Interceptor.Chain): Response {
        val configured = baseUrl().toHttpUrlOrNull()
            ?: return chain.proceed(chain.request())
        val req = chain.request()
        val newUrl = req.url.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()
        return chain.proceed(req.newBuilder().url(newUrl).build())
    }
}
