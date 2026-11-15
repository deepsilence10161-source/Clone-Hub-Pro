package com.clonehub.advanced.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log reader the WebView sign-in gate depends on.
 *
 * Written because the gate itself got this wrong twice: the first version looked for the marker at the
 * start of a logcat line, but the tag comes first ("… I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_MARKER step
 * …"), so it found nothing and reported an empty map while the probe had answered every step (runs
 * 43690036509 and 43690755006). That is a harness defect of the kind that must not be left to a live
 * run to discover, so the rule is pinned here against real logcat lines.
 */
class SignInLogParsingTest {

    private val runPrefix = "WEBVIEW_SIGNIN_RUN "
    private val markerPrefix = "WEBVIEW_SIGNIN_MARKER "

    /** The production rule, kept in one place so a change to it fails this test. */
    private fun parse(log: String): List<Pair<Map<String, String>, Map<String, String>>> {
        val runs = ArrayList<Pair<Map<String, String>, Map<String, String>>>()
        var run: Map<String, String>? = null
        var steps = LinkedHashMap<String, String>()
        for (line in log.lineSequence()) {
            val runAt = line.indexOf(runPrefix)
            val markerAt = line.indexOf(markerPrefix)
            when {
                runAt >= 0 -> {
                    if (run != null) runs.add(run to steps)
                    run = line.substring(runAt + runPrefix.length).toMarkerMap()
                    steps = LinkedHashMap()
                }
                markerAt >= 0 && run != null -> {
                    val rest = line.substring(markerAt + markerPrefix.length)
                    val step = rest.substringBefore(' ')
                    steps[step] = rest.removePrefix(step).trim()
                }
            }
        }
        if (run != null) runs.add(run to steps)
        return runs
    }

    private fun String.toMarkerMap(): Map<String, String> =
        split(' ').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Real lines from run 43690755006, timestamps and thread ids included. */
    private val realLog = listOf(
        "11-15 07:47:17.150  3523  3523 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_RUN process=com.clonehub.pro.test pid=3523 kind=native",
        "11-15 07:47:17.257  3523  3523 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_MARKER settings js=true domStorage=true cookies=true",
        "11-15 07:47:17.429  3523  3523 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_MARKER page_finished url=https://example.com/ title=Example Domain",
        "11-15 07:47:17.692  3523  3523 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_MARKER verdict webview=true cookie_roundtrip=true gsi_loaded=true process=com.clonehub.pro.test",
        "11-15 07:47:18.000  3523  3523 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_DONE kind=native steps=9 elapsedMs=1542"
    ).joinToString("\n")

    @Test
    fun `a marker is found even though the tag precedes it on the line`() {
        val (run, steps) = parse(realLog).single()
        assertEquals("com.clonehub.pro.test", run?.get("process"))
        assertEquals("native", run?.get("kind"))
        assertEquals(3, steps.size)
        assertTrue(steps.getValue("page_finished").contains("title=Example Domain"))
        assertTrue(steps.getValue("verdict").contains("cookie_roundtrip=true"))
    }

    @Test
    fun `markers before the run line are ignored, so a previous process cannot answer`() {
        val stale =
            "11-15 07:40:00.000  1111  1111 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_MARKER verdict webview=true cookie_roundtrip=false"
        val (run, steps) = parse(stale + "\n" + realLog).single()
        assertEquals("native", run?.get("kind"))
        assertTrue(steps.getValue("verdict").contains("cookie_roundtrip=true"))
        assertEquals(3, steps.size)
    }

    @Test
    fun `several runs are returned in order, so the gate can pick the one it launched`() {
        // Measured twice: inside a clone the probe reported the process as com.clonehub.pro.test
        // (run 43694349589), and a class-loader guess then still reported 'native'
        // (run 43695212616). Environment-based identification is therefore not dependable, so the
        // reader returns every run in order and the gate - which knows which side it started after
        // clearing the buffer - picks by position.
        val twoRuns = realLog + "\n" +
            "11-15 08:40:50.000  4582  4582 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_RUN " +
            "process=com.clonehub.pro.test pid=4582 kind=native loader=dalvik.system.PathClassLoader" + "\n" +
            "11-15 08:40:50.500  4582  4582 I WEBVIEW_SIGNIN: WEBVIEW_SIGNIN_MARKER " +
            "page_finished url=https://example.com/ title=Example Domain"
        val runs = parse(twoRuns)
        assertEquals(2, runs.size)
        assertEquals("3523", runs[0].first["pid"])
        assertEquals("4582", runs[1].first["pid"])
        assertEquals(1, runs[1].second.size)
    }

    @Test
    fun `a probe that never ran yields no run line, which the gate must treat as not tested`() {
        assertTrue(parse("nothing here\n").isEmpty())
    }
}
