package cz.teply.sheetset

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.WindowCompat
import androidx.test.espresso.Espresso
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class MainActivitySmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.P)
    fun navigationBarFollowsAppearanceOnAndroid9() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Appearance").performScrollTo().performClick()
        composeRule.onNodeWithText("Ocean").performScrollTo().performClick()
        val window = composeRule.activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)

        composeRule.onNodeWithText("Dark").performClick()
        composeRule.runOnIdle {
            assertEquals(0xFF142234.toInt(), window.navigationBarColor)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }

        composeRule.onNodeWithText("Light").performClick()
        composeRule.runOnIdle {
            assertEquals(0xFFF8FBFF.toInt(), window.navigationBarColor)
            assertTrue(bars.isAppearanceLightNavigationBars)
        }
        composeRule.onNodeWithText("Monochrome").performScrollTo().performClick()
    }

    @Test
    fun appLaunches() {
        composeRule.onNodeWithContentDescription("Import PDF").assertIsDisplayed()
    }

    @Test
    fun rootBackDoesNotFinishApp() {
        Espresso.pressBack()

        composeRule.onNodeWithContentDescription("Import PDF").assertIsDisplayed()
    }
}
