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
}

