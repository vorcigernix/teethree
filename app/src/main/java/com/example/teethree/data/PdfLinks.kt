package com.example.teethree.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun resolvePreviewPdfLink(href: String, sourceUrl: String? = null): String? {
    if (href.isBlank()) return null
    val url = href.toHttpUrlOrNull() ?: sourceUrl?.toHttpUrlOrNull()?.resolve(href) ?: return null
    return url.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
}
