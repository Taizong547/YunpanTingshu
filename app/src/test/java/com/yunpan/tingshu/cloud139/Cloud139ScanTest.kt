package com.yunpan.tingshu.cloud139

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.data.model.CloudAccount
import com.yunpan.tingshu.data.model.ScanDepth
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class Cloud139ScanTest {

    private lateinit var server: MockWebServer
    private lateinit var account: CloudAccount

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        account = CloudAccount("1", "13800138000", "tok", 0, "x", 0)
    }
    @After fun tearDown() { server.shutdown() }

    private fun api(): Cloud139Api {
        val base = server.url("/")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val orig = chain.request().url
            val nu = base.newBuilder(orig.encodedPath)!!.encodedQuery(orig.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(nu).build())
        }.build()
        return Cloud139Api(client)
    }

    private fun listResp(items: List<Pair<String, String>>, isDir: Boolean): String {
        val arr = JSONArray()
        for ((id, name) in items) {
            val o = JSONObject()
            if (isDir) {
                o.put("fileId", id).put("name", name).put("type", "folder")
            } else {
                o.put("fileId", id).put("name", name).put("type", "file").put("size", 100)
            }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("data", JSONObject().put("items", arr)).toString()
    }

    @Test
    fun `depth counts`() = runBlocking {
        // 上面 installTree 写复杂了，这里直接用内联 dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) {
                    return MockResponse().setBody(
                        JSONObject().put("success", true).put("data", JSONObject().put("routePolicyList",
                            JSONArray().put(JSONObject().put("modName","personal").put("httpsUrl", server.url("/").toString().trimEnd('/'))))).toString())
                }
                val body = JSONObject(req.body.readUtf8())
                val pid = body.getString("parentFileId")
                val items = JSONArray()
                when (pid) {
                    "/" -> {
                        items.put(folder("folderA", "2026老梁精选"))
                        items.put(audio("root1", "根说明.mp3"))
                    }
                    "folderA" -> {
                        items.put(folder("folderB", "out concat"))
                        items.put(audio("a1", "A1.mp3"))
                    }
                    "folderB" -> {
                        items.put(audio("b1", "B1.mp3"))
                        items.put(audio("b2", "A2.mp3"))
                    }
                }
                return MockResponse().setBody(
                    JSONObject().put("success", true).put("data", JSONObject().put("items", items)).toString())
            }
        }
        val api = api()
        assertThat(api.scanAudio(account, "/", ScanDepth.CURRENT, null).map { it.file.name })
            .containsExactly("根说明.mp3")
        assertThat(api.scanAudio(account, "/", ScanDepth.LEVEL1, null).map { it.file.name })
            .containsExactly("根说明.mp3", "A1.mp3")
        val all = api.scanAudio(account, "/", ScanDepth.ALL, null)
        assertThat(all.map { it.file.name }).containsExactly("根说明.mp3", "A1.mp3", "B1.mp3", "A2.mp3")
        // albumPath 分组：folderA 的音频 albumPath=[2026老梁精选]，folderB 的=[2026老梁精选, out concat]
        val b = all.first { it.file.name == "B1.mp3" }
        assertThat(b.albumPath).containsExactly("2026老梁精选", "out concat")
        // 组内自然排序：A2 在 B1 前（数字自然序）
        val group = all.filter { it.file.name.startsWith("A") || it.file.name.startsWith("B") }
        assertThat(group.map { it.file.name }).containsExactly("A1.mp3", "A2.mp3", "B1.mp3").inOrder()
    }

    @Test
    fun `cancellation stops early`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) {
                    return MockResponse().setBody(
                        JSONObject().put("success", true).put("data", JSONObject().put("routePolicyList",
                            JSONArray().put(JSONObject().put("modName","personal").put("httpsUrl", server.url("/").toString().trimEnd('/'))))).toString())
                }
                val items = JSONArray()
                // 每一层都返回一个子文件夹 + 一个音频，制造递归（受 MAX_FOLDERS 保护）
                items.put(folder("next", "sub"))
                items.put(audio("a", "track.mp3"))
                return MockResponse().setBody(
                    JSONObject().put("success", true).put("data", JSONObject().put("items", items)).toString())
            }
        }
        val api = api()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            api.scanAudio(account, "/", ScanDepth.ALL, null)
        }
        kotlinx.coroutines.delay(500)
        job.cancel()
        job.join()
        assertThat(job.isCancelled).isTrue()
    }

    private fun folder(id: String, name: String) =
        JSONObject().put("fileId", id).put("name", name).put("type", "folder")
    private fun audio(id: String, name: String) =
        JSONObject().put("fileId", id).put("name", name).put("type", "file").put("size", 100)
}
