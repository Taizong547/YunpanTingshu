package com.yunpan.tingshu.cloud139

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import com.yunpan.tingshu.data.model.CloudAccount
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class Cloud139ApiMockTest {

    private lateinit var server: MockWebServer
    private lateinit var account: CloudAccount

    private val rawToken = "WDoxMzgwMDEzODAwMDphfGJ8Y3wxODk5OTk5OTk5OTk5fHRhaWw="

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        account = CloudAccount(
            id = "13800138000", phone = "13800138000", rawToken = rawToken,
            expireAtMs = 0, displayName = "13800138000", addedAtMs = 0
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun client(): OkHttpClient {
        val base = server.url("/")
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val orig = chain.request().url
                val nu = base.newBuilder(orig.encodedPath)!!.encodedQuery(orig.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(nu).build())
            }
            .build()
    }

    private fun newApi() = Cloud139Api(client())

    private fun assertSignValid(req: RecordedRequest, body: String) {
        val signHeader = req.getHeader("mcloud-sign") ?: throw AssertionError("no mcloud-sign")
        val parts = signHeader.split(",")
        assertThat(parts).hasSize(3)
        val (ts, rand, sign) = parts
        assertThat(Cloud139Signer.sign(body, ts, rand)).isEqualTo(sign)
    }

    private fun routeOk(): MockResponse = MockResponse().setBody(Golden.read("samples/route_orchestration.json"))

    @Test
    fun `route request has route headers and correct body and sign`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) {
                    assertThat(req.getHeader("mcloud-route")).isNull()
                    assertThat(req.getHeader("x-yun-client-info")).isNull()
                    assertThat(req.getHeader("Caller")).isNull()
                    assertThat(req.getHeader("Authorization")).isEqualTo("Basic $rawToken")
                    assertThat(req.getHeader("mcloud-sign")).matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},[A-Za-z0-9]{16},[A-F0-9]{32}$")
                    val body = req.body.readUtf8()
                    assertSignValid(req, body)
                    val bodyJson = JSONObject(body)
                    assertThat(bodyJson.getJSONObject("userInfo").getString("accountName")).isEqualTo("13800138000")
                    return routeOk()
                }
                if (req.path!!.endsWith("/file/list")) {
                    return MockResponse().setBody(Golden.read("samples/list_items.json"))
                }
                throw AssertionError("unexpected path ${req.path}")
            }
        }
        val page = newApi().listFolder(account, "/", "")
        assertThat(page.items).isNotNull()
    }

    @Test
    fun `list request has non-route headers and parses recorded json`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) return routeOk()
                if (req.path!!.endsWith("/file/list")) {
                    assertThat(req.getHeader("Caller")).isEqualTo("web")
                    assertThat(req.getHeader("mcloud-route")).isEqualTo("001")
                    assertThat(req.getHeader("x-yun-client-info")).isEqualTo(Cloud139Headers.CLIENT_INFO)
                    assertThat(req.getHeader("Referer")).isEqualTo("https://yun.139.com/w/")
                    assertThat(req.getHeader("Origin")).isEqualTo("https://yun.139.com")
                    assertThat(req.getHeader("User-Agent")).isEqualTo(Cloud139Headers.USER_AGENT)
                    assertSignValid(req, req.body.readUtf8().also { body ->
                        val b = JSONObject(body)
                        assertThat(b.getString("parentFileId")).isEqualTo("/")
                        assertThat(b.getJSONObject("pageInfo").getString("pageCursor")).isEqualTo("")
                    })
                    return MockResponse().setBody(Golden.read("samples/list_items.json"))
                }
                throw AssertionError("unexpected ${req.path}")
            }
        }
        val page = newApi().listFolder(account, "/", "")
        assertThat(page.items).hasSize(3)
        assertThat(page.items[1].name).isEqualTo("第一章 红岸往事.mp3")
    }

    @Test
    fun `getDownloadUrl returns cdnUrl`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) return routeOk()
                if (req.path!!.endsWith("/file/getDownloadUrl")) {
                    val body = JSONObject(req.body.readUtf8())
                    assertThat(body.getString("fileId")).isEqualTo("audio-001")
                    return MockResponse().setBody(Golden.read("samples/download_cdn.json"))
                }
                throw AssertionError("unexpected ${req.path}")
            }
        }
        val url = newApi().getPlayUrl(account, "audio-001")
        assertThat(url).isEqualTo("https://cdn.example.com/dl/audio-001.mp3?auth=abc")
    }

    @Test
    fun `error code 9000 throws`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) return routeOk()
                if (req.path!!.endsWith("/file/list")) {
                    return MockResponse().setBody(Golden.read("samples/error_9000.json"))
                }
                throw AssertionError("unexpected ${req.path}")
            }
        }
        try {
            newApi().listFolder(account, "/", "")
            throw AssertionError("should throw on 9000")
        } catch (e: Exception) {
            assertThat(e.message).contains("系统繁忙")
        }
    }

    @Test
    fun `404 route-not-found falls back to hcy-orchestration candidate`() = runBlocking {
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) {
                    return MockResponse().setBody(Golden.read("samples/route_hcy.json"))
                }
                if (req.path!!.endsWith("/file/list")) {
                    calls++
                    return if (req.path!!.contains("/hcy/")) {
                        MockResponse().setResponseCode(404).setBody(Golden.read("samples/error_route_not_found.json"))
                    } else {
                        MockResponse().setBody(Golden.read("samples/list_items.json"))
                    }
                }
                throw AssertionError("unexpected ${req.path}")
            }
        }
        val page = newApi().listFolder(account, "/", "")
        assertThat(calls).isEqualTo(2)
        assertThat(page.items).hasSize(3)
    }

    @Test
    fun `homepage list failure falls back to old getDisk`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) return routeOk()
                if (req.path!!.endsWith("/file/list")) {
                    return MockResponse().setResponseCode(404).setBody(Golden.read("samples/error_route_not_found.json"))
                }
                if (req.path!!.endsWith("/getDisk")) {
                    val body = JSONObject(req.body.readUtf8())
                    assertThat(body.getString("catalogID")).isEqualTo("root")
                    return MockResponse().setBody(Golden.read("samples/list_content_list_old.json"))
                }
                throw AssertionError("unexpected ${req.path}")
            }
        }
        val page = newApi().listFolder(account, "/", "")
        assertThat(page.items).hasSize(2)
        assertThat(page.items[0].name).isEqualTo("射雕英雄传 第01回.m4b")
    }

    @Test
    fun `pagination two pages aggregated in listAllAudio`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse {
                if (req.path!!.endsWith("/user/route/qryRoutePolicy")) return routeOk()
                if (req.path!!.endsWith("/file/list")) {
                    val body = JSONObject(req.body.readUtf8())
                    val cursor = body.getJSONObject("pageInfo").getString("pageCursor")
                    return if (cursor.isEmpty()) MockResponse().setBody(Golden.read("samples/list_page1.json"))
                    else MockResponse().setBody(Golden.read("samples/list_page2.json"))
                }
                throw AssertionError("unexpected ${req.path}")
            }
        }
        val all = newApi().listAllAudio(account, "/")
        assertThat(all.map { it.name }).containsExactly("第一页曲目.mp3", "第二页曲目.m4a")
        Unit
    }
}
