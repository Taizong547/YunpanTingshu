package com.yunpan.tingshu.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Robolectric：验证 MediaSession 能在本环境基于 ExoPlayer 创建（PlaybackService 即以此装配）。
 * 注：单测 sourceSet 未启用 includeAndroidResources（不动 build.gradle），
 * Robolectric 无 manifest，故不实例化真实 PlaybackService（其 onCreate 依赖 YunpanApp.locator）；
 * 这里直接验证“ExoPlayer + MediaSession”可创建这一核心能力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackServiceTest {

    @Test fun mediaSessionCreatesWithExoPlayer() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val player = ExoPlayer.Builder(ctx).build()
        val session = MediaSession.Builder(ctx, player).build()
        assertThat(session).isNotNull()
        assertThat(session.player).isSameInstanceAs(player)
        session.release()
        player.release()
    }
}
