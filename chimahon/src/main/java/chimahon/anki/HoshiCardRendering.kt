package chimahon.anki

import chimahon.Cloze
import hoshi.card.CardEnricher
import hoshi.card.CardEnrichment
import hoshi.card.HoshiCardFields

/**
 * One card's values applied around chimahon's marker renderer, without changing it:
 *
 * - hoshi-card fields ([CardEnrichment]): pack values, `{sentence-pitch}`, and dictionary
 *   fallbacks in chimahon markers ([HoshiPreset.markerFallbacks]);
 * - on the Hoshi note type, [overrides] for the few markers whose chimahon output differs from
 *   Hoshi Reader's, so both apps write the same field values.
 *
 * Use as `fill(render(protect(template)))`. Values are never re-parsed as markers.
 */
internal class HoshiCardRender(
    private val enrichment: CardEnrichment,
    private val overrides: Map<String, String> = emptyMap(),
) {
    fun protect(template: String): String {
        val protected = enrichment.protect(template)
        if (overrides.isEmpty()) return protected
        return MarkerToken.replace(protected) { match ->
            val marker = match.groupValues[1]
            if (marker in overrides) placeholder(marker) else match.value
        }
    }

    fun fill(rendered: String): String {
        val withOverrides = if (overrides.isEmpty() || PlaceholderOpen !in rendered) {
            rendered
        } else {
            overrides.entries.fold(rendered) { acc, (marker, value) -> acc.replace(placeholder(marker), value) }
        }
        return enrichment.fill(withOverrides)
    }

    private fun placeholder(marker: String) = "$PlaceholderOpen$marker$PlaceholderClose"

    companion object {
        // Private-use characters distinct from the ones CardEnrichment uses.
        private const val PlaceholderOpen = ''
        private const val PlaceholderClose = ''
        private val MarkerToken = Regex("""\{([^{}]+)\}""")

        val None = HoshiCardRender(CardEnrichment.Empty)
    }
}

internal object HoshiCardRendering {
    private const val TAG = "HoshiCardRendering"

    /**
     * The render layer for one card. [templates] are the field and tag templates; the enricher is
     * only asked when one of them references a hoshi-card field. Blocking: call off the main thread.
     */
    fun prepare(
        enricher: () -> CardEnricher,
        expression: String,
        reading: String,
        sentence: String,
        templates: Collection<String>,
        hoshiModel: Boolean,
        cloze: Cloze?,
    ): HoshiCardRender {
        val enrichment = if (templates.any(HoshiCardFields::isReferencedBy)) {
            try {
                enricher().enrich(expression, reading, sentence, templates)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "hoshi-card enrichment failed; using dictionary fallbacks", e)
                CardEnrichment(emptyMap(), HoshiPreset.markerFallbacks)
            }
        } else {
            CardEnrichment.Empty
        }
        val overrides = if (hoshiModel) hoshiReaderOverrides(expression, reading, cloze) else emptyMap()
        return HoshiCardRender(enrichment, overrides)
    }

    /** Markers whose chimahon output differs from Hoshi Reader's, as Hoshi Reader renders them. */
    fun hoshiReaderOverrides(expression: String, reading: String, cloze: Cloze?): Map<String, String> = mapOf(
        Marker.FURIGANA_PLAIN to furiganaPlain(expression, reading),
        Marker.SENTENCE_BOLD to sentenceBold(cloze),
    )

    /**
     * Hoshi Reader's cloze split (`AnkiHandlebarRenderer.clozeParts`): the [matched] text at
     * [offset] when it is really there, else the first occurrence of [matched], [expression] or
     * [reading] (a picked sentence may spell the word differently), else no body.
     */
    fun cloze(sentence: String, offset: Int, matched: String, expression: String, reading: String): Cloze {
        val bodyKana = AnkiCardCreator.calculateBodyKana(expression, reading)
        fun parts(prefix: String, body: String, suffix: String) = Cloze(sentence, prefix, body, bodyKana, suffix)
        if (matched.isBlank()) return parts(sentence, "", "")
        if (offset >= 0 &&
            offset + matched.length <= sentence.length &&
            sentence.regionMatches(offset, matched, 0, matched.length)
        ) {
            return parts(sentence.substring(0, offset), matched, sentence.substring(offset + matched.length))
        }
        val found = listOf(matched, expression, reading)
            .filter { it.isNotBlank() }
            .firstNotNullOfOrNull { text -> sentence.indexOf(text).takeIf { it >= 0 }?.let { it to text } }
            ?: return parts(sentence, "", "")
        val (start, body) = found
        return parts(sentence.substring(0, start), body, sentence.substring(start + body.length))
    }

    /**
     * Hoshi Reader's `{furigana-plain}` (popup.js `constructFuriganaPlain`): like chimahon's, but a
     * segment without furigana always ends in a space, also when the whole word is kana.
     */
    fun furiganaPlain(expression: String, reading: String): String =
        if (reading.isEmpty() || reading == expression) {
            "$expression "
        } else {
            AnkiCardCreator.buildFuriganaPlain(expression, reading)
        }

    /** Hoshi Reader's `{sentence}`: the mined word in bold, or the plain sentence without one. */
    fun sentenceBold(cloze: Cloze?): String {
        cloze ?: return ""
        if (cloze.body.isEmpty()) return AnkiCardCreator.escapeHtml(cloze.sentence)
        val escape = AnkiCardCreator::escapeHtml
        return "${escape(cloze.prefix)}<b>${escape(cloze.body)}</b>${escape(cloze.suffix)}"
    }
}
