# Instrumentation is compiled separately and calls APIs that a release app can inline away.
# Retain runtime/test-observation boundaries only in the isolated QA variant.
# Activity, tile, layout, resources, and VPN engines retain normal release optimization.
-keep,allowoptimization class androidx.tracing.** { *; }
-keep,allowoptimization class kotlin.** { *; }
-keep,allowoptimization class kotlinx.coroutines.** { *; }
-keep,allowoptimization class org.olcbox.app.data.model.** { *; }
-keep,allowoptimization class org.olcbox.app.data.datasource.AndroidLocationsRepository** { *; }
-keep,allowoptimization class org.olcbox.app.vpn.AndroidVpnManager** { *; }
-keep,allowoptimization class org.olcbox.app.vpn.VpnStatus** { *; }
-keep,allowoptimization class org.olcbox.app.vpn.service.OlcboxVpnState** { *; }
-keep,allowoptimization interface org.olcbox.app.data.repository.LocationsRepository { *; }
-keep,allowoptimization class org.olcbox.app.data.repository.LocationsRepository$* { *; }
