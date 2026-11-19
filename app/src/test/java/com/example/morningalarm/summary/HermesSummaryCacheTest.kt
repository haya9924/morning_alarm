package com.example.morningalarm.summary

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HermesSummaryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun atomicallyReplacesExistingFile() {
        val cache = temporary.newFile("cache")
        cache.writeText("before")
        replaceCache(cache, "朝のまとめ".toByteArray(Charsets.UTF_8))
        assertEquals("朝のまとめ", cache.readText())
        assertEquals(listOf("cache"), temporary.root.list()!!.toList())
    }

    @Test fun propagatesFailedCommitAndRemovesTemporaryFile() {
        val target = temporary.newFolder("cache")
        val existing = File(target, "previous").apply { writeText("before") }
        assertThrows(IOException::class.java) { replaceCache(target, byteArrayOf(1)) }
        assertEquals("before", existing.readText())
        assertEquals(listOf("cache"), temporary.root.list()!!.toList())
    }
}
