package org.olcbox.app.ui.components
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable fun UnifiedBrandMark(modifier:Modifier=Modifier){
 val bg=MaterialTheme.colorScheme.primary;val fg=MaterialTheme.colorScheme.onPrimary
 Canvas(modifier){
  val u=size.minDimension/24f
  drawRoundRect(bg,cornerRadius=CornerRadius(5*u,5*u))
  val shield=Path().apply{moveTo(12*u,4.2f*u);lineTo(18.5f*u,6.6f*u);lineTo(18.5f*u,11.8f*u);cubicTo(18.5f*u,16.5f*u,14.7f*u,19.1f*u,12*u,20*u);cubicTo(9.3f*u,19.1f*u,5.5f*u,16.5f*u,5.5f*u,11.8f*u);lineTo(5.5f*u,6.6f*u);close()}
  drawPath(shield,fg,style=Stroke(1.5f*u,cap=StrokeCap.Round,join=StrokeJoin.Round))
  val check=Path().apply{moveTo(8.8f*u,11.8f*u);lineTo(11.1f*u,14.1f*u);lineTo(15.6f*u,9.5f*u)}
  drawPath(check,fg,style=Stroke(1.6f*u,cap=StrokeCap.Round,join=StrokeJoin.Round))
 }
}
