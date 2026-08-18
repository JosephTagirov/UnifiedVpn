package org.olcbox.app.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

interface LocationsRepository {
    val changes: StateFlow<Long>
    suspend fun getBundle(): LocationBundleV4
    suspend fun saveBundle(bundle: LocationBundleV4)
    suspend fun exportBundle(): String
    suspend fun importTextDetailed(
        text: String,
        subscriptionProxy: SubscriptionFetchProxy? = null,
        allowInsecureRequests: Boolean = false
    ): LocationImportResult
    suspend fun importText(
        text: String,
        subscriptionProxy: SubscriptionFetchProxy? = null,
        allowInsecureRequests: Boolean = false
    ): Boolean {
        return importTextDetailed(
            text = text,
            subscriptionProxy = subscriptionProxy,
            allowInsecureRequests = allowInsecureRequests
        ) is LocationImportResult.Success
    }
    suspend fun refreshSubscriptions(subscriptionProxy: SubscriptionFetchProxy? = null): Int
    suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): Int
    suspend fun refreshDueSubscriptions(subscriptionProxy: SubscriptionFetchProxy? = null): Int
    suspend fun nextSubscriptionRefreshAtEpochMs(): Long?
    suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, intervalMs: Long?)
    suspend fun deleteSubscription(subscriptionUrl: String): Int
    suspend fun saveLocation(storageId: String, location: LocationConfig)
    suspend fun loadLocation(storageId: String): LocationConfig?
    suspend fun deleteLocation(storageId: String)
    suspend fun getAllLocations(): List<LocationEntry>
    suspend fun getActiveLocationId(): String?
    suspend fun setActiveLocationId(storageId: String?)
    suspend fun getActiveLocation(): LocationEntry?
    suspend fun getDeviceIdentity(): String
}

sealed interface LocationImportResult {
    data class Success(
        val importedLocations: Int,
        val subscriptionUrl: String? = null
    ) : LocationImportResult

    data class Failure(
        val kind: LocationImportFailureKind,
        val message: String
    ) : LocationImportResult
}

enum class LocationImportFailureKind {
    EmptyInput,
    InvalidUrl,
    Network,
    Timeout,
    Tls,
    Http,
    EmptyResponse,
    UnsupportedFormat
}

data class SubscriptionFetchProxy(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)
