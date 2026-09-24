package org.olcbox.app

import android.Manifest
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
