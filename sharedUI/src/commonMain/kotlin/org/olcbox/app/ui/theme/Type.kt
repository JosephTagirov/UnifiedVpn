package org.olcbox.app.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import unifiedvpn.sharedui.generated.resources.Res
import unifiedvpn.sharedui.generated.resources.inter_regular
import unifiedvpn.sharedui.generated.resources.inter_medium
import unifiedvpn.sharedui.generated.resources.inter_semi_bold
import unifiedvpn.sharedui.generated.resources.inter_bold
import org.jetbrains.compose.resources.Font

@Composable fun getAppTypography(): Typography {
 val font=FontFamily(Font(Res.font.inter_regular,FontWeight.Normal),Font(Res.font.inter_medium,FontWeight.Medium),Font(Res.font.inter_semi_bold,FontWeight.SemiBold),Font(Res.font.inter_bold,FontWeight.Bold))
 val t=Typography()
 return Typography(
  displayLarge=t.displayLarge.copy(fontFamily=font,fontWeight=FontWeight.Medium,letterSpacing=(-2).sp),
  displayMedium=t.displayMedium.copy(fontFamily=font,fontWeight=FontWeight.Medium,letterSpacing=(-1.5).sp),
  displaySmall=t.displaySmall.copy(fontFamily=font,fontWeight=FontWeight.Medium,letterSpacing=(-1).sp),
  headlineLarge=t.headlineLarge.copy(fontFamily=font,fontWeight=FontWeight.Medium,letterSpacing=(-1).sp),
  headlineMedium=t.headlineMedium.copy(fontFamily=font,fontWeight=FontWeight.Medium,letterSpacing=(-.6).sp),
  headlineSmall=t.headlineSmall.copy(fontFamily=font,fontWeight=FontWeight.Medium,letterSpacing=(-.4).sp),
  titleLarge=t.titleLarge.copy(fontFamily=font,fontWeight=FontWeight.SemiBold),
  titleMedium=t.titleMedium.copy(fontFamily=font,fontWeight=FontWeight.SemiBold,fontSize=15.sp),
  titleSmall=t.titleSmall.copy(fontFamily=font,fontWeight=FontWeight.SemiBold),
  bodyLarge=t.bodyLarge.copy(fontFamily=font),bodyMedium=t.bodyMedium.copy(fontFamily=font),bodySmall=t.bodySmall.copy(fontFamily=font),
  labelLarge=t.labelLarge.copy(fontFamily=font,fontWeight=FontWeight.SemiBold),labelMedium=t.labelMedium.copy(fontFamily=font),labelSmall=t.labelSmall.copy(fontFamily=font)
 )
}
