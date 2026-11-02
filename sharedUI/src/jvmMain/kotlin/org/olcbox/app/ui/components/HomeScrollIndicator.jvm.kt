package org.olcbox.app.ui.components
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
@Composable actual fun HomeScrollIndicator(state: ScrollState, modifier: Modifier) {
    if(state.maxValue>0) VerticalScrollbar(adapter=rememberScrollbarAdapter(state),modifier=modifier)
}
