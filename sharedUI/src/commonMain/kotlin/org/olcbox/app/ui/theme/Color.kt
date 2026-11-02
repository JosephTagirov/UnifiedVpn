package org.olcbox.app.ui.theme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Shared with the owner's cabinet: warm canvas, forest green, olive and white cards.
internal val OlcboxLightColorScheme = lightColorScheme(
 primary=Color(0xFF203B2C), onPrimary=Color.White,
 primaryContainer=Color(0xFFE6EADB), onPrimaryContainer=Color(0xFF21382B), inversePrimary=Color(0xFFBFCEA8),
 secondary=Color(0xFF67794E), onSecondary=Color.White,
 secondaryContainer=Color(0xFFE8ECDF), onSecondaryContainer=Color(0xFF344C39),
 tertiary=Color(0xFF7C8C60), onTertiary=Color.White,
 tertiaryContainer=Color(0xFFEAF0DF), onTertiaryContainer=Color(0xFF3E572C),
 background=Color(0xFFF4F5EF), onBackground=Color(0xFF21382B),
 surface=Color(0xFFF4F5EF), onSurface=Color(0xFF21382B),
 surfaceVariant=Color(0xFFE8ECDF), onSurfaceVariant=Color(0xFF64735C),
 surfaceContainerLowest=Color.White, surfaceContainerLow=Color.White, surfaceContainer=Color.White,
 surfaceContainerHigh=Color(0xFFE8ECDF), surfaceContainerHighest=Color(0xFFE6EADB),
 inverseSurface=Color(0xFF21382B), inverseOnSurface=Color(0xFFF4F5EF),
 outline=Color(0xFF7C8C70), outlineVariant=Color(0xFFDCE1D4),
 error=Color(0xFF943F2D), onError=Color.White, errorContainer=Color(0xFFF7E5DA), onErrorContainer=Color(0xFF703826)
)
internal val OlcboxDarkColorScheme = darkColorScheme(
 primary=Color(0xFFC3D4AF), onPrimary=Color(0xFF17281D),
 primaryContainer=Color(0xFF2C4333), onPrimaryContainer=Color(0xFFE7EEDC), inversePrimary=Color(0xFF203B2C),
 secondary=Color(0xFFB5C69D), onSecondary=Color(0xFF20311F),
 secondaryContainer=Color(0xFF30432E), onSecondaryContainer=Color(0xFFE8ECDF),
 tertiary=Color(0xFFBCC9A5), onTertiary=Color(0xFF28341D),
 tertiaryContainer=Color(0xFF3E4D30), onTertiaryContainer=Color(0xFFE7EED6),
 background=Color(0xFF111C16), onBackground=Color(0xFFEDF1E7),
 surface=Color(0xFF111C16), onSurface=Color(0xFFEDF1E7),
 surfaceVariant=Color(0xFF34483A), onSurfaceVariant=Color(0xFFBFC9B6),
 surfaceContainerLowest=Color(0xFF0D1711), surfaceContainerLow=Color(0xFF19271F),
 surfaceContainer=Color(0xFF1D2D23), surfaceContainerHigh=Color(0xFF293C2E), surfaceContainerHighest=Color(0xFF34483A),
 inverseSurface=Color(0xFFEDF1E7), inverseOnSurface=Color(0xFF21382B),
 outline=Color(0xFF93A388), outlineVariant=Color(0xFF3A4E3F),
 error=Color(0xFFFFB4AB), onError=Color(0xFF561F15), errorContainer=Color(0xFF723B2B), onErrorContainer=Color(0xFFFFDAD0)
)
