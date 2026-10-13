package com.yunpan.tingshu.data.cover

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

/** 极简 Cloud139 fake。 */
private class FakeCloud(
    private val listing: List<CloudFile>,
    private val dlUrl: String = "https://dl.example/img.jpg"
) : Cloud139Repository {
    override suspend fun listFolder(account: CloudAccount, parentId: String, cursor: String): ListPage =
        ListPage(listing, "")
    override suspend fun listAllAudio(account: CloudAccount, folderId: String): List<CloudFile> = emptyList()
    override suspend fun getPlayUrl(account: CloudAccount, fileId: String): String = dlUrl
    override fun streamHeaders(): Map<String, String> = mapOf("UA" to "cloud-ua", "Referer" to "https://yun.139.com/")
    override suspend fun scanAudio(
        account: CloudAccount, rootFolderId: String, depth: ScanDepth,
        progress: ((ScanProgress) -> Unit)?
    ): List<ScannedTrack> = emptyList()
}

class RealAutoCoverSourceTest {

    private lateinit var server: MockWebServer
    private var listingResp: String = "{}"

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() { server.shutdown() }

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

    private fun dispatch(): Dispatcher = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            val enc = req.body.readUtf8().trim()
            val plain = try { OutlinkCrypto.decrypt(enc) } catch (_: Exception) { enc }
            val resp = when {
                (req.path ?: "").contains("getOutLinkGeneral") ->
                    JSONObject().put("resultCode", "0").put("data", JSONObject()).toString()
                else -> listingResp
            }
            return MockResponse().setBody(OutlinkCrypto.encrypt(resp))
        }
    }

    private fun shareFile(parentToken: String) = CloudFile(
        id = "co-audio", name = "第1集.mp3", isDir = false, size = 0, updatedAtMs = 0,
        thumbnailUrl = "", parentId = parentToken, audioExt = "mp3", source = FileSource.SHARE,
        parentToken = parentToken, shareLinkId = "LINK1", sharePasswd = ""
    )

    private fun listingWithImages(vararg images: Pair<String, String>): String {
        val arr = JSONArray()
        for ((name, thumb) in images) {
            arr.put(JSONObject().put("coID", "img").put("coName", name).put("coType", 1)
                .put("coSuffix", "jpg").put("thumbnailURL", thumb))
        }
        return JSONObject().put("resultCode", "0")
            .put("data", JSONObject().put("nodNum", arr.length()).put("caLst", JSONArray()).put("coLst", arr))
            .toString()
    }

    @Test fun `share returns coType1 thumbnail url`() = runTest {
        server.dispatcher = dispatch()
        listingResp = listingWithImages("bg1.jpg" to "https://img/1.jpg", "cover.jpg" to "https://img/cover.jpg")
        val src = RealAutoCoverSource(FakeCloud(emptyList()), shareRepo(), { null })
        val ref = src.autoFor(shareFile("FOLDER1"))
        assertThat(ref).isNotNull()
        assertThat(ref!!.headers).isEmpty()
        // cover.jpg 优先
        assertThat(ref.url).isEqualTo("https://img/cover.jpg")
    }

    @Test fun `share falls back to first image when no cover-named`() = runTest {
        server.dispatcher = dispatch()
        listingResp = listingWithImages("photo.jpg" to "https://img/photo.jpg")
        val src = RealAutoCoverSource(FakeCloud(emptyList()), shareRepo(), { null })
        assertThat(src.autoFor(shareFile("FOLDER1"))!!.url).isEqualTo("https://img/photo.jpg")
    }

    @Test fun `cloud uses getDownloadUrl with stream headers`() = runTest {
        val img = CloudFile("img1", "backdrop.jpg", false, 0, 0, "", "FOLDER1", "jpg", FileSource.CLOUD139, parentToken = "FOLDER1")
        val audio = CloudFile("a1", "ep.mp3", false, 0, 0, "", "FOLDER1", "mp3", FileSource.CLOUD139, parentToken = "FOLDER1")
        val src = RealAutoCoverSource(FakeCloud(listOf(audio, img)), shareRepo(), { CloudAccount("a", "p", "t", 0, "n", 0) })
        val ref = src.autoFor(audio)
        assertThat(ref!!.url).isEqualTo("https://dl.example/img.jpg")
        assertThat(ref.headers).containsEntry("UA", "cloud-ua")
    }

    @Test fun `missing parentToken returns null`() = runTest {
        val src = RealAutoCoverSource(FakeCloud(emptyList()), shareRepo(), { null })
        val file = shareFile("")
        assertThat(src.autoFor(file)).isNull()
    }

    @Test fun `network failure falls back to null`() = runTest {
        server.shutdown() // 直接断网 → 抛异常被吞
        val src = RealAutoCoverSource(FakeCloud(emptyList()), shareRepo(), { null })
        assertThat(src.autoFor(shareFile("FOLDER1"))).isNull()
    }
}
