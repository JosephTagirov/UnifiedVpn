package org.olcbox.app.data.logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.createProxyHttpClient

object SupportLogs {
    val target=MutableStateFlow<String?>(null)
    fun selectTarget(url:String?) {
        target.value=url?.let { Regex("https://panel\\.meesharr\\.space/vpn-control/(?:bundle|unified|calls|sub)/([A-Za-z0-9_-]{43})").matchEntire(it)?.groupValues?.get(1) }
    }
    internal fun redact(value:String):String = value
        .replace(Regex("(?i)(?:vless|vmess|vpn|awg|openflux|olcrtc)://\\S+"),"[конфиг скрыт]")
        .replace(Regex("(?i)(/vpn-control/(?:bundle|add|client|sub|unified|calls|existing)/)[A-Za-z0-9_-]{43}"),"$1[ссылка скрыта]")
        .replace(Regex("(?im)^.*(?:authorization|password|private[_ -]?key|pre[_ -]?shared[_ -]?key|encryption[_ -]?key|token|secret)\\s*[\"\\s]*[:=].*$"),"[секретные данные скрыты]")
        .replace(Regex("(?i)(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])"),"[ключ скрыт]")
        .replace(Regex("(?i)(?<![a-f0-9])[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}(?![a-f0-9])"),"[id скрыт]")
        .replace(Regex("(?i)(https?://[^\\s/]+/(?:call/join/|j/))[^\\s]+"),"$1[комната скрыта]")
    suspend fun ready():Result<Boolean> = runCatching {
        val client=createProxyHttpClient(connectTimeoutMs=3000,requestTimeoutMs=5000,socketTimeoutMs=5000)
        try { Json.parseToJsonElement(client.get("https://panel.meesharr.space/vpn-control/support/status").bodyAsText()).jsonObject["ready"]?.jsonPrimitive?.boolean==true }
        finally { client.close() }
    }.onFailure { if(it is kotlinx.coroutines.CancellationException)throw it }
    suspend fun send(logs:List<String>):Result<Int> = runCatching {
        val capability=target.value ?: error("Добавь или обнови набор конфигов из своего кабинета")
        val text=redact(LogPresentation.export(logs).lineSequence().toList().takeLast(1000).joinToString("\n")).takeLast(60000)
        require(text.isNotBlank()) { "Нет выбранных строк" }
        val client=createProxyHttpClient(connectTimeoutMs=5000,requestTimeoutMs=25000,socketTimeoutMs=25000)
        try {
            val response=client.post("https://panel.meesharr.space/vpn-control/support/logs") {
                header("Content-Type","application/json")
                setBody(buildJsonObject { put("capability",capability);put("text",text);put("version",CurrentAppInfo.value.version) }.toString())
            }
            val data=Json.parseToJsonElement(response.bodyAsText()).jsonObject
            require(response.status.value in 200..299) { data["error"]?.jsonPrimitive?.content ?: "Не удалось отправить лог" }
            data["lines"]?.jsonPrimitive?.int ?: text.lines().size
        } finally { client.close() }
    }.onFailure { if(it is kotlinx.coroutines.CancellationException)throw it }
}
