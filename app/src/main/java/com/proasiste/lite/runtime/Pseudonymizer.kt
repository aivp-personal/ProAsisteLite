package com.proasiste.lite.runtime

/**
 * Replaces personal data with consistent labels such as [PERSONA_1] or [RUT_1].
 * Fixed rules catch structured identifiers exactly; Gemini Nano adds names and addresses, which rules can't see.
 * Anything either finds is masked. Masked text is still personal data in law; this lowers exposure, it doesn't anonymise.
 */
class Pseudonymizer(private val nano: NanoAnswerer) {
    class Result(val text: String, val replacements: Map<String, String>, val nanoChecked: Boolean)

    suspend fun mask(text: String): Result {
        val found = linkedMapOf<String, String>() // original -> kind
        RULES.forEach { (kind, regex, valid) -> regex.findAll(text).map { it.value.trim() }.filter(valid).forEach { found.putIfAbsent(it, kind) } }
        val nanoChecked = runCatching {
            nano.generate(NAMES_PROMPT + text, 400).lines().mapNotNull { line ->
                val parts = line.split('|', limit = 2).map { it.trim() }
                val kind = when (parts.firstOrNull()?.uppercase()) { "PERSONA" -> "PERSONA"; "DIRECCION" -> "DIRECCION"; else -> null }
                // Keep only spans that really occur in the text, so the model can't invent replacements.
                if (kind != null && parts.size == 2 && parts[1].length >= 3 && text.contains(parts[1])) parts[1] to kind else null
            }.forEach { (value, kind) -> found.putIfAbsent(value, kind) }
        }.isSuccess
        val counters = mutableMapOf<String, Int>()
        val labels = found.mapValues { (_, kind) -> "[${kind}_${counters.merge(kind, 1, Int::plus)!!}]" }
        // Longest first, so "Juan Pérez Soto" is replaced before "Juan Pérez".
        var out = text
        labels.keys.sortedByDescending { it.length }.forEach { out = out.replace(it, labels.getValue(it)) }
        return Result(out, labels, nanoChecked)
    }

    private companion object {
        val NAMES_PROMPT = """
            List every personal name of a real person and every street address in the text below.
            Write one per line as TYPE|exact text, where TYPE is PERSONA or DIRECCION, copying the text exactly as written.
            Do not list companies, products, laws or places that are not street addresses. If there are none, write NONE.

            Text:
        """.trimIndent() + "\n"

        fun rutValid(raw: String): Boolean {
            val clean = raw.replace(".", "").replace("-", "").uppercase()
            if (clean.length < 8) return false
            val body = clean.dropLast(1).toLongOrNull() ?: return false
            var sum = 0L; var factor = 2; var n = body
            while (n > 0) { sum += (n % 10) * factor; n /= 10; factor = if (factor == 7) 2 else factor + 1 }
            val check = when (val r = 11 - (sum % 11).toInt()) { 11 -> '0'; 10 -> 'K'; else -> ('0' + r) }
            return clean.last() == check
        }

        fun luhn(raw: String): Boolean {
            val digits = raw.filter { it.isDigit() }
            if (digits.length !in 13..19) return false
            return digits.reversed().mapIndexed { i, c -> val d = c - '0'; if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d }.sum() % 10 == 0
        }

        /** Order matters: earlier kinds win when the same text matches twice. */
        val RULES: List<Triple<String, Regex, (String) -> Boolean>> = listOf(
            Triple("EMAIL", Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"), { true }),
            Triple("RUT", Regex("\\b\\d{1,2}\\.?\\d{3}\\.?\\d{3}-[\\dkK]\\b"), ::rutValid),
            Triple("TARJETA", Regex("\\b(?:\\d[ -]?){12,18}\\d\\b"), ::luhn),
            Triple("IBAN", Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b"), { true }),
            Triple("TELEFONO", Regex("(?:\\+\\d{1,3}[ -]?)?(?:\\(?\\d{1,3}\\)?[ -]?)?(?:\\d{4}[ -]?\\d{4}|\\d{3}[ -]\\d{3}[ -]\\d{4})\\b"), { it.count(Char::isDigit) >= 8 }),
            Triple("CUENTA", Regex("\\b\\d{8,}\\b"), { true }),
        )
    }
}
