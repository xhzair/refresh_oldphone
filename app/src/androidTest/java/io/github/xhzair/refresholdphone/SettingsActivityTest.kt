package io.github.xhzair.refresholdphone

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xhzair.refresholdphone.prefs.OrientationMode
import io.github.xhzair.refresholdphone.prefs.UserPreferences
import io.github.xhzair.refresholdphone.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    private lateinit var preferences: UserPreferences
    private var previousOrientation = OrientationMode.AUTO
    private var previousKeepScreenOn = false

    @Before
    fun setUp() {
        preferences = UserPreferences(ApplicationProvider.getApplicationContext())
        previousOrientation = preferences.orientationMode
        previousKeepScreenOn = preferences.keepScreenOn
    }

    @After
    fun tearDown() {
        preferences.orientationMode = previousOrientation
        preferences.keepScreenOn = previousKeepScreenOn
    }

    @Test
    fun landscapeChoiceIsPersisted() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.settings_orientation_landscape)).perform(scrollTo(), click())
            assertEquals(OrientationMode.LANDSCAPE, preferences.orientationMode)
        }
    }

    @Test
    fun recoveryEntriesAndSystemShortcutsAreReachable() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.settings_restore_home)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_choose_home)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_open_notification_access)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_open_wifi)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_open_bluetooth)).perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withText(R.string.settings_clear_button)).perform(scrollTo())
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun clearingLocalConfigRequiresConfirmation() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            onView(withText(R.string.settings_clear_button)).perform(scrollTo(), click())
            onView(withText(R.string.settings_clear_title)).check(matches(isDisplayed()))
            onView(withText(R.string.settings_cancel)).perform(click())
        }
    }
}
