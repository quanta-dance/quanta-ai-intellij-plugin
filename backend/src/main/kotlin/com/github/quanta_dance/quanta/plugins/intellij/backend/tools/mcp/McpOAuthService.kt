// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.tools.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.quanta_dance.quanta.plugins.intellij.backend.logging.QDLog
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Implements OAuth 2.1 Authorization Code with PKCE for protected remote MCP servers. */
internal class McpOAuthService {
    private val log = Logger.getInstance(McpOAuthService::class.java)
    private val mapper = jacksonObjectMapper()
    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    private val authorizationRequests = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val recentAuthorizationFailures = ConcurrentHashMap<String, RecentFailure>()

    /**
     * Returns a valid bearer token, coalescing concurrent callers into one interactive authorization flow.
     *
     * MCP discovery can be requested concurrently by settings synchronization and tool enumeration. Without
     * coalescing, each request can independently open a browser window before the first token is saved.
     *
     * Token values are never logged. The surrounding safe diagnostics record only length and a short SHA-256
     * fingerprint, allowing credential-store integrity to be verified without exposing a bearer token.
     */
    fun accessToken(
        serverName: String,
        resourceUrl: String,
        challenge: AuthorizationChallenge,
        configuredClientId: String? = null,
    ): String {
        val resource = requireHttps(resourceUrl, "MCP resource URL")
        val requestKey = "$serverName|$resource"
        recentAuthorizationFailures[requestKey]?.let { failure ->
            if (failure.occurredAt.plusSeconds(AUTHORIZATION_RETRY_COOLDOWN_SECONDS).isAfter(Instant.now())) {
                error(
                    "OAuth authorization for '$serverName' recently failed; retry is paused for " +
                        "$AUTHORIZATION_RETRY_COOLDOWN_SECONDS seconds: ${failure.message}",
                )
            }
            recentAuthorizationFailures.remove(requestKey, failure)
        }
        val newRequest = CompletableFuture<String>()
        val existingRequest = authorizationRequests.putIfAbsent(requestKey, newRequest)
        if (existingRequest != null) {
            log.info("MCP OAuth authorization is already in progress for '$serverName'; reusing it")
            return existingRequest.get()
        }

        return try {
            log.info("MCP OAuth: acquiring token for '$serverName'")
            val token = acquireAccessToken(serverName, resource, challenge, configuredClientId)
            recentAuthorizationFailures.remove(requestKey)
            newRequest.complete(token)
            token
        } catch (error: Throwable) {
            val summary = error.message ?: error.javaClass.simpleName
            recentAuthorizationFailures[requestKey] = RecentFailure(Instant.now(), summary)
            log.warn("MCP OAuth: token acquisition failed for '$serverName': $summary")
            newRequest.completeExceptionally(error)
            throw error
        } finally {
            authorizationRequests.remove(requestKey, newRequest)
        }
    }

    private fun acquireAccessToken(
        serverName: String,
        resource: URI,
        challenge: AuthorizationChallenge,
        configuredClientId: String?,
    ): String {
        val stored = loadToken(serverName, resource)
        if (stored != null && stored.expiresAt > Instant.now().plusSeconds(60).epochSecond) {
            QDLog.info(log) {
                "MCP OAuth: reusing a stored access token for '$serverName' (${safeTokenDescription(stored.accessToken)})"
            }
            return stored.accessToken
        }
        QDLog.info(log) {
            "MCP OAuth: ${if (stored == null) "no stored token" else "stored token is expired"} for '$serverName'"
        }

        QDLog.info(log) { "MCP OAuth: discovering protected-resource metadata for '$serverName' from its 401 challenge" }
        val protectedResource = discoverProtectedResource(resource, challenge.metadataUri)
        QDLog.info(log) { "MCP OAuth: discovering authorization-server metadata for '$serverName'" }
        val metadata = discoverAuthorizationServer(protectedResource.authorizationServer)
        val clientId =
            configuredClientId?.takeIf { it.isNotBlank() }
                ?: loadClientId(serverName, resource)
                ?: registerClient(metadata, resource).also { saveClientId(serverName, resource, it) }
        val token =
            if (stored?.refreshToken != null) {
                log.info("MCP OAuth: refreshing stored token for '$serverName'")
                runCatching { refresh(metadata, clientId, stored.refreshToken, resource) }
                    .onFailure {
                        log.info(
                            "MCP OAuth refresh failed for '$serverName'; starting interactive login: ${it.message}",
                        )
                    }.getOrNull()
            } else {
                null
            } ?: authorize(
                serverName,
                metadata,
                challenge.scopes.ifEmpty { protectedResource.scopes },
                clientId,
                resource,
            )
        saveToken(serverName, resource, token)
        QDLog.info(log) {
            "MCP OAuth: stored token securely for '$serverName' (${safeTokenDescription(token.accessToken)}, " +
                "refreshTokenPresent=${token.refreshToken != null})"
        }
        return token.accessToken
    }

    /**
     * Discovers MCP OAuth only from the resource server's `WWW-Authenticate` challenge. This avoids
     * guessing a well-known URL and permits path/host-specific metadata routing by a gateway.
     */
    fun discoverAuthorizationChallenge(
        resourceUrl: String,
        headers: Map<String, String>?,
    ): AuthorizationChallenge? {
        val resource = requireHttps(resourceUrl, "MCP resource URL")
        val request =
            HttpRequest
                .newBuilder(resource)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
        headers?.forEach { (name, value) -> request.header(name, value) }
        val response =
            httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_REQUEST)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        if (response.statusCode() != 401) return null
        val challenge =
            response.headers().allValues("WWW-Authenticate").firstOrNull { it.contains("Bearer", true) }
                ?: return null
        val metadataUrl =
            CHALLENGE_PARAMETER.find(challenge)?.groupValues?.get(1)
                ?: error("MCP resource $resource returned OAuth 401 without bearer resource_metadata")
        val scopes =
            SCOPE_PARAMETER
                .find(challenge)
                ?.groupValues
                ?.get(1)
                ?.split(' ')
                ?.filter { it.isNotBlank() } ?: emptyList()
        return AuthorizationChallenge(requireHttps(metadataUrl, "OAuth protected-resource metadata"), scopes)
    }

    private fun discoverProtectedResource(
        resource: URI,
        metadataUri: URI,
    ): ProtectedResourceMetadata {
        val body = getJson(metadataUri)
        val advertisedResource = body["resource"]?.toString()?.let { URI(it) }
        require(advertisedResource == null || advertisedResource == resource) {
            "OAuth protected-resource metadata at $metadataUri is for '$advertisedResource', not '$resource'"
        }
        val authorizationServer =
            (body["authorization_servers"] as? List<*>)?.firstOrNull() as? String
                ?: error("MCP resource did not advertise an OAuth authorization server at $metadataUri")
        val scopes = (body["scopes_supported"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return ProtectedResourceMetadata(requireHttps(authorizationServer, "OAuth authorization server"), scopes)
    }

    private fun discoverAuthorizationServer(authorizationServer: URI): AuthorizationServerMetadata {
        val metadataUri = authorizationServer.resolve(".well-known/oauth-authorization-server")
        val body = getJson(metadataUri)
        val issuer = requireHttps(body["issuer"] as? String ?: authorizationServer.toString(), "OAuth issuer")
        require(issuer.scheme == authorizationServer.scheme && issuer.host == authorizationServer.host) {
            "OAuth metadata issuer '$issuer' does not match authorization server '$authorizationServer'"
        }
        return AuthorizationServerMetadata(
            authorizationEndpoint =
                requireHttps(
                    body.requiredString("authorization_endpoint", metadataUri),
                    "OAuth authorization endpoint",
                ),
            tokenEndpoint = requireHttps(body.requiredString("token_endpoint", metadataUri), "OAuth token endpoint"),
            registrationEndpoint =
                body["registration_endpoint"]
                    ?.toString()
                    ?.let { requireHttps(it, "OAuth registration endpoint") },
        )
    }

    private fun registerClient(
        metadata: AuthorizationServerMetadata,
        resource: URI,
    ): String {
        val endpoint =
            metadata.registrationEndpoint
                ?: error("OAuth server does not provide a registration endpoint; configure a registered client before connecting")
        log.info("MCP OAuth: registering public client for resource '$resource'")
        val redirectUri = loopbackRedirectUri()
        val response =
            postJson(
                endpoint,
                mapOf(
                    "client_name" to "Quanta AI IntelliJ",
                    "redirect_uris" to listOf(redirectUri),
                    "grant_types" to listOf("authorization_code", "refresh_token"),
                    "response_types" to listOf("code"),
                    "token_endpoint_auth_method" to "none",
                ),
            )
        log.info("MCP OAuth: public client registration completed")
        return response.requiredString("client_id", endpoint)
    }

    private fun authorize(
        serverName: String,
        metadata: AuthorizationServerMetadata,
        scopes: List<String>,
        clientId: String,
        resource: URI,
    ): OAuthToken {
        val callback = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), CALLBACK_PORT), 0)
        try {
            val redirectUri = "http://127.0.0.1:${callback.address.port}/oauth/callback"
            val state = randomUrlSafe()
            val verifier = randomUrlSafe(64)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val future = java.util.concurrent.CompletableFuture<String>()
            callback.createContext("/oauth/callback") { exchange ->
                val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
                val code = query["code"]
                val error = query["error"]
                val valid = query["state"] == state && !code.isNullOrBlank() && error == null
                val message =
                    if (valid) {
                        "Authorization received for MCP server '$serverName'. Return to IntelliJ; it is completing the connection."
                    } else {
                        "Authorization for MCP server '$serverName' failed. Return to IntelliJ to see the error, then close this page."
                    }
                exchange.sendResponseHeaders(if (valid) 200 else 400, message.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(message.toByteArray()) }
                if (valid) {
                    log.info("MCP OAuth: received valid browser callback")
                    future.complete(code)
                } else {
                    val failure = error ?: "OAuth callback state validation failed"
                    log.warn("MCP OAuth: rejected browser callback: $failure")
                    future.completeExceptionally(IllegalStateException(failure))
                }
            }
            callback.start()
            val requestedScopes = scopes.joinToString(" ")
            val authorizationUri =
                metadata.authorizationEndpoint.withQuery(
                    mapOf(
                        "response_type" to "code",
                        "client_id" to clientId,
                        "redirect_uri" to redirectUri,
                        "code_challenge" to challenge,
                        "code_challenge_method" to "S256",
                        "state" to state,
                        "resource" to resource.toString(),
                        "scope" to requestedScopes,
                    ),
                )
            QDLog.info(log) { "MCP OAuth: opening browser login for '$clientId'; waiting up to 3 minutes for callback" }
            BrowserUtil.browse(authorizationUri)
            val code = future.get(3, TimeUnit.MINUTES)
            log.info("MCP OAuth: exchanging authorization code for token")
            return exchangeCode(metadata, clientId, code, verifier, redirectUri, resource)
        } finally {
            callback.stop(0)
        }
    }

    private fun refresh(
        metadata: AuthorizationServerMetadata,
        clientId: String,
        refreshToken: String,
        resource: URI,
    ): OAuthToken =
        postForm(
            metadata.tokenEndpoint,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "resource" to resource.toString(),
            ),
        ).toOAuthToken(metadata.tokenEndpoint, refreshToken)

    private fun exchangeCode(
        metadata: AuthorizationServerMetadata,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
        resource: URI,
    ): OAuthToken =
        postForm(
            metadata.tokenEndpoint,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri,
                "resource" to resource.toString(),
            ),
        ).toOAuthToken(metadata.tokenEndpoint, null)

    private fun getJson(uri: URI): Map<String, Any?> {
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(uri)
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        require(response.statusCode() in 200..299) { "OAuth discovery at $uri returned HTTP ${response.statusCode()}" }
        return mapper.readValue(response.body())
    }

    private fun postJson(
        uri: URI,
        body: Map<String, Any?>,
    ): Map<String, Any?> {
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        require(response.statusCode() in 200..299) {
            "OAuth registration at $uri returned HTTP ${response.statusCode()}: ${
                response.body().take(500)
            }"
        }
        return mapper.readValue(response.body())
    }

    private fun postForm(
        uri: URI,
        values: Map<String, String>,
    ): Map<String, Any?> {
        val form = values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(uri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        require(response.statusCode() in 200..299) {
            "OAuth token request returned HTTP ${response.statusCode()}: ${
                response.body().take(500)
            }"
        }
        return mapper.readValue(response.body())
    }

    private fun loadToken(
        serverName: String,
        resource: URI,
    ): OAuthToken? =
        PasswordSafe.instance
            .get(CredentialAttributes("Quanta AI MCP OAuth token", "$serverName|$resource"))
            ?.getPasswordAsString()
            ?.let { password -> runCatching { mapper.readValue<OAuthToken>(password) }.getOrNull() }
            ?.also { token ->
                QDLog.info(log) {
                    "MCP OAuth: loaded stored token for '$serverName' (${safeTokenDescription(token.accessToken)}, " +
                        "refreshTokenPresent=${token.refreshToken != null})"
                }
            }

    private fun saveToken(
        serverName: String,
        resource: URI,
        token: OAuthToken,
    ) {
        PasswordSafe.instance.set(
            CredentialAttributes("Quanta AI MCP OAuth token", "$serverName|$resource"),
            Credentials("oauth", mapper.writeValueAsString(token)),
        )
    }

    private fun loadClientId(
        serverName: String,
        resource: URI,
    ): String? =
        PasswordSafe.instance
            .get(CredentialAttributes("Quanta AI MCP OAuth client", "$serverName|$resource"))
            ?.getPasswordAsString()

    private fun saveClientId(
        serverName: String,
        resource: URI,
        clientId: String,
    ) {
        PasswordSafe.instance.set(
            CredentialAttributes("Quanta AI MCP OAuth client", "$serverName|$resource"),
            Credentials("oauth", clientId),
        )
    }

    private fun Map<String, Any?>.toOAuthToken(
        source: URI,
        previousRefreshToken: String?,
    ): OAuthToken {
        val expiresIn = (this["expires_in"] as? Number)?.toLong() ?: 300
        return OAuthToken(
            accessToken = requiredTokenString("access_token", source),
            refreshToken = this["refresh_token"]?.toString() ?: previousRefreshToken,
            expiresAt = Instant.now().epochSecond + expiresIn,
        )
    }

    private fun Map<String, Any?>.requiredTokenString(
        name: String,
        source: URI,
    ): String =
        this[name]?.toString()?.takeIf { it.isNotBlank() }
            ?: error(
                "OAuth token response from $source does not contain '$name'" +
                    oauthErrorDescription(),
            )

    private fun Map<String, Any?>.oauthErrorDescription(): String {
        val error = this["error"]?.toString()?.takeIf { it.isNotBlank() } ?: return ""
        val description = this["error_description"]?.toString()?.takeIf { it.isNotBlank() }
        return if (description == null) "; error=$error" else "; error=$error, description=$description"
    }

    private fun Map<String, Any?>.requiredString(
        name: String,
        source: URI,
    ): String =
        this[name]?.toString()?.takeIf { it.isNotBlank() }
            ?: error("OAuth metadata at $source does not contain '$name'")

    private fun requireHttps(
        value: String,
        description: String,
    ): URI = URI(value).also { require(it.scheme == "https" && !it.host.isNullOrBlank()) { "$description must be an absolute HTTPS URL" } }

    private fun URI.withQuery(values: Map<String, String>): URI =
        URI("$scheme://$authority$path?${values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }}")

    private fun parseQuery(query: String): Map<String, String> =
        query
            .split('&')
            .mapNotNull { item ->
                item.split('=', limit = 2).takeIf { it.size == 2 }?.let {
                    java.net.URLDecoder.decode(it[0], Charsets.UTF_8) to
                        java.net.URLDecoder.decode(
                            it[1],
                            Charsets.UTF_8,
                        )
                }
            }.toMap()

    private fun loopbackRedirectUri(): String = "http://127.0.0.1:$CALLBACK_PORT/oauth/callback"

    private fun safeTokenDescription(token: String): String =
        "accessTokenLength=${token.length}, accessTokenSha256Prefix=${oauthTokenFingerprint(token)}"

    private fun randomUrlSafe(length: Int = 32): String = base64Url(ByteArray(length).also(SecureRandom()::nextBytes))

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val CALLBACK_PORT = 53145
        const val AUTHORIZATION_RETRY_COOLDOWN_SECONDS = 300L
        const val INITIALIZE_REQUEST =
            "{\"jsonrpc\":\"2.0\",\"id\":\"oauth-discovery\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"Quanta AI IntelliJ\",\"version\":\"1.0\"}}}"
        val CHALLENGE_PARAMETER = Regex("(?:^|[,\\s])resource_metadata=\\\"([^\\\"]+)\\\"")
        val SCOPE_PARAMETER = Regex("(?:^|[,\\s])scope=\\\"([^\\\"]*)\\\"")
    }

    private data class RecentFailure(
        val occurredAt: Instant,
        val message: String,
    )

    internal data class AuthorizationChallenge(
        val metadataUri: URI,
        val scopes: List<String>,
    )

    private data class ProtectedResourceMetadata(
        val authorizationServer: URI,
        val scopes: List<String>,
    )

    private data class AuthorizationServerMetadata(
        val authorizationEndpoint: URI,
        val tokenEndpoint: URI,
        val registrationEndpoint: URI?,
    )

    private data class OAuthToken(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAt: Long,
    )
}

/** Returns a non-reversible, short diagnostic fingerprint; never log the token itself. */
internal fun oauthTokenFingerprint(token: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(12)
