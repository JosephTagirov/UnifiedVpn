package org.olcbox.app

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import org.olcbox.app.data.share.SelectionImportLink
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.notifications.AndroidDeviceNotifications
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.data.exporter.AndroidLogExporter
import org.olcbox.app.data.importer.AndroidConfigImporter
import org.olcbox.app.ui.activities.AndroidMainScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.settings.AndroidAppearanceSettingsStore
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.ui.theme.SyncAppSystemBars
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {
    private var importLink by mutableStateOf<String?>(null)
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent);setIntent(intent)
        SelectionImportLink.parse(intent.dataString)?.let { importLink=it }
    }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ownerPreferences=getSharedPreferences("owner-edition",Context.MODE_PRIVATE)
        if(BuildConfig.OWNER_EDITION)ownerPreferences.edit().putBoolean("enabled",true).apply()
        System.setProperty("unifiedvpn.ownerCabinet",ownerPreferences.getBoolean("enabled",false).toString())
        importLink=SelectionImportLink.parse(intent?.dataString)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val vpnUi = ViewModelProvider(this, viewModelFactory {
            initializer { AndroidVpnUiViewModel(applicationContext) }
        })[AndroidVpnUiViewModel::class.java]
        val vpnManager = vpnUi.vpnManager
        val locationsRepository = AndroidLocationsRepository.get(applicationContext)
        AndroidDeviceNotifications.schedule(applicationContext)
        val configImporter = AndroidConfigImporter(applicationContext)
        val logExporter = AndroidLogExporter(applicationContext)
        val updateService = AppUpdateService()
        val appearanceSettingsStore = AndroidAppearanceSettingsStore(this)
        val initialAppearanceSettings = appearanceSettingsStore.loadNow()

        val models = ViewModelProvider(this, viewModelFactory {
            initializer {
                HomeScreenViewModel(
                    vpnManager = vpnManager,
                    locationsRepository = locationsRepository,
                    configImporter = configImporter,
                    logExporter = logExporter
                )
            }
            initializer { LocationViewModel(locationsRepository) }
        })
        val viewModel = models[HomeScreenViewModel::class.java]
        val locationViewModel = models[LocationViewModel::class.java]

        setContent {
            var appearanceSettings by remember { mutableStateOf(initialAppearanceSettings) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                while(true) {
                    withContext(Dispatchers.IO) { runCatching { AndroidDeviceNotifications.get(applicationContext).pollOnce(vpnManager.subscriptionFetchProxy()) } }
                    delay(60_000)
                }
            }
            LaunchedEffect(importLink) {
                val source=importLink ?: return@LaunchedEffect
                importLink=null
                viewModel.onImportFullConfig(source,onComplete={
                    locationViewModel.loadLocations { }
                    Toast.makeText(this@AppActivity,"Профили добавлены",Toast.LENGTH_LONG).show()
                },onError={Toast.makeText(this@AppActivity,it,Toast.LENGTH_LONG).show()})
            }
            SyncAppSystemBars(appearanceSettings.theme)

            AppTheme(
                useDynamicColor = false,
                themeMode = appearanceSettings.theme,
                language = appearanceSettings.language
            ) {
                AndroidMainScreen(
                    viewModel = viewModel,
                    locationViewModel = locationViewModel,
                    vpnManager = vpnManager,
                    appUpdateService = updateService,
                    appearanceSettings = appearanceSettings,
                    onAppearanceSettingsChanged = { settings ->
                        appearanceSettings = settings
                        scope.launch {
                            runCatching { appearanceSettingsStore.save(settings) }
                        }
                    }
                )
            }
        }
    }
}

private class AndroidVpnUiViewModel(context: Context) : ViewModel() {
    val vpnManager = AndroidVpnManager(context.applicationContext)

    override fun onCleared() {
        vpnManager.closeUi()
    }
}
