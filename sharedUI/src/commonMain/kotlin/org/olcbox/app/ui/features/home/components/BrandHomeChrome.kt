package org.olcbox.app.ui.features.home.components
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import org.olcbox.app.ui.localization.AppText as Text

@Composable fun BrandHomeIntro(){
 Column(Modifier.fillMaxWidth().padding(top=10.dp,bottom=24.dp)){
  Text("YOUR PERSONAL NETWORK",style=MaterialTheme.typography.labelSmall,letterSpacing=1.8.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(12.dp))
  BoxWithConstraints{
   val fontSize=if(maxWidth<340.dp)34.sp else 42.sp
   Column{
    Text("Your network.",fontSize=fontSize,lineHeight=fontSize*1.06f,letterSpacing=(-1.6).sp,fontWeight=FontWeight.Medium)
    Text("Under control.",fontSize=fontSize,lineHeight=fontSize*1.06f,letterSpacing=(-1.6).sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.tertiary)
   }
  }
 }
}
@Composable fun BrandHomeNavigation(showCabinet:Boolean,owner:Boolean,onLogs:()->Unit,onCabinet:()->Unit){
 Column(Modifier.fillMaxWidth().padding(top=18.dp,bottom=18.dp)){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
   TextButton(onClick={},modifier=Modifier.weight(1f)){Text("Profiles",fontWeight=FontWeight.SemiBold)}
   TextButton(onClick=onLogs,modifier=Modifier.weight(1f)){Text("Logs",color=MaterialTheme.colorScheme.onSurfaceVariant)}
   if(showCabinet) TextButton(onClick=onCabinet,modifier=Modifier.weight(1f)){Text(if(owner) "Управление" else "Мой доступ",color=MaterialTheme.colorScheme.onSurfaceVariant)}
  }
  HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
 }
}
