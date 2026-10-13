package com.yunpan.tingshu.player

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.player.session.SavedSession
import com.yunpan.tingshu.player.session.SessionKv
import com.yunpan.tingshu.player.session.SessionSource
import com.yunpan.tingshu.player.session.SessionStore
import com.yunpan.tingshu.player.session.SessionTrack
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionStoreTest {

    private val kv = object : SessionKv {
        var v: String? = null
        override suspend fun get(): String? = v
        override suspend fun set(json: String?) { v = json }
    }
    private val clock = VirtualClock().apply { ms = 1_700_000_000_000L }
    private val store = SessionStore(kv, clock)

    private fun sample(source: SessionSource) = SavedSession(
        source = source,
        accountId = if (source == SessionSource.CLOUD) "acc1" else null,
        linkId = if (source == SessionSource.SHARE) "2xTrMfreXj5lq" else null,
        passwd = if (source == SessionSource.SHARE) "" else null,
        shareName = if (source == SessionSource.SHARE) "评书合集" else null,
        albumTitle = "老梁评书",
        rootToken = null,
        tracks = listOf(
            SessionTrack("f1", "p1", "第一集.mp3", listOf("专辑A"), "mp3", 1800, false, parentToken = "FOLDER1"),
            SessionTrack("f2", "p1", "第二集.m4a", listOf("专辑A"), "m4a", 2000, false, parentToken = "FOLDER1")
        ),
        index = 1,
        positionMs = 45_000,
        playMode = com.yunpan.tingshu.player.PlayMode.SHUFFLE,
        speed = 1.25f,
        sleepConfig = "Episodes",
        updatedAt = clock.ms
    )

    @Test fun roundTripCloud() = runTest {
        store.save(sample(SessionSource.CLOUD))
        val loaded = store.load()!!
        assertThat(loaded.source).isEqualTo(SessionSource.CLOUD)
        assertThat(loaded.accountId).isEqualTo("acc1")
        assertThat(loaded.tracks).hasSize(2)
        assertThat(loaded.index).isEqualTo(1)
        assertThat(loaded.positionMs).isEqualTo(45_000)
        assertThat(loaded.playMode).isEqualTo(com.yunpan.tingshu.player.PlayMode.SHUFFLE)
        assertThat(loaded.speed).isEqualTo(1.25f)
        assertThat(loaded.sleepConfig).isEqualTo("Episodes")
        assertThat(loaded.currentTrack()!!.name).isEqualTo("第二集.m4a")
    }

    @Test fun roundTripShare() = runTest {
        store.save(sample(SessionSource.SHARE))
        val loaded = store.load()!!
        assertThat(loaded.source).isEqualTo(SessionSource.SHARE)
        assertThat(loaded.linkId).isEqualTo("2xTrMfreXj5lq")
        assertThat(loaded.shareName).isEqualTo("评书合集")
        assertThat(loaded.tracks[0].isHls).isFalse()
        // parentToken 落盘/恢复
        assertThat(loaded.tracks[0].parentToken).isEqualTo("FOLDER1")
        // 不存 URL：序列化结果里没有 http
        assertThat(kv.v!!).doesNotContain("presentURL")
    }

    @Test fun oldJsonWithoutParentTokenDecodesToNull() = runTest {
        // 旧版会话 JSON（曲目无 parentToken 字段）应兼容恢复为 null。
        kv.set(
            """{"source":"SHARE","linkId":"L1","tracks":[
              {"fileId":"f1","name":"a.mp3","albumPath":[],"durationSec":1,"isHls":true}],
              "index":0,"positionMs":0,"playMode":"SEQUENTIAL","speed":1.0,"updatedAt":0}"""
        )
        val loaded = store.load()!!
        assertThat(loaded.tracks[0].fileId).isEqualTo("f1")
        assertThat(loaded.tracks[0].parentToken).isNull()
    }

    @Test fun clearRemovesSession() = runTest {
        store.save(sample(SessionSource.CLOUD))
        store.clear()
        assertThat(store.load()).isNull()
    }

    @Test fun corruptJsonReturnsNull() = runTest {
        kv.set("{not json")
        assertThat(store.load()).isNull()
    }
}
