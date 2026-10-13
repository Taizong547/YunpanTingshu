package com.yunpan.tingshu.data.cover

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.db.AppDatabase
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.util.Clock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** fake 压缩器：原样返回（便于用输入字节哈希去重）。 */
private class EchoCompressor : ImageCompressor {
    override fun compress(src: ByteArray): ByteArray = src
}

private class FixedClock : Clock {
    override fun nowMs(): Long = 1_000_000L
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoverStoreAndProviderTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var storage: CoverStorage
    private lateinit var store: CoverStore

    @Before fun setup() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        storage = DirCoverStorage(tmp.root.resolve("covers"))
        store = CoverStore(db.coverDao(), db.coverOverrideDao(), storage, EchoCompressor(), FixedClock())
    }

    @After fun teardown() { db.close() }

    private fun coversDir(): File = tmp.root.resolve("covers")

    private fun audioFile(id: String, parentId: String = "", thumb: String = "") = CloudFile(
        id = id, name = "$id.mp3", isDir = false, size = 0, updatedAtMs = 0,
        thumbnailUrl = thumb, parentId = parentId, audioExt = "mp3",
        source = FileSource.CLOUD139, parentToken = parentId.ifEmpty { null }
    )

    @Test fun `same image imported for many keys writes only one physical file`() = runTest {
        val img = byteArrayOf(1, 2, 3, 4, 5)
        val coverId = store.importFromBytes(img)
        store.batchSet(CoverStore.SCOPE_FILE, (1..10).map { "f$it" }, coverId)
        // 仅 1 个物理文件（.nomedia 不算）
        val jpgs = coversDir().listFiles()!!.filter { it.extension == "jpg" }
        assertThat(jpgs).hasSize(1)
        assertThat(db.coverOverrideDao().getAllOverrides()).hasSize(10)
        assertThat(store.countRefs(coverId)).isEqualTo(10)
    }

    @Test fun `different images stored separately`() = runTest {
        val c1 = store.importFromBytes(byteArrayOf(1, 1, 1))
        val c2 = store.importFromBytes(byteArrayOf(9, 9, 9))
        assertThat(c1).isNotEqualTo(c2)
        assertThat(coversDir().listFiles()!!.count { it.extension == "jpg" }).isEqualTo(2)
    }

    @Test fun `deleting all mappings cleans up orphan physical file`() = runTest {
        val coverId = store.importFromBytes(byteArrayOf(7, 7, 7))
        store.setFileCover("f1", coverId)
        assertThat(storage.exists(coverId)).isTrue()
        store.clear(CoverStore.SCOPE_FILE, "f1")
        assertThat(storage.exists(coverId)).isFalse()
        assertThat(store.countRefs(coverId)).isEqualTo(0)
    }

    @Test fun `replacing cover cleans old only when refs drop to zero`() = runTest {
        val oldC = store.importFromBytes(byteArrayOf(1, 2, 3))
        val newC = store.importFromBytes(byteArrayOf(8, 8, 8))
        store.setFileCover("f1", oldC)
        store.setFileCover("f2", oldC)      // oldC 被两个 key 引用
        store.setFileCover("f1", newC)      // f1 换图 → oldC 仍被 f2 引用，不删
        assertThat(storage.exists(oldC)).isTrue()
        store.clear(CoverStore.SCOPE_FILE, "f2")  // oldC 引用归 0 → 删
        assertThat(storage.exists(oldC)).isFalse()
        assertThat(storage.exists(newC)).isTrue()
    }

    @Test fun `nomedia written`() = runTest {
        store.importFromBytes(byteArrayOf(1))
        assertThat(File(coversDir(), ".nomedia").exists()).isTrue()
    }

    // ---------- Provider 优先级链 ----------

    @Test fun `FILE override wins and points to local path`() = runTest {
        val auto = RecordingAuto()
        val provider = CoverArtProvider(store, auto)
        val file = audioFile("f1", parentId = "D1")
        store.setFileCover("f1", store.importFromBytes("file-cover".toByteArray()))
        val ref = provider.artworkFor(file)
        assertThat(ref).isInstanceOf(ArtworkRef.Local::class.java)
        assertThat((ref as ArtworkRef.Local).path).endsWith(".jpg")
        assertThat(auto.called).isFalse()
    }

    @Test fun `FILE override beats ALBUM override`() = runTest {
        val provider = CoverArtProvider(store, null)
        val file = audioFile("f1", parentId = "D1")
        store.setAlbumCover("D1", store.importFromBytes("album-cover".toByteArray()))
        store.setFileCover("f1", store.importFromBytes("file-cover".toByteArray()))
        val ref = provider.artworkFor(file) as ArtworkRef.Local
        assertThat(ref.path).contains(CoverStore.sha256Hex("file-cover".toByteArray()).take(16))
    }

    @Test fun `falls back to ALBUM override when no FILE`() = runTest {
        val provider = CoverArtProvider(store, null)
        val albumC = store.importFromBytes("album-cover".toByteArray())
        store.setAlbumCover("D1", albumC)
        val ref = provider.artworkFor(audioFile("f1", parentId = "D1")) as ArtworkRef.Local
        assertThat(ref.path).contains(CoverStore.sha256Hex("album-cover".toByteArray()).take(16))
    }

    @Test fun `missing physical file skips override layer`() = runTest {
        val auto = RecordingAuto()
        val provider = CoverArtProvider(store, auto)
        val coverId = store.importFromBytes("x".toByteArray())
        store.setFileCover("f1", coverId)
        storage.delete(coverId) // 物理文件被删，但 Room 记录还在
        val ref = provider.artworkFor(audioFile("f1", parentId = "D1", thumb = "https://cdn/t.jpg"))
        // FILE 层文件不存在 → 跳过 → 文件自带缩略图
        assertThat(ref).isInstanceOf(ArtworkRef.Remote::class.java)
        assertThat((ref as ArtworkRef.Remote).url).isEqualTo("https://cdn/t.jpg")
    }

    @Test fun `auto cover then thumbnail then null`() = runTest {
        val file = audioFile("f1", parentId = "D1", thumb = "https://cdn/t.jpg")
        // 无自定义覆盖
        val auto = RecordingAuto("https://auto/folder.jpg")
        assertThat((CoverArtProvider(store, auto).artworkFor(file) as ArtworkRef.Remote).url)
            .isEqualTo("https://auto/folder.jpg")
        // 无 auto → thumbnail
        assertThat((CoverArtProvider(store, null).artworkFor(file) as ArtworkRef.Remote).url)
            .isEqualTo("https://cdn/t.jpg")
        // 都没有 → null
        assertThat(CoverArtProvider(store, null).artworkFor(audioFile("f1", parentId = "D1"))).isNull()
    }

    private class RecordingAuto(val url: String? = null) : AutoCoverSource {
        var called = false
        override suspend fun autoFor(file: CloudFile): ArtworkRef.Remote? {
            called = true
            return url?.let { ArtworkRef.Remote(it) }
        }
    }
}
