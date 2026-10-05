package com.robb3n.petrel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactStateJsonTest {
    @Test fun loginUrlAndErrorAreRedacted() {
        val json = """{"vpn":"stopped","tailnet":"Stopped","tailnetIPs":[],""" +
            """"loginURL":"https://login.tailscale.com/a/abc","error":"parse config: yaml: line 3: password: \"s3cret\"","exit":"","groupsRev":0}"""
        val out = redactStateJson(json)
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("password"))
        assertEquals(
            """{"vpn":"stopped","tailnet":"Stopped","tailnetIPs":[],"loginURL":"<set>","error":"<set>","exit":"","groupsRev":0}""",
            out,
        )
    }

    @Test fun emptyFieldsStayEmpty() {
        val json = """{"vpn":"running","loginURL":"","error":""}"""
        assertEquals(json, redactStateJson(json))
    }
}

class TailnetHostnameTest {
    @Test fun modelIsNormalized() {
        assertEquals("pjd110-petrel", TailnetHostname.fromModel("PJD110"))
        assertEquals("pixel-8-pro-petrel", TailnetHostname.fromModel("Pixel 8 Pro"))
        assertEquals("sm-s9280-petrel", TailnetHostname.fromModel("SM-S9280"))
        assertEquals("a-b-petrel", TailnetHostname.fromModel("  --A__B--  "))
    }

    @Test fun emptyOrUnusableModelFallsBack() {
        assertEquals("android-petrel", TailnetHostname.fromModel(null))
        assertEquals("android-petrel", TailnetHostname.fromModel(""))
        assertEquals("android-petrel", TailnetHostname.fromModel("小米"))
    }

    @Test fun longModelIsTruncatedWithoutTrailingDash() {
        val name = TailnetHostname.fromModel("x".repeat(39) + " yyyy")
        assertEquals("x".repeat(39) + "-petrel", name)
    }
}
