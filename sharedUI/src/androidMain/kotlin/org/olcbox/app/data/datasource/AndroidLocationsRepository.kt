package org.olcbox.app.data.datasource

import android.content.Context
import org.olcbox.app.data.repository.LocationsRepository

/** One mutation lock and change stream for the UI, notification chooser and VPN service. */
object AndroidLocationsRepository {
    @Volatile
    private var instance: LocationsRepository? = null

    fun get(context: Context): LocationsRepository = instance ?: synchronized(this) {
        instance ?: LocationsRepositoryImpl(
            LocationsDataSourceImpl(context.applicationContext)
        ).also { instance = it }
    }
}
