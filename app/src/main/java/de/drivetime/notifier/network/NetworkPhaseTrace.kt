package de.drivetime.notifier.network

import okhttp3.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/** Fixed phase names only. Never retain hosts, IP addresses, URLs or credentials. */
internal class NetworkPhaseTrace : EventListener() {
    @Volatile var phase = "queued"
        private set
    override fun dnsStart(call:Call,domainName:String) { phase="dns" }
    override fun dnsEnd(call:Call,domainName:String,inetAddressList:List<InetAddress>) { phase="connect" }
    override fun connectStart(call:Call,inetSocketAddress:InetSocketAddress,proxy:Proxy) { phase="connect" }
    override fun secureConnectStart(call:Call) { phase="tls" }
    override fun secureConnectEnd(call:Call,handshake:Handshake?) { phase="request" }
    override fun requestHeadersStart(call:Call) { phase="request headers" }
    override fun requestBodyStart(call:Call) { phase="request body" }
    override fun responseHeadersStart(call:Call) { phase="response headers" }
    override fun responseBodyStart(call:Call) { phase="response body" }
    override fun responseBodyEnd(call:Call,byteCount:Long) { phase="completed" }
    companion object {
        val factory = EventListener.Factory { call -> call.request().tag(NetworkPhaseTrace::class.java) ?: EventListener.NONE }
    }
}
