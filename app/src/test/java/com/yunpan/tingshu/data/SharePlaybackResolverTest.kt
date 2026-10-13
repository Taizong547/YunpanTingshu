package com.yunpan.tingshu.data

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.cloud139.Cloud139Repository
import com.yunpan.tingshu.cloud139.share.OutlinkCrypto
import com.yunpan.tingshu.cloud139.share.ShareRepository
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.CloudFile
import com.yunpan.tingshu.data.model.FileSource
import com.yunpan.tingshu.data.model.ListPage
import com.yunpan.tingshu.data.model.ScanDepth
import com.yunpan.tingshu.data.model.ScannedTrack
import com.yunpan.tingshu.data.model.ScanProgress
import com.yunpan.tingshu.util.Clock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/** 固定时钟（TTL 缓存测试用）。 */
private class FakeClock : Clock {
    var now: Long = 1_000_000L
    override fun nowMs(): Long = now
}

/** 极简 CLOUD fake：只验证 getPlayUrl 被调用。 */
private class FakeCloud : Cloud139Repository {
    var playUrlFor: String? = null
    override suspend fun listFolder(account: CloudAccount, parentId: String, cursor: String): ListPage =
        ListPage(emptyList(), "")
    override suspend fun listAllAudio(account: CloudAccount, folderId: String): List<CloudFile> = emptyList()
    override suspend fun getPlayUrl(account: CloudAccount, fileId: String): String =
        "https://cdn.example/$fileId.mp3"
    override fun streamHeaders(): Map<String, String> = mapOf("UA" to "x")
    override suspend fun scanAudio(
        account: CloudAccount, rootFolderId: String, depth: ScanDepth,
        progress: ((ScanProgress) -> Unit)?
    ): List<ScannedTrack> = emptyList()
}

class SharePlaybackResolverTest {

    private lateinit var server: MockWebServer

    /** 由测试控制：openShare 成功与否、列目录返回哪页。 */
    private var openShareShouldFail = false
    private var openCount = 0
    /** bNum -> 明文响应体；缺省返回空目录。 */
    private var pageResponder: (Int, String) -> String = { _, _ -> emptyListing() }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        openShareShouldFail = false
        openCount = 0
        pageResponder = { _, _ -> emptyListing() }
    }

    @After fun tearDown() { server.shutdown() }

    private fun emptyListing() = JSONObject()
        .put("resultCode", "0")
        .put("data", JSONObject().put("nodNum", 0).put("caLst", JSONArray()).put("coLst", JSONArray()))
        .toString()

    private fun audio(coID: String, present: String) = JSONObject()
        .put("coID", coID).put("coType", 2).put("coName", "$coID.mp3").put("coSuffix", "mp3")
        .put("presentURL", present)

    private fun listing(vararg items: JSONObject, nodNum: Int = items.size) = JSONObject()
        .put("resultCode", "0")
        .put("data", JSONObject().put("nodNum", nodNum).put("caLst", JSONArray()).put("coLst", JSONArray(items.toList())))
        .toString()

    private fun shareRepo(): ShareRepository {
        val base = server.url("/")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val orig = chain.request().url
                val nu = base.newBuilder(orig.encodedPath)!!.encodedQuery(orig.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(nu).build())
            }.build()
        return ShareRepository(client)
    }

    private fun dispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            val encBody = req.body.readUtf8().trim()
            val plain = try { OutlinkCrypto.decrypt(encBody) } catch (_: Exception) { encBody }
            val path = req.path ?: ""
            val respPlain: String = when {
                path.contains("getOutLinkGeneral") -> {
                    openCount++
                    if (openShareShouldFail) "NOT_A_JSON_ERROR"
                    else JSONObject().put("resultCode", "0").put("data", JSONObject()).toString()
                }
                path.contains("getOutLinkInfoV6") -> {
                    val bNum = JSONObject(plain).getJSONObject("getOutLinkInfoReq").optInt("bNum", 1)
                    val pCa = JSONObject(plain).getJSONObject("getOutLinkInfoReq").optString("pCaID", "root")
                    pageResponder(bNum, pCa)
                }
                else -> emptyListing()
            }
            return MockResponse()
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(OutlinkCrypto.encrypt(respPlain))
        }
    }

    private fun restoredShareFile(id: String, pCa: String?) = CloudFile(
        id = id, name = "$id.mp3", isDir = false, size = 0, updatedAtMs = 0,
        thumbnailUrl = "", parentId = pCa ?: "", audioExt = "mp3",
        source = FileSource.SHARE, directUrl = null, isHls = true,
        parentToken = pCa, shareLinkId = "LINK1", sharePasswd = ""
    )

    private fun resolver(clock: Clock = FakeClock()) =
        DefaultPlaybackResolver(FakeCloud(), shareRepo(), clock) { null }

    @Test
    fun `restored share track re-resolves via openShare then listShareFolder`() = runTest {
        server.dispatcher = dispatcher()
        pageResponder = { _, _ ->
            listing(audio("co-t-2", "https://x/hls/t2.m3u8"))
        }
        val r = resolver()
        val spec = r.resolve(restoredShareFile("co-t-2", "FOLDER1"))
        assertThat(spec.uri).isEqualTo("https://x/hls/t2.m3u8")
        assertThat(spec.isHls).isTrue()
        assertThat(spec.headers).isEmpty()
        assertThat(openCount).isEqualTo(1)
    }

    @Test
    fun `directUrl present on restored file takes fast path and skips network`() = runTest {
        server.dispatcher = dispatcher()
        val r = resolver()
        val file = restoredShareFile("co-x", "FOLDER1").copy(directUrl = "https://x/direct.mp3")
        val spec = r.resolve(file)
        assertThat(spec.uri).isEqualTo("https://x/direct.mp3")
        assertThat(openCount).isEqualTo(0)
    }

    @Test
    fun `finds target on second page`() = runTest {
        server.dispatcher = dispatcher()
        pageResponder = { bNum, _ ->
            if (bNum <= 1) listing(audio("co-t-1", "https://x/t1.m3u8"), nodNum = 201)
            else listing(audio("co-t-9", "https://x/t9.m3u8"), nodNum = 201)
        }
        val r = resolver()
        val spec = r.resolve(restoredShareFile("co-t-9", "FOLDER1"))
        assertThat(spec.uri).isEqualTo("https://x/t9.m3u8")
    }

    @Test
    fun `openShare cached by linkId within TTL does not re-open`() = runTest {
        server.dispatcher = dispatcher()
        pageResponder = { _, _ -> listing(audio("co-t-2", "https://x/t2.m3u8")) }
        val clock = FakeClock()
        val r = resolver(clock)
        r.resolve(restoredShareFile("co-t-2", "FOLDER1"))
        assertThat(openCount).isEqualTo(1)
        // 1 小时后仍在 TTL 内
        clock.now += 3_600_000
        r.resolve(restoredShareFile("co-t-2", "FOLDER1"))
        assertThat(openCount).isEqualTo(1)
        // 超过 8h TTL → 重开
        clock.now += 9L * 3600 * 1000
        r.resolve(restoredShareFile("co-t-2", "FOLDER1"))
        assertThat(openCount).isEqualTo(2)
    }

    @Test
    fun `openShare failure throws clear Chinese error`() = runTest {
        server.dispatcher = dispatcher()
        openShareShouldFail = true
        val r = resolver()
        val e = runCatching { r.resolve(restoredShareFile("co-t-2", "FOLDER1")) }.exceptionOrNull()
        assertThat(e).hasMessageThat().contains("分享打开失败")
    }

    @Test
    fun `track not found throws clear Chinese error`() = runTest {
        server.dispatcher = dispatcher()
        pageResponder = { _, _ -> listing(audio("co-t-1", "https://x/t1.m3u8")) }
        val r = resolver()
        val e = runCatching { r.resolve(restoredShareFile("co-missing", "FOLDER1")) }.exceptionOrNull()
        assertThat(e).hasMessageThat().contains("分享已失效")
    }

    @Test
    fun `cloud path regression uses getPlayUrl`() = runTest {
        val cloud = FakeCloud()
        val account = CloudAccount("acc1", "13800138000", "raw", 0, "u", 0)
        val r = DefaultPlaybackResolver(cloud, shareRepo(), FakeClock()) { account }
        val file = CloudFile(
            id = "fid-1", name = "a.mp3", isDir = false, size = 0, updatedAtMs = 0,
            thumbnailUrl = "", parentId = "/", audioExt = "mp3", source = FileSource.CLOUD139
        )
        val spec = r.resolve(file)
        assertThat(spec.uri).isEqualTo("https://cdn.example/fid-1.mp3")
        assertThat(spec.headers).containsEntry("UA", "x")
    }
}
