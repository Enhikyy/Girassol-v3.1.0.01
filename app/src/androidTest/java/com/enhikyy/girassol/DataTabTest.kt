package com.enhikyy.girassol

import android.Manifest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataTabTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dataTabIsReachableAndShowsLiveDashboard() {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab_DADOS"), 60_000)
        rule.onNodeWithTag("tab_DADOS").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("data_dashboard"), 10_000)
        rule.onNodeWithTag("data_dashboard").assertExists()
    }
}
