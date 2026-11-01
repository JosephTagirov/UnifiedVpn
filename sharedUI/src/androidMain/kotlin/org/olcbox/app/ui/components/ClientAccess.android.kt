package org.olcbox.app.ui.components
actual fun ownerCabinetEnabled(): Boolean = System.getProperty("unifiedvpn.ownerCabinet") == "true"
