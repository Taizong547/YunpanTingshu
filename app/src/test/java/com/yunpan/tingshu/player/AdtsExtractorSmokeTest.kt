package com.yunpan.tingshu.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.AdtsExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冒烟测试：验证 HLS 回归测试用的自制 ADTS AAC 分片能被 media3 AdtsExtractor
 * 真正解析出音频样本（master 声明 CODECS="mp4a.40.2" 时 HLS 使用 AdtsExtractor）。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdtsExtractorSmokeTest {

    @Test
    fun `自制ADTS分片可被AdtsExtractor提取出音频样本`() {
        val data = aacSegment()
        val extractor = AdtsExtractor()
        val output = RecordingOutput()
        extractor.init(output)

        val positionHolder = PositionHolder()
        val input = DefaultExtractorInput(MemoryDataReader(data), 0, data.size.toLong())
        var loop = 0
        while (loop++ < 200) {
            if (extractor.read(input, positionHolder) == Extractor.RESULT_END_OF_INPUT) break
        }
        assertThat(output.format).isNotNull()
        assertThat(output.format?.sampleMimeType).isEqualTo("audio/mp4a-latm")
        assertThat(output.sampleCount).isGreaterThan(0)
    }

    // ---------- extractor 基础设施 ----------

    private class RecordingOutput : ExtractorOutput {
        var format: Format? = null
        var sampleCount = 0
        override fun track(id: Int, type: Int): TrackOutput = RecordingTrackOutput(this)
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    private class RecordingTrackOutput(private val owner: RecordingOutput) : TrackOutput {
        override fun format(format: Format) { owner.format = format }
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, dataPart: Int): Int {
            val buf = ByteArray(length)
            return input.read(buf, 0, length)
        }
        override fun sampleData(input: ParsableByteArray, length: Int, dataPart: Int) { owner.sampleCount++ }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, encryptionData: TrackOutput.CryptoData?) {
            owner.sampleCount++
        }
    }

    private class MemoryDataReader(private val data: ByteArray) : DataReader {
        private var pos = 0
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, target, offset, n)
            pos += n
            return n
        }
    }

    // ---------- ADTS AAC 分片生成（与 HlsLocalPlaybackContinuityTest 相同） ----------

    private fun aacSegment(): ByteArray {
        val frame = adtsFrame()
        return frame + frame + frame + frame
    }

    private fun adtsFrame(): ByteArray {
        // FF F1: sync + MPEG-4 + layer0 + no CRC
        // 51: profile=01(LC) + sf_index=0100(44100) + channel_config=001(mono)
        // 80 0C FF FC: frame_length=103 + buffer_fullness
        val header = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x51.toByte(), 0x80.toByte(),
            0x0C.toByte(), 0xFF.toByte(), 0xFC.toByte()
        )
        return header + ByteArray(96)
    }
}
