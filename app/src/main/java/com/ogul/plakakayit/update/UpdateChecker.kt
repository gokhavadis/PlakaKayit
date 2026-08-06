package com.ogul.plakakayit.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker {

    fun checkLatestRelease(): ReleaseInfo {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "PlakaKayit-Android")

        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    val payload = JSONObject(json)
                    ReleaseInfo(
                        tag = payload.optString("tag_name").ifBlank { "Bilinmiyor" },
                        title = payload.optString("name").ifBlank { "Yeni sürüm" },
                        notes = payload.optString("body").ifBlank { "Sürüm notu eklenmemiş." },
                        pageUrl = payload.optString("html_url").ifBlank { RELEASES_PAGE }
                    )
                }

                HttpURLConnection.HTTP_NOT_FOUND -> ReleaseInfo(
                    tag = null,
                    title = "Henüz yayınlanmış sürüm yok",
                    notes = "GitHub Releases bölümünde bir sürüm yayınlandığında burada görünecek.",
                    pageUrl = RELEASES_PAGE
                )

                else -> error("GitHub yanıtı: ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    data class ReleaseInfo(
        val tag: String?,
        val title: String,
        val notes: String,
        val pageUrl: String
    )

    companion object {
        const val RELEASES_PAGE = "https://github.com/gokhavadis/PlakaKayit/releases"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/gokhavadis/PlakaKayit/releases/latest"
    }
}
