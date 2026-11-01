package org.olcbox.app.ui.features.home.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.localization.AppText as Text

@Composable fun RelayStatus(isActive:Boolean,isLoading:Boolean=false,requiresSetup:Boolean=false,modifier:Modifier=Modifier,profileCount:Int=0){
 Surface(modifier=modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.primaryContainer){
  Row(Modifier.padding(horizontal=20.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   Icon(if(isActive&&!isLoading)Icons.Rounded.VerifiedUser else Icons.Outlined.Shield,null,tint=MaterialTheme.colorScheme.onPrimaryContainer,modifier=Modifier.size(22.dp))
   Column(Modifier.weight(1f)){
    Text(when{isLoading->"Connecting...";isActive->"Connected";requiresSetup->"Add your first profile";else->"Ready to connect"},style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
    Text(when{isLoading->"The channel is starting";isActive->"Your connection is protected";requiresSetup->"Use a link, QR or file";else->"Choose a profile below"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=.76f))
   }
   if(profileCount>0)Text(profileCount.toString().padStart(2,'0'),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onPrimaryContainer)
  }
 }
}
