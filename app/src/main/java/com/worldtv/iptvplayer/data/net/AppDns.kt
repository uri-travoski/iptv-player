package com.worldtv.iptvplayer.data.net

import android.os.SystemClock
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Name lookups for everything WorldTV loads (playlists, streams, logos). By default the box's
 * own DNS; optionally encrypted DNS-over-HTTPS to Cloudflare (1.1.1.1) or Google (8.8.8.8),
 * which gets around DNS-level blocking by an ISP or local filter. It doesn't change the route
 * the video takes, only who answers "what is this server's address".
 *
 * [mode] is read on every lookup, so a change in App Settings applies to the next connection.
 */
class AppDns(private val mode: () -> Int) : Dns {

    // DoH needs its own plain client (it can't resolve its own server through itself).
    private val bootstrap = OkHttpClient.Builder().build()

    private val cloudflare by lazy {
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()
    }

    private val google by lazy {
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
            .build()
    }

    /** "mode|host" -> (addresses, expiry). DoH answers are cached a few minutes so zapping stays quick. */
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val m = mode()
        val resolver = when (m) {
            MODE_CLOUDFLARE -> cloudflare
            MODE_GOOGLE -> google
            else -> return Dns.SYSTEM.lookup(hostname)
        }
        val key = "$m|$hostname"
        val now = SystemClock.elapsedRealtime()
        cache[key]?.let { (addresses, until) -> if (now < until) return addresses }
        val addresses = try {
            resolver.lookup(hostname)
        } catch (e: UnknownHostException) {
            // The encrypted DNS service unreachable (or the name really doesn't exist):
            // fall back to the box's DNS rather than fail outright.
            Dns.SYSTEM.lookup(hostname)
        }
        cache[key] = addresses to now + CACHE_MS
        return addresses
    }

    companion object {
        const val MODE_SYSTEM = 0
        const val MODE_CLOUDFLARE = 1
        const val MODE_GOOGLE = 2
        private const val CACHE_MS = 5 * 60_000L
    }
}
