package dev.dawitsh.noroshi

import dev.dawitsh.noroshi.core.network.AsrDigest
import dev.dawitsh.noroshi.core.network.DigestChallenge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The expected values were computed outside Kotlin (Python's hashlib) with the formula of the
 * panel's js/base/utils.js, so they check the Kotlin port rather than repeat it.
 */
class AsrDigestTest {
    private val challenge = DigestChallenge(realm = "Highwmg", nonce = "12244742", qop = "auth")
    private val user = AsrDigest.encodeUriComponent("admin")
    private val pass = AsrDigest.encodeUriComponent("p@ss w0rd!")

    @Test
    fun credentialsAreUrlEncodedLikeTheLoginForm() {
        assertEquals("admin", user)
        assertEquals("p%40ss%20w0rd!", pass)
        assertEquals("a%20b%2Bc%2F%C3%A9~*'()", AsrDigest.encodeUriComponent("a b+c/é~*'()"))
    }

    @Test
    fun ha1() {
        assertEquals("dc375f97fa6a249efd3496c0f3886ab9", AsrDigest.ha1(user, "Highwmg", pass))
    }

    @Test
    fun loginResponseHashesProtectedCgi() {
        assertEquals("c173ee548be4dffc7849068f3ada100d", AsrDigest.loginResponse(user, pass, challenge, "0a4f3c1b2d5e6f70"))
    }

    @Test
    fun loginQueryKeepsThePanelsOrder() {
        assertEquals(
            "Action=Digest&username=admin&realm=Highwmg&nonce=12244742&response=R&qop=auth&cnonce=C&temp=asr",
            AsrDigest.loginQuery(user, challenge, "R", "C"),
        )
    }

    @Test
    fun authorizationHeaderForXmlCalls() {
        assertEquals(
            "Digest username=\"admin\", realm=\"Highwmg\", nonce=\"12244742\", uri=\"/cgi/xml_action.cgi\", " +
                "response=\"2a5c5a57bc61c6d81e8d871e41a1602f\", qop=auth, nc=00000002, cnonce=\"1122334455667788\"",
            AsrDigest.authorization(user, pass, challenge, "GET", 2, "1122334455667788"),
        )
        // The login request carries one too, with count 1.
        assertTrue(
            AsrDigest.authorization(user, pass, challenge, "GET", 1, "1122334455667788")
                .contains("response=\"272918574cd7f7437328fc2a76cb10ec\", qop=auth, nc=00000001")
        )
    }

    @Test
    fun postUsesItsOwnMethodAndUppercaseHexCount() {
        val header = AsrDigest.authorization(user, pass, challenge, "POST", 10, "8877665544332211")
        assertTrue(header, header.contains("response=\"c2140abf34753902322abf01d6b941ae\""))
        assertTrue(header, header.contains("nc=0000000A"))
    }

    @Test
    fun nonceCount() {
        assertEquals("00000001", AsrDigest.nonceCount(1))
        assertEquals("000000FF", AsrDigest.nonceCount(255))
        assertEquals("00010000", AsrDigest.nonceCount(65536))
    }

    @Test
    fun cnonceIsSixteenHexDigits() {
        val c = AsrDigest.newCnonce(Random(7), now = 1_764_000_000_000)
        assertEquals(16, c.length)
        assertTrue(c.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(c, AsrDigest.newCnonce(Random(7), now = 1_764_000_000_000))
    }

    @Test
    fun md5MatchesKnownValue() {
        assertEquals("21232f297a57a5a743894a0e4a801fc3", AsrDigest.md5Hex("admin"))
    }

    @Test
    fun parsesTheRoutersChallenge() {
        // As the Stellar 8400 sends it from GET /login.cgi.
        assertEquals(challenge, AsrDigest.parseChallenge("Digest realm=\"Highwmg\", nonce=\"12244742\", qop=\"auth\""))
        assertTrue(AsrDigest.isAsrChallenge("Digest realm=\"Highwmg\", nonce=\"1\", qop=\"auth\""))
    }

    @Test
    fun otherChallengesAreNotAsr() {
        assertNull(AsrDigest.parseChallenge(null))
        assertNull(AsrDigest.parseChallenge("Basic realm=\"router\""))
        assertFalse(AsrDigest.isAsrChallenge("Digest realm=\"Other\", nonce=\"1\", qop=\"auth\""))
        assertFalse(AsrDigest.isAsrChallenge(""))
    }
}
