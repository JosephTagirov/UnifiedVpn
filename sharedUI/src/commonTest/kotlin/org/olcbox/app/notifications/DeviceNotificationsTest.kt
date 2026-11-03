package org.olcbox.app.notifications

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import org.olcbox.app.data.datasource.LocationsDataSource
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.model.*

class DeviceNotificationsTest {
    private val source="https://panel.meesharr.space/vpn-control/bundle/"+"a".repeat(43)
    private val messageId="b".repeat(32)
    private class Store:NotificationDeliveryStore {
        var state=NotificationDeliveryState("c".repeat(32))
        override fun load()=state
        override fun save(value:NotificationDeliveryState){state=value}
    }
    private class Presenter(var allowed:Boolean=true):DeviceMessagePresenter {
        val shown=mutableListOf<DeviceMessage>()
        override fun permitted()=allowed
        override suspend fun show(message:DeviceMessage):Boolean {shown+=message;return true}
    }
    private fun repository()=LocationsRepositoryImpl(object:LocationsDataSource {
        override suspend fun loadLocationBundle()=LocationBundleV4(locations=listOf(
            LocationEntry.from("one",LocationConfig(name="A",id="room",key="a".repeat(64)),source),
            LocationEntry.from("two",LocationConfig(name="B",id="room2",key="a".repeat(64)),source)))
        override suspend fun saveLocationBundle(bundle:LocationBundleV4){}
        override suspend fun loadLegacyLocations()=emptyList<Pair<String,String>>()
        override suspend fun loadLegacyActiveLocationId():String?=null
    })
    private fun feed()=buildJsonObject { putJsonArray("messages"){add(buildJsonObject {put("id",messageId);put("title","Для тебя ❤️");put("body","Хорошего дня 💜💜💜")})} }.toString()
    @Test fun onlyTrustedSubscriptionUrlsCanIdentifyTheRecipient(){
        assertEquals("a".repeat(43),notificationCapability(source))
        assertNull(notificationCapability(source+"?redirect=1"))
        assertNull(notificationCapability(source.replace("panel.meesharr.space","evil.test")))
        assertNull(notificationCapability(source.replace("https:","http:")))
        assertNull(notificationCapability(source.replace("/bundle/","/admin/")))
    }
    @Test fun twoProfilesFromOneSubscriptionProduceOneNotificationAndReceipt()=runTest {
        val requests=mutableListOf<JsonObject>();val store=Store();val presenter=Presenter()
        val client=DeviceNotifications(repository(),store,presenter) {HttpClient(MockEngine { request ->
            requests+=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(feed(),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })}
        assertEquals(1,client.pollOnce());assertEquals(1,presenter.shown.size)
        assertEquals("Хорошего дня 💜💜💜",presenter.shown.single().body)
        assertEquals(2,requests.size)
        assertEquals(messageId,requests.last()["received"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(0,client.pollOnce());assertEquals(1,presenter.shown.size)
    }
    @Test fun deniedNotificationsStayPendingAndAreNotAcknowledged()=runTest {
        val store=Store();val presenter=Presenter(false);var count=0
        val client=DeviceNotifications(repository(),store,presenter) {HttpClient(MockEngine {count++;respond(feed(),HttpStatusCode.OK)})}
        assertEquals(0,client.pollOnce());assertTrue(store.state.received.isEmpty());assertEquals(1,count)
        presenter.allowed=true;assertEquals(1,client.pollOnce())
    }
    @Test fun persistedDeduplicationSurvivesApplicationRestart()=runTest {
        val store=Store();val presenter=Presenter()
        fun client()=DeviceNotifications(repository(),store,presenter) {HttpClient(MockEngine {respond(feed(),HttpStatusCode.OK)})}
        assertEquals(1,client().pollOnce());assertEquals(0,client().pollOnce());assertEquals(1,presenter.shown.size)
    }
    @Test fun networkFailureCanBeRetriedWithoutLosingMessage()=runTest {
        val store=Store();val presenter=Presenter();var failed=true
        val client=DeviceNotifications(repository(),store,presenter) {HttpClient(MockEngine {respond(if(failed)"{}" else feed(),if(failed)HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)})}
        assertEquals(0,client.pollOnce());assertTrue(store.state.received.isEmpty())
        failed=false;assertEquals(1,client.pollOnce())
    }
}
