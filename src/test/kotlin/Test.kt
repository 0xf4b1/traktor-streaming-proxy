import beatport.api.Utils.decode
import beatport.api.Utils.encode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class Test {

    @Test
    fun testEncodeDecode() {
        val test = "xEmR-uU_Gg"
        assertEquals(decode(encode(test)), test)
    }

    @Test
    fun testEncodeDecodeLeadingZeros() {
        var test = "0foobarbaz"
        assertEquals(decode(encode(test)), test)
        test = "00foobarba"
        assertEquals(decode(encode(test)), test)
        test = "0000foobar"
        assertEquals(decode(encode(test)), test)
        test = "0000000foo"
        assertEquals(decode(encode(test)), test)
    }
}

