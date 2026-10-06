package com.robb3n.petrel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseExitIpTest {
    @Test fun successResponse() {
        val json = """{"status":"success","country":"美国","countryCode":"US","regionName":"加利福尼亚州","city":"洛杉矶",""" +
            """"isp":"NTT America, Inc.","as":"AS2914 NTT America, Inc.","mobile":false,"hosting":true,"query":"203.0.113.42"}"""
        assertEquals(
            ExitIpInfo("203.0.113.42", "US", "美国", "加利福尼亚州", "洛杉矶", "NTT America, Inc.", "AS2914", hosting = true, mobile = false),
            parseExitIp(json),
        )
    }

    @Test fun failuresAreNull() {
        assertNull(parseExitIp("""{"status":"fail","message":"reserved range","query":"10.0.0.1"}"""))
        assertNull(parseExitIp("""{"status":"success"}"""))
        assertNull(parseExitIp("not json"))
    }

    @Test fun missingAsIsEmpty() {
        val info = parseExitIp("""{"status":"success","query":"192.0.2.1","as":""}""")!!
        assertEquals("", info.asn)
    }
}
