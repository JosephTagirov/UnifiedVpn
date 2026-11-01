package org.olcbox.app.ui.features.home.components
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.localization.AppText as Text
import org.olcbox.app.ui.components.UnifiedBrandMark

@Composable fun HomeScreenAppBar(protocolSummary:String="",onHistoryClick:()->Unit={},showAppSettingsButton:Boolean=false,onAppSettingsClick:()->Unit={},showSplitTunnelingButton:Boolean=false,onSplitTunnelingClick:()->Unit={},onAddClick:()->Unit={}){
 Box(Modifier.fillMaxWidth().statusBarsPadding(),contentAlignment=Alignment.Center){
  Surface(modifier=Modifier.widthIn(max=780.dp).fillMaxWidth(),color=MaterialTheme.colorScheme.background,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
   Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
    UnifiedBrandMark(Modifier.size(26.dp));Spacer(Modifier.width(9.dp))
    Text("UNIFIEDVPN",fontSize=13.sp,letterSpacing=1.3.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f),maxLines=1)
    if(showSplitTunnelingButton)IconButton(onClick=onSplitTunnelingClick,modifier=Modifier.size(40.dp)){Icon(Icons.Outlined.Shield,"Split tunneling",Modifier.size(20.dp))}
    IconButton(onClick=if(showAppSettingsButton)onAppSettingsClick else onHistoryClick,modifier=Modifier.size(40.dp)){Icon(if(showAppSettingsButton)Icons.Outlined.Settings else Icons.Outlined.History,if(showAppSettingsButton)"Application settings" else "History",Modifier.size(20.dp))}
    IconButton(onClick=onAddClick,modifier=Modifier.size(40.dp)){Icon(Icons.Outlined.Add,"Add configuration",Modifier.size(22.dp))}
   }
  }
 }
}
