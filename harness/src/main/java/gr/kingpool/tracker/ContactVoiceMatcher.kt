package gr.kingpool.tracker

import java.text.Normalizer
import java.util.Locale

object ContactVoiceMatcher {
    const val MIN_SCORE = 60
    private val articles = setOf("τον", "την", "το", "στον", "στην", "στο", "του", "της")

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale("el", "GR"))
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isCallVerb(token: String): Boolean =
        token.startsWith("καλεσ") || token == "παρε" || token.startsWith("τηλεφων") ||
            token.startsWith("κλησ") || token == "κληση"

    fun isCallCommand(raw: String): Boolean =
        isCallVerb(normalize(raw).split(' ').firstOrNull().orEmpty())

    fun extractContactQuery(raw: String): String {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return ""
        var start = 0
        while (start < words.size) {
            val token = normalize(words[start])
            if (!isCallVerb(token) && token !in articles) break
            start += 1
        }
        var end = words.size
        while (end > start) {
            val token = normalize(words[end - 1])
            if (!token.startsWith("τηλεφων") && !token.startsWith("κλησ") && token != "κληση") break
            end -= 1
        }
        return words.subList(start, end).joinToString(" ").trim()
    }

    fun phoneticKey(value: String): String {
        val plain = normalize(value)
        val out = StringBuilder()
        for (ch in plain) {
            out.append(when (ch) {
                'α' -> "a"; 'β' -> "v"; 'γ' -> "g"; 'δ' -> "d"; 'ε' -> "e"; 'ζ' -> "z"
                'η', 'ι', 'υ' -> "i"; 'θ' -> "th"; 'κ' -> "k"; 'λ' -> "l"; 'μ' -> "m"
                'ν' -> "n"; 'ξ' -> "x"; 'ο', 'ω' -> "o"; 'π' -> "p"; 'ρ' -> "r"
                'σ', 'ς' -> "s"; 'τ' -> "t"; 'φ' -> "f"; 'χ' -> "ch"; 'ψ' -> "ps"
                else -> ch.toString()
            })
        }
        return out.toString()
            .replace('c', 'k').replace('q', 'k').replace('y', 'i')
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun scoreKeys(query: String, name: String): Int {
        if (query.isBlank() || name.isBlank()) return 0
        if (query == name) return 100
        if (name.startsWith(query) || query.startsWith(name)) return 85
        if (name.contains(query) || query.contains(name)) return 75
        val qTokens = query.split(' ').filter { it.length > 1 }
        val nTokens = name.split(' ').filter { it.length > 1 }
        if (qTokens.isNotEmpty() && qTokens.all { q -> nTokens.any { n -> n == q || n.startsWith(q) } }) return 65
        return 0
    }

    fun score(query: String, contactName: String): Int = scoreKeys(phoneticKey(query), phoneticKey(contactName))
}
