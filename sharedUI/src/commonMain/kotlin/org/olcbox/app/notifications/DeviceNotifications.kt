package org.olcbox.app.notifications

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.createProxyHttpClient
import org.olcbox.app.data.datasource.withProxyAuthentication
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy

@Serializable
data class NotificationDeliveryState(val installation:String, val received:List<String> = emptyList())
data class DeviceMessage(val id:String,val title:String,val body:String)
interface NotificationDeliveryStore {
    fun load():NotificationDeliveryState
    fun save(value:NotificationDeliveryState)
}
interface DeviceMessagePresenter {
    fun permitted():Boolean
    suspend fun show(message:DeviceMessage):Boolean
}

internal fun notificationCapability(source:String?):String? = source?.let {
    Regex("https://panel\\.meesharr\\.space/vpn-control/(?:bundle|unified|calls|sub)/([A-Za-z0-9_-]{43})").matchEntire(it.trim())?.groupValues?.get(1)
}

class DeviceNotifications(
    private val repository:LocationsRepository,
    private val store:NotificationDeliveryStore,
    private val presenter:DeviceMessagePresenter,
    private val clientFactory:(SubscriptionFetchProxy?)->HttpClient = { createProxyHttpClient(it,5000,12000,12000) },
) {
    private val mutex=Mutex()
    suspend fun pollOnce(proxy:SubscriptionFetchProxy?=null):Int = mutex.withLock {
        val capabilities=repository.getAllLocations().mapNotNull { notificationCapability(it.subscriptionUrl) }.distinct()
        if(capabilities.isEmpty())return@withLock 0
        var displayed=0
        var state=store.load()
        val client=clientFactory(proxy)
        try {
            withProxyAuthentication(proxy) {
                for(capability in capabilities) {
                    try {
                        suspend fun request():JsonObject? {
                            val response=client.post("https://panel.meesharr.space/vpn-control/notifications/poll") {
                                header("Content-Type","application/json")
                                setBody(buildJsonObject {
                                    put("capability",capability);put("installation",state.installation)
                                    put("version",CurrentAppInfo.value.version);put("permission",presenter.permitted())
                                    putJsonArray("received") { state.received.takeLast(100).forEach { add(it) } }
                                }.toString())
                            }
                            if(response.status.value!=200)return null
                            val text=response.bodyAsText()
                            if(text.length>1_000_000)return null
                            return Json.parseToJsonElement(text).jsonObject
                        }
                        val messages=request()?.get("messages")?.jsonArray?.take(100) ?: continue
                        var changed=false
                        for(item in messages) {
                            val value=item.jsonObject
                            val id=value["id"]?.jsonPrimitive?.content ?: continue
                            val title=value["title"]?.jsonPrimitive?.content ?: continue
                            val body=value["body"]?.jsonPrimitive?.content ?: continue
                            if(!Regex("[a-f0-9]{32}").matches(id) || title.length !in 1..200 || body.length !in 1..4000)continue
                            if(id in state.received || !presenter.permitted())continue
                            if(presenter.show(DeviceMessage(id,title,body))) {
                                state=state.copy(received=(state.received+id).distinct().takeLast(1000))
                                store.save(state);displayed++;changed=true
                            }
                        }
                        if(changed)request() // Receipt follows successful system notification and durable local deduplication.
                    } catch(e:CancellationException){throw e} catch(_:Exception){ /* Retry after connectivity returns. */ }
                }
            }
        } finally {client.close()}
        displayed
    }
}
