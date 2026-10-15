package com.lectorocr

enum class DataKind(val label: String) {
    CBU("CBU"),
    ALIAS("Alias de pago"),
    CUIT("CUIT / CUIL"),
    PHONE("Teléfono"),
    EMAIL("Correo"),
    LINK("Enlace"),
}

data class FoundData(val kind: DataKind, val value: String)

/**
 * Detecta datos útiles dentro de un texto: CBU, alias, CUIT/CUIL (con dígito verificador),
 * teléfonos argentinos, correos y enlaces. Código propio, sin librerías.
 */
object DataDetector {

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"')\]]+""")
    private val CBU = Regex("""(?<!\d)\d{22}(?!\d)""")
    private val CUIT = Regex("""(?<!\d)(20|23|24|25|26|27|30|33|34)[- ]?(\d{8})[- ]?(\d)(?!\d)""")
    private val ALIAS = Regex("""(?i)\balias\b\s*[:\-]?\s*([a-z0-9][a-z0-9.\-]{4,18}[a-z0-9])""")
    private val PHONE = Regex("""(?<![\w.])\+?\d[\d\s().\-]{7,18}\d(?!\w)""")

    fun detect(text: String): List<FoundData> {
        val found = LinkedHashMap<String, FoundData>()
        val masked = StringBuilder(text)

        fun mask(range: IntRange) {
            for (i in range) masked.setCharAt(i, ' ')
        }

        fun add(kind: DataKind, value: String, key: String = value) {
            found.putIfAbsent("${kind.name}|$key", FoundData(kind, value))
        }

        for (m in LINK.findAll(text)) {
            val v = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            add(DataKind.LINK, v)
            mask(m.range)
        }
        for (m in EMAIL.findAll(masked.toString())) {
            add(DataKind.EMAIL, m.value)
            mask(m.range)
        }
        for (m in CUIT.findAll(masked.toString())) {
            val (prefix, body, dv) = m.destructured
            if (cuitValid(prefix, body, dv)) {
                add(DataKind.CUIT, "$prefix-$body-$dv")
                mask(m.range)
            }
        }
        for (m in CBU.findAll(masked.toString())) {
            add(DataKind.CBU, m.value)
            mask(m.range)
        }
        for (m in ALIAS.findAll(masked.toString())) {
            val g = m.groups[1] ?: continue
            add(DataKind.ALIAS, g.value)
            mask(g.range)
        }
        for (m in PHONE.findAll(masked.toString())) {
            val raw = m.value.trim()
            val digits = raw.filter { it.isDigit() }
            if (phoneValid(raw.startsWith("+"), digits)) add(DataKind.PHONE, raw, digits)
        }

        return found.values.sortedBy { it.kind.ordinal }
    }

    /** Número para marcar: solo dígitos (y el + inicial si lo tenía). */
    fun dialable(value: String): String =
        (if (value.trim().startsWith("+")) "+" else "") + value.filter { it.isDigit() }

    private fun phoneValid(plus: Boolean, d: String): Boolean = when {
        plus -> d.length in 10..14
        d.length == 10 -> true
        d.length == 11 -> d[0] == '0'
        d.length == 12 -> d.startsWith("54")
        d.length == 13 -> d.startsWith("549")
        else -> false
    }

    /** Dígito verificador del CUIT/CUIL (módulo 11). */
    private fun cuitValid(prefix: String, body: String, dv: String): Boolean {
        val digits = prefix + body
        val weights = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)
        var sum = 0
        for (i in 0 until 10) sum += (digits[i] - '0') * weights[i]
        var check = 11 - (sum % 11)
        if (check == 11) check = 0
        if (check == 10) return false
        return check == dv[0] - '0'
    }
}
