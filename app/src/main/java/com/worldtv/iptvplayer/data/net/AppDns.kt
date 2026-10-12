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
 * Name lookups for everything WorldTV loads (playlists, streams, logos). By default encrypted
 * DNS-over-HTTPS to Cloudflare (1.1.1.1), then Google (8.8.8.8) if Cloudflare can't be reached;
 * or just one of them, or the box's own DNS. Encrypted DNS gets around DNS-level blocking by an
 * ISP or local filter. It doesn't change the route
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
        val resolvers = when (m) {
            MODE_BOTH -> listOf(cloudflare, google)
            MODE_CLOUDFLARE -> listOf(cloudflare)
            MODE_GOOGLE -> listOf(google)
            else -> return Dns.SYSTEM.lookup(hostname)
        }
        val key = "$m|$hostname"
        val now = SystemClock.elapsedRealtime()
        cache[key]?.let { (addresses, until) -> if (now < until) return addresses }
        // The encrypted DNS services unreachable (or the name really doesn't exist): the box's
        // DNS rather than fail outright.
        val addresses = resolvers.firstNotNullOfOrNull { r ->
            try {
                r.lookup(hostname)
            } catch (e: UnknownHostException) {
                null
            }
        } ?: Dns.SYSTEM.lookup(hostname)
        cache[key] = addresses to now + CACHE_MS
        return addresses
    }

    companion object {
        const val MODE_SYSTEM = 0
        const val MODE_CLOUDFLARE = 1
        const val MODE_GOOGLE = 2
        /** Cloudflare, then Google: the default. */
        const val MODE_BOTH = 3
        private const val CACHE_MS = 5 * 60_000L
    }
}
