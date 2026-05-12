package com.sound2inat.app.permissions

import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(sdk = [33])
@RunWith(RobolectricTestRunner::class)
class AndroidPermissionsControllerTest {
    @Test
    fun `controller exposes camera permission`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        val controller = AndroidPermissionsController(activity)

        assertThat(controller.statuses.value).containsKey(Permission.CAMERA)
    }
}
