package org.olcbox.app.ui.features.locations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.olcbox.app.data.model.LocationMetadata
import org.olcbox.app.data.model.SubscriptionMetadata

class LocationReorderGroupTest {
    @Test
    fun customProfilesShareOneReorderGroup() {
        assertEquals(
            LocationItem(storageId = "first", fullName = "First").reorderGroupKey(),
            LocationItem(storageId = "second", fullName = "Second").reorderGroupKey()
        )
    }

    @Test
    fun subscriptionGroupUsesTrimmedNameAndUrl() {
        val first = subscriptionLocation("first", " Main ", " https://example.test/list ")
        val second = subscriptionLocation("second", "Main", "https://example.test/list")

        assertEquals(first.reorderGroupKey(), second.reorderGroupKey())
    }

    @Test
    fun subscriptionsWithSameUrlButDifferentVisibleNamesStaySeparate() {
        val first = subscriptionLocation("first", "Primary", "https://example.test/list")
        val second = subscriptionLocation("second", "Backup", "https://example.test/list")

        assertNotEquals(first.reorderGroupKey(), second.reorderGroupKey())
    }

    private fun subscriptionLocation(id: String, name: String, url: String) = LocationItem(
        storageId = id,
        fullName = id,
        subscriptionUrl = url,
        metadata = LocationMetadata(subscription = SubscriptionMetadata(name = name))
    )
}
