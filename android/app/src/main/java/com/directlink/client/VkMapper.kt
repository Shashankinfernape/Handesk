package com.directlink.client

object VkMapper {
    // Basic mapping of uppercase chars to Virtual Key codes
    fun getVkCode(char: Char): Int {
        val upper = char.uppercaseChar()
        if (upper in 'A'..'Z') return upper.code
        if (upper in '0'..'9') return upper.code
        return when (char) {
            ' ' -> 0x20
            '\n' -> 0x0D
            '\b' -> 0x08
            '`', '~' -> 0xC0
            '-', '_' -> 0xBD
            '=', '+' -> 0xBB
            '[', '{' -> 0xDB
            ']', '}' -> 0xDD
            '\\', '|' -> 0xDC
            ';', ':' -> 0xBA
            '\'', '"' -> 0xDE
            ',', '<' -> 0xBC
            '.', '>' -> 0xBE
            '/', '?' -> 0xBF
            '!', '@', '#', '$', '%', '^', '&', '*', '(', ')' -> {
                // Number row shifted
                when (char) {
                    '!' -> '1'.code
                    '@' -> '2'.code
                    '#' -> '3'.code
                    '$' -> '4'.code
                    '%' -> '5'.code
                    '^' -> '6'.code
                    '&' -> '7'.code
                    '*' -> '8'.code
                    '(' -> '9'.code
                    ')' -> '0'.code
                    else -> 0
                }
            }
            else -> 0
        }
    }

    fun requiresShift(char: Char): Boolean {
        if (char in 'A'..'Z') return true
        return char in listOf('~', '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')')
    }
}
