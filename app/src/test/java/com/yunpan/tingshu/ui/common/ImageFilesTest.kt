package com.yunpan.tingshu.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageFilesTest {
    @Test fun `recognizes image extensions`() {
        assertThat(ImageFiles.isImageName("封面.JPG")).isTrue()
        assertThat(ImageFiles.isImageName("a.png")).isTrue()
        assertThat(ImageFiles.isImageName("cover.webp")).isTrue()
    }
    @Test fun `rejects audio and other`() {
        assertThat(ImageFiles.isImageName("第1集.mp3")).isFalse()
        assertThat(ImageFiles.isImageName("notes.txt")).isFalse()
        assertThat(ImageFiles.isImageName("noext")).isFalse()
    }
    @Test fun `filter list`() {
        assertThat(ImageFiles.filter(listOf("a.mp3", "封面.jpg", "b.txt", "pic.png")))
            .containsExactly("封面.jpg", "pic.png").inOrder()
    }
}
