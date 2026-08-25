package gr.kingpool.tracker

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

object ContactVoiceMatcher {
    const val MIN_SCORE = 68
    private const val MIN_TOKEN_SCORE = 68
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
            .replace('c', 'k').replace('q', 'k').replace('y', 'i').replace('w', 'v')
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun consonantSkeleton(token: String): String =
        token
            .replace("ph", "f")
            .replace("th", "t")
            .replace("ch", "h")
            .replace("x", "h")
            .replace("mp", "b")
            .replace("nt", "d")
            .replace("gk", "g")
            .replace("gg", "g")
            .replace('8', 't')
            .filter { it.isDigit() || it !in "aeiou" }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun similarityPercent(a: String, b: String): Int {
        val longest = max(a.length, b.length)
        if (longest == 0) return 100
        return ((longest - editDistance(a, b)) * 100) / longest
    }

    private fun tokenScore(queryToken: String, nameToken: String): Int {
        if (queryToken == nameToken) return 100
        if (queryToken.length >= 3 && (nameToken.startsWith(queryToken) || queryToken.startsWith(nameToken))) return 92
        if (queryToken.length >= 3 && (nameToken.contains(queryToken) || queryToken.contains(nameToken))) return 88
        val directSimilarity = similarityPercent(queryToken, nameToken)
        if (directSimilarity >= 84) return 86
        if (directSimilarity >= 72 && minOf(queryToken.length, nameToken.length) >= 5) return 76
        val querySkeleton = consonantSkeleton(queryToken)
        val nameSkeleton = consonantSkeleton(nameToken)
        if (querySkeleton.length >= 2 && querySkeleton == nameSkeleton) return 84
        if (querySkeleton.length >= 3 && nameSkeleton.length >= 3 && similarityPercent(querySkeleton, nameSkeleton) >= 75) return 72
        return 0
    }

    fun scoreKeys(query: String, name: String): Int {
        if (query.isBlank() || name.isBlank()) return 0
        if (query == name) return 100
        if (name.contains(query) || query.contains(name)) return 96
        val qTokens = query.split(' ').filter { it.length > 1 }
        val nTokens = name.split(' ').filter { it.length > 1 }
        if (qTokens.isEmpty() || nTokens.isEmpty()) return 0
        val bestScores = qTokens.map { q -> nTokens.maxOf { n -> tokenScore(q, n) } }
        if (bestScores.any { it < MIN_TOKEN_SCORE }) return 0
        return minOf(94, bestScores.sum() / bestScores.size)
    }

    fun score(query: String, contactName: String): Int = scoreKeys(phoneticKey(query), phoneticKey(contactName))
}
