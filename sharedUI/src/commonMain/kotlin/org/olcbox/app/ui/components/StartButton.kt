package org.olcbox.app.ui.components
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.localization.AppText as Text

sealed class StartButtonState { object Idle:StartButtonState();object Loading:StartButtonState();object Success:StartButtonState() }
@Composable fun StartButton(modifier:Modifier=Modifier,isActive:Boolean,isLoading:Boolean,requiresSetup:Boolean=false,label:String?=null,enabled:Boolean=true,onClick:()->Unit){
 Button(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=18.dp,vertical=16.dp)){
  if(isLoading) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=MaterialTheme.colorScheme.onPrimary)
  else Icon(if(requiresSetup)Icons.Rounded.Add else Icons.Rounded.PowerSettingsNew,contentDescription=null,modifier=Modifier.size(20.dp))
  Spacer(Modifier.width(10.dp))
  Text(label?:when{isLoading->"Cancel connection";isActive->"Disconnect";requiresSetup->"Add profile";else->"Connect"},style=MaterialTheme.typography.labelLarge)
 }
}
