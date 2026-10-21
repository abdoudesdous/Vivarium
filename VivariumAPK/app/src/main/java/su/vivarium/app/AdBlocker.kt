package su.vivarium.app

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {
    private val AD_HOSTS = HashSet<String>()

    fun init(context: Context) {
        Thread {
            try {
                context.assets.open("ad_hosts.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val clean = line.trim()
                        if (clean.isNotEmpty() && !clean.startsWith("#")) {
                            AD_HOSTS.add(clean)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun isAd(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        if (host.endsWith("vivarium.su")) return false
        return isAdHost(host)
    }

    private tailrec fun isAdHost(host: String): Boolean {
        if (AD_HOSTS.contains(host)) return true
        val index = host.indexOf('.')
        if (index <= 0) return false
        return isAdHost(host.substring(index + 1))
    }

    fun createEmptyResource(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
