package com.clonehub.advanced.ui.home

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.viewpager2.widget.ViewPager2
import com.clonehub.advanced.R
import com.clonehub.advanced.core.container.SpaceManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeActivityTest {

    @Test
    fun homeScreen_displaysFirstSpaceAndAddAction() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            onView(withId(R.id.viewPagerSpaces)).check(matches(isDisplayed()))
            onView(withId(R.id.tvSpaceIndicator)).check(matches(withText("1")))
            onView(withId(R.id.fabAdd)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun nextSpaceButton_createsAndSelectsSecondSpace() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nextButton = activity.findViewById<android.widget.ImageButton>(R.id.btnNextSpace)
                nextButton.setOnTouchListener { view, event ->
                    Log.i("HomeActivityTest", "Next touch: action=${event.action}, " +
                        "x=${event.x}, y=${event.y}, focused=${view.hasWindowFocus()}")
                    false
                }
            }
            onView(withId(R.id.btnNextSpace)).perform(click())
            onView(withId(R.id.tvSpaceIndicator)).check(matches(withText("2")))
            scenario.onActivity { activity ->
                assertEquals(2, SpaceManager.getInstance(activity).getSpaces().size)
                assertEquals(1, activity.findViewById<ViewPager2>(R.id.viewPagerSpaces).currentItem)
            }

            onView(withId(R.id.btnPrevSpace)).perform(click())
            onView(withId(R.id.tvSpaceIndicator)).check(matches(withText("1")))
        }
    }

    @Test
    fun nextSpaceButton_performClickCreatesSecondSpace() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.ImageButton>(R.id.btnNextSpace).performClick()
                assertEquals(2, SpaceManager.getInstance(activity).getSpaces().size)
            }
            onView(withId(R.id.tvSpaceIndicator)).check(matches(withText("2")))
            scenario.onActivity { activity ->
                assertEquals(1, activity.findViewById<ViewPager2>(R.id.viewPagerSpaces).currentItem)
            }
        }
    }

    @Test
    fun addAction_opensAppPicker() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            onView(withId(R.id.fabAdd)).perform(click())
            onView(withId(R.id.searchView)).check(matches(isDisplayed()))
            onView(withId(R.id.recyclerInstalledApps)).check(matches(isDisplayed()))
        }
    }
}
