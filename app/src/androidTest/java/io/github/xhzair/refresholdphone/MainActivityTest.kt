package io.github.xhzair.refresholdphone

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xhzair.refresholdphone.apps.MediaApp
import io.github.xhzair.refresholdphone.media.PlaybackControls
import io.github.xhzair.refresholdphone.media.PlaybackSnapshot
import io.github.xhzair.refresholdphone.ui.HomeViewModel
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private lateinit var application: MediaShellApplication
    private lateinit var playback: FakePlaybackBridge

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        playback = FakePlaybackBridge()
        val catalog = FakeCatalog(
            listOf(
                MediaApp("com.netease.cloudmusic", "网易云音乐", installed = true, official = true),
                MediaApp("com.tencent.qqmusic", "QQ音乐", installed = false, official = true),
                MediaApp("app.podcast.cosmos", "小宇宙", installed = false, official = true),
                MediaApp("org.videolan.vlc", "VLC", installed = true, official = false),
            ),
        )
        application.homeViewModelOverride = {
            HomeViewModel(
                catalog = catalog,
                selectedPackages = { emptySet() },
                playbackBridge = playback,
                statusSource = FakeStatusSource(),
            )
        }
    }

    @After
    fun tearDown() {
        application.homeViewModelOverride = null
    }

    @Test
    fun compactEntryOpensDialogWithInstalledSourcesOnly() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.mediaSource)).check(matches(isDisplayed()))
            onView(withId(R.id.mediaSource)).perform(click())

            onView(withText(R.string.media_source_dialog_title)).check(matches(isDisplayed()))
            onView(withText("网易云音乐")).check(matches(isDisplayed()))
            onView(withText("VLC")).check(matches(isDisplayed()))
            onView(withText("QQ音乐")).check(doesNotExist())
            onView(withText("小宇宙")).check(doesNotExist())

            onView(withText(R.string.settings_cancel)).perform(click())
            onView(withText(R.string.media_source_dialog_title)).check(doesNotExist())
        }
    }

    @Test
    fun playbackSnapshotDrivesTitleArtistAndCapabilityGatedControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playback.emitPlayback(
                    PlaybackSnapshot(
                        packageName = "com.netease.cloudmusic",
                        providerLabel = "网易云音乐",
                        title = "夜曲",
                        artist = "周杰伦",
                        durationMs = 240_000,
                        positionMs = 60_000,
                        playing = true,
                        controls = PlaybackControls(
                            canPlayPause = true,
                            canSkipPrevious = false,
                            canSkipNext = true,
                        ),
                    ),
                )
            }
            onView(withText("夜曲")).check(matches(isDisplayed()))
            onView(withText("周杰伦")).check(matches(isDisplayed()))
            onView(withId(R.id.playPause)).check(
                matches(allOf(isDisplayed(), isEnabled(), withContentDescription(R.string.pause))),
            )
            onView(withId(R.id.previous)).check(matches(allOf(isDisplayed(), not(isEnabled()))))
            onView(withId(R.id.next)).check(matches(allOf(isDisplayed(), isEnabled())))
        }
    }

    @Test
    fun artworkFillsDesktopBackgroundWhilePlaying() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    android.view.View.INVISIBLE,
                    activity.findViewById<android.widget.ImageView>(R.id.artworkBackground).visibility,
                )
            }
            val artwork = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playback.emitPlayback(
                    PlaybackSnapshot(
                        packageName = "com.netease.cloudmusic",
                        providerLabel = "网易云音乐",
                        title = "夜曲",
                        artist = "周杰伦",
                        durationMs = 240_000,
                        positionMs = 60_000,
                        playing = true,
                        controls = PlaybackControls(canPlayPause = true, canSkipPrevious = false, canSkipNext = true),
                        artwork = artwork,
                    ),
                )
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val background = activity.findViewById<android.widget.ImageView>(R.id.artworkBackground)
                assertEquals(android.view.View.VISIBLE, background.visibility)
                val drawable = background.drawable as android.graphics.drawable.BitmapDrawable
                assertEquals(artwork, drawable.bitmap)
            }
        }
    }

    @Test
    fun appDrawerOpensAllAppsList() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.appDrawer)).perform(click())
            onView(withText(R.string.all_apps)).check(matches(isDisplayed()))
            onView(withId(R.id.appList)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun notificationPromptOpensOnlyOnClick() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(R.string.permission_prompt)).check(matches(isDisplayed()))
            onView(withText(R.string.permission_title)).check(doesNotExist())

            onView(withText(R.string.permission_prompt)).perform(click())

            onView(withText(R.string.permission_title)).check(matches(isDisplayed()))
            onView(withText(R.string.cancel)).perform(click())
            onView(withText(R.string.permission_title)).check(doesNotExist())
        }
    }

    @Test
    fun transportButtonsForwardToPlaybackBridge() {
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                playback.emitPlayback(
                    PlaybackSnapshot(
                        packageName = "com.netease.cloudmusic",
                        providerLabel = "网易云音乐",
                        title = "夜曲",
                        artist = "周杰伦",
                        durationMs = 240_000,
                        positionMs = 60_000,
                        playing = true,
                        controls = PlaybackControls(
                            canPlayPause = true,
                            canSkipPrevious = true,
                            canSkipNext = true,
                        ),
                    ),
                )
            }
            onView(withId(R.id.playPause)).perform(click())
            onView(withId(R.id.previous)).perform(click())
            onView(withId(R.id.next)).perform(click())

            assertEquals(1, playback.playPauseCalls)
            assertEquals(1, playback.skipPreviousCalls)
            assertEquals(1, playback.skipNextCalls)
        }
    }
}
