package beatport.api

import java.lang.Exception

object Utils {
    fun encode(code: String): Long {
        var res: Long = 0
        for (i in code.indices) {
            res = res shl 6
            res = if (code[i] in '0'..'9') {
                res or (code[i] - '0').toLong()
            } else if (code[i] in 'a'..'z') {
                res or (code[i] - 'a' + 10).toLong()
            } else if (code[i] in 'A'..'Z') {
                res or (code[i] - 'A' + 36).toLong()
            } else if (code[i] == '-') {
                res or 62
            } else if (code[i] == '_') {
                res or 63
            } else {
                throw Exception("Unmatched character: ${code[i]}")
            }
        }
        return res
    }

    fun decode(code: Long): String {
        var code = code
        var res = ""
        while (code > 0) {
            val cur = code and 63
            if (cur < 10) {
                res = cur.toString() + res
            } else if (cur < 36) {
                res = (cur - 10 + 'a'.code).toChar() + res
            } else if (cur < 62) {
                res = (cur - 36 + 'A'.code).toChar() + res
            } else if (cur.toInt() == 62) {
                res = "-$res"
            } else if (cur.toInt() == 63) {
                res = "_$res"
            }
            code = code shr 6
        }
        return res
    }
}