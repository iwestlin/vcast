package com.viegg.vcast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskForwarderTest {
    @Test
    fun emptyKeywordMatchesNothing() {
        assertFalse(TaskForwarder.matchesKeyword("http://example.com/video.mp4", ""))
        assertFalse(TaskForwarder.matchesKeyword("http://example.com/video.mp4", "   "))
    }

    @Test
    fun wildcardMatchesEverything() {
        assertTrue(TaskForwarder.matchesKeyword("", "*"))
        assertTrue(TaskForwarder.matchesKeyword("http://example.com/video.mp4", "*"))
    }

    @Test
    fun regexMatchesSubstringIgnoringCase() {
        assertTrue(TaskForwarder.matchesKeyword("http://example.com/VIDEO.mp4", "video"))
        assertTrue(TaskForwarder.matchesKeyword("http://example.com/video.mp4", "example\\.com"))
        assertFalse(TaskForwarder.matchesKeyword("http://example.com/audio.mp3", "video"))
    }

    @Test
    fun regexIsCaseInsensitive() {
        assertTrue(TaskForwarder.matchesKeyword("http://example.com/Movie.MKV", "movie\\.mkv"))
    }
}
