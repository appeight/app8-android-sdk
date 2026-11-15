package dev.app8.engine

/**
 * An HTTP request made by the DSL `request` action, as handed to
 * [App8DataSource.prepareRequest] before it is sent. [body] is JSON text (sent as
 * `application/json` unless [headers] set a `Content-Type`); null sends no body.
 */
public data class App8HttpRequest(
    /** `GET`, `POST`, `PUT`, `PATCH` or `DELETE`. */
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)
