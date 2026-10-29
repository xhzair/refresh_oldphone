package io.github.xhzair.refresholdphone

import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.hasSibling
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
import io.github.xhzair.refresholdphone.ui.MediaAppAdapter
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
    fun homeRendersOfficialProvidersInStableOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.appList)
                val adapter = list.adapter as MediaAppAdapter
                assertEquals(3, adapter.itemCount)
                assertEquals(
                    listOf("com.netease.cloudmusic", "com.tencent.qqmusic", "app.podcast.cosmos"),
                    adapter.currentList.map { it.packageName },
                )
            }
            onView(withText("网易云音乐")).check(matches(isDisplayed()))
            onView(allOf(withText("未安装"), hasSibling(withText("QQ音乐"))))
                .check(matches(isDisplayed()))
            onView(allOf(withText("已安装"), hasSibling(withText("网易云音乐"))))
                .check(matches(isDisplayed()))
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
            onView(withText("周杰伦 · 网易云音乐")).check(matches(isDisplayed()))
            onView(withId(R.id.playPause)).check(
                matches(allOf(isDisplayed(), isEnabled(), withContentDescription(R.string.pause))),
            )
            onView(withId(R.id.previous)).check(matches(allOf(isDisplayed(), not(isEnabled()))))
            onView(withId(R.id.next)).check(matches(allOf(isDisplayed(), isEnabled())))
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
