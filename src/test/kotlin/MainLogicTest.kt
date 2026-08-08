import kotlin.test.Test
import kotlin.test.assertEquals

class MainLogicTest {

    @Test
    fun parseSearchEnabledSources_blankMeansAll() {
        assertEquals(listOf(null), parseSearchEnabledSources(""))
        assertEquals(listOf(null), parseSearchEnabledSources("   "))
        assertEquals(listOf(null), parseSearchEnabledSources(", ,"))
    }

    @Test
    fun parseSearchEnabledSources_trimsAndResolves() {
        assertEquals(listOf(allSources["youtube"]), parseSearchEnabledSources(" youtube "))
        assertEquals(
            listOf(allSources["youtube"], allSources["spotify"]),
            parseSearchEnabledSources("youtube, spotify")
        )
    }
}
