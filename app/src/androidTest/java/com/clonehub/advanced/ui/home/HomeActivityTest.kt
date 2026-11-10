package com.clonehub.advanced.ui.home

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clonehub.advanced.R
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
        ActivityScenario.launch(HomeActivity::class.java).use {
            onView(withId(R.id.btnNextSpace)).perform(click())
            onView(withId(R.id.tvSpaceIndicator)).check(matches(withText("2")))

            onView(withId(R.id.btnPrevSpace)).perform(click())
            onView(withId(R.id.tvSpaceIndicator)).check(matches(withText("1")))
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
