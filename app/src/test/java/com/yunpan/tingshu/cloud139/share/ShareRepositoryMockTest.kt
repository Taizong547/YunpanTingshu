package com.yunpan.tingshu.cloud139.share

import com.google.common.truth.Truth.assertThat
import com.yunpan.tingshu.Golden
import com.yunpan.tingshu.cloud139.Cloud139Signer
import com.yunpan.tingshu.data.model.ScanDepth
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class ShareRepositoryMockTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun repo(): ShareRepository {
        val base = server.url("/")
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val orig = chain.request().url
                val nu = base.newBuilder(orig.encodedPath)!!.encodedQuery(orig.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(nu).build())
            }.build()
        return ShareRepository(client)
    }

    private fun dispatch(handler: (String, RecordedRequest) -> String): Dispatcher = object : Dispatcher() {
        override fun dispatch(req: RecordedRequest): MockResponse {
            // 1) 解密请求体
            val encBody = req.body.readUtf8().trim()
            val plain = OutlinkCrypto.decrypt(encBody)
            // 2) 校验 mcloud-sign
            val signHeader = req.getHeader("mcloud-sign")!!
            val parts = signHeader.split(",")
            assertThat(parts).hasSize(3)
            val recomputed = Cloud139Signer.sign(plain, parts[0], parts[1])
            assertThat(recomputed).isEqualTo(parts[2])
            // 3) 选择响应（明文 JSON），服务端再加密
            val respPlain = handler(plain, req)
            val encResp = OutlinkCrypto.encrypt(respPlain)
            return MockResponse()
                .setHeader("Content-Type", "application/json;charset=UTF-8")
                .setBody(encResp)
        }
    }

    @Test
    fun `first list uses pCaID root and audio maps to hls directUrl`() = kotlinx.coroutines.runBlocking {
        val seenPca = ArrayList<String>()
        server.dispatcher = dispatch { plain, req ->
            val pca = JSONObject(plain).getJSONObject("getOutLinkInfoReq").getString("pCaID")
            seenPca.add(pca)
            when (pca) {
                "root" -> Golden.read("share/00_bootstrap_root.resp.json")
                else -> Golden.read("share/02_getOutLinkInfoV6_root.resp.json")
            }
        }
        val listing = repo().listShareFolder("2xTrMfreXj5lq", "root")
        assertThat(seenPca[0]).isEqualTo("root")
        assertThat(listing.items.first().isDir).isTrue()
        // 列子目录
        val subToken = listing.items.first().childToken!!
        val sub = repo().listShareFolder("2xTrMfreXj5lq", subToken)
        assertThat(seenPca[1]).isEqualTo(subToken)
        val audio = sub.items.first { it.isAudio }
        assertThat(audio.isHls).isTrue()
        assertThat(audio.directUrl).contains(".m3u8")
    }

    @Test
    fun `recursive scan collects audio across levels`() = kotlinx.coroutines.test.runTest {
        server.dispatcher = dispatch { plain, req ->
            val pca = JSONObject(plain).getJSONObject("getOutLinkInfoReq").getString("pCaID")
            when {
                pca == "root" -> Golden.read("share/00_bootstrap_root.resp.json")        // 1 folder, 0 audio
                pca.startsWith("FiJFJg") -> Golden.read("share/02_getOutLinkInfoV6_root.resp.json") // 1 folder + 38 audio
                else -> Golden.read("share/02b_getOutLinkInfoV6_subfolder.resp.json") // videos, 0 audio
            }
        }
        val all = repo().scanShareAudio("2xTrMfreXj5lq", "", ScanDepth.ALL, null)
        assertThat(all.size).isEqualTo(38)
        // albumPath 含根目录名
        assertThat(all.first().albumPath).contains("2026老梁精选")
    }

    @Test
    fun `CURRENT depth does not descend`() = kotlinx.coroutines.test.runTest {
        server.dispatcher = dispatch { plain, req ->
            val pca = JSONObject(plain).getJSONObject("getOutLinkInfoReq").getString("pCaID")
            if (pca == "root") Golden.read("share/02_getOutLinkInfoV6_root.resp.json")
            else Golden.read("share/02b_getOutLinkInfoV6_subfolder.resp.json")
        }
        val current = repo().scanShareAudio("2xTrMfreXj5lq", "", ScanDepth.CURRENT, null)
        // root 直接就是 38 个音频
        assertThat(current.size).isEqualTo(38)
    }
}
