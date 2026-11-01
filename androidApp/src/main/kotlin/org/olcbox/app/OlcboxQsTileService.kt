package org.olcbox.app

import android.os.Build
import androidx.annotation.RequiresApi

/** Retained source-compatible name; both entry points use the same quick controls. */
@RequiresApi(Build.VERSION_CODES.N)
class OlcboxQsTileService : QuickSettingsTileService()
