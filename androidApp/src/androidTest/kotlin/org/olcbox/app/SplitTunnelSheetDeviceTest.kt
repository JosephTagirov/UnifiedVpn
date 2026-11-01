package org.olcbox.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SplitTunnelSheetDeviceTest {
    @Test fun draggingSplitTunnelAndAppListSheetsKeepsHomeResponsive() {
        assertTrue("Only the disposable QA package may run this test",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".shadeqa"))
        runAndroidComposeUiTest<AppActivity> {
            onNodeWithText("Add olcRTC location").assertDoesNotExist()
            onNodeWithContentDescription("Add configuration").assertIsDisplayed()
            repeat(3) { attempt ->
                onNodeWithContentDescription("Split tunneling").performClick()
                onNodeWithText("Split Tunneling").assertIsDisplayed()
                if (attempt > 0) {
                    onNodeWithText("Bypass Selected").performScrollTo().performClick()
                    onNodeWithText("Bypassed Apps").performScrollTo().performClick()
                    onNode(hasScrollToNodeAction()).performScrollToNode(hasText("com.android.chrome"))
                    onNodeWithText("com.android.chrome").assertIsDisplayed()
                }
                val distance = activity!!.resources.displayMetrics.heightPixels * 0.8f
                onNodeWithContentDescription("Drag handle").performTouchInput {
                    swipe(start = center, end = center + Offset(0f, distance), durationMillis = 600)
                }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Drag handle").fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Add configuration").assertIsDisplayed()
                onNodeWithText("Add olcRTC location").assertDoesNotExist()
            }
        }
    }
}

