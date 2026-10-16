package chimahon.anki

import hoshi.card.HoshiCardFields
import hoshi.card.notetype.HoshiNoteType
import org.json.JSONObject

/**
 * The Hoshi note type from hoshi-card, the same one Hoshi Reader mines into. Its card design and
 * default mapping come from the library; the mapping uses Hoshi Reader's handlebar names, which
 * [translate] turns into chimahon markers that render the same values.
 */
object HoshiPreset {
    const val MODEL_NAME = HoshiNoteType.Name
    const val DEFAULT_TAGS = HoshiNoteType.DefaultTag
    const val DEFAULT_DECK_NAME = LapisPreset.DEFAULT_DECK_NAME

    val fields: List<String> = HoshiNoteType.Fields

    /**
     * Hoshi Reader handlebar -> chimahon template with the same value on a Hoshi card. Covers every
     * handlebar of [HoshiNoteType.DefaultFieldMapping] and [HoshiCardFields.DictionaryFallbacks].
     */
    internal val handlebarTranslation: Map<String, String> = mapOf(
        "{expression}" to "{${Marker.EXPRESSION}}",
        "{reading}" to "{${Marker.READING}}",
        "{furigana-plain}" to "{${Marker.FURIGANA_PLAIN}}",
        // Hoshi Reader's {sentence} is the sentence with the mined word in bold.
        "{sentence}" to "{${Marker.SENTENCE_BOLD}}",
        "{cloze-prefix}" to "{${Marker.CLOZE_PREFIX}}",
        "{cloze-body}" to "{${Marker.CLOZE_BODY}}",
        "{cloze-suffix}" to "{${Marker.CLOZE_SUFFIX}}",
        "{document-title}" to "{${Marker.MEDIA_NAME}}",
        "{image}" to "{${Marker.SCREENSHOT}}",
        "{audio}" to "{${Marker.AUDIO}}",
        "{sentence-audio}" to "{${Marker.SENTENCE_AUDIO}}",
        "{glossary-first}" to "{${Marker.GLOSSARY_FIRST}}",
        "{glossary-first-dictionary}" to "{${Marker.GLOSSARY_FIRST_DICTIONARY}}",
        "{pitch-accent-graphs}" to "{${Marker.PITCH_ACCENT_GRAPHS}}",
        "{frequency-harmonic-rank}" to "{${Marker.FREQUENCY_HARMONIC_RANK}}",
    ) + HoshiCardFields.All.associate { "{$it}" to "{$it}" }

    private val handlebar = Regex("""\{[^{}]*\}""")

    /** [template] with Hoshi Reader handlebars replaced by chimahon markers; unknown ones are kept. */
    fun translate(template: String): String =
        handlebar.replace(template) { match -> handlebarTranslation[match.value] ?: match.value }

    /** What a hoshi-card field renders without a pack value, in chimahon markers. */
    val markerFallbacks: Map<String, String> =
        HoshiCardFields.DictionaryFallbacks.mapValues { (_, template) -> translate(template) }

    /** Hoshi Reader's default mapping in chimahon markers, in note-type field order. */
    val defaultFieldMap: Map<String, String> = LinkedHashMap<String, String>().apply {
        for (field in HoshiNoteType.Fields) {
            HoshiNoteType.DefaultFieldMapping[field]?.let { put(field, translate(it)) }
        }
    }

    val defaultFieldMapJson: String
        get() = JSONObject(defaultFieldMap).toString()

    /** The hoshi-card markers, in field-picker order. */
    val markers: List<String> = HoshiCardFields.All

    /** Field-picker labels for [markers]. */
    val markerLabels: Map<String, String> = mapOf(
        HoshiCardFields.Definition to "Definition",
        HoshiCardFields.DefinitionSource to "Definition Source",
        HoshiCardFields.PitchGraph to "Pitch Graph",
        HoshiCardFields.FrequencyRank to "Frequency Rank",
        HoshiCardFields.ExampleSentences to "Example Sentences",
        HoshiCardFields.SentencePitch to "Sentence Pitch",
    )

    fun isHoshiModel(name: String): Boolean = name.trim().equals(MODEL_NAME, ignoreCase = true)

    fun defaultFieldMapFor(modelFields: List<String>): Map<String, String> {
        if (modelFields.isEmpty()) return defaultFieldMap
        val available = modelFields.toSet()
        return defaultFieldMap.filterKeys { it in available }
    }

    /** Tags for a profile switching to the Hoshi note type: chimahon's default tag becomes Hoshi's. */
    fun tagsFor(currentTags: String): String =
        if (currentTags.isBlank() || currentTags.trim() == CHIMAHON_DEFAULT_TAGS) DEFAULT_TAGS else currentTags

    private const val CHIMAHON_DEFAULT_TAGS = "chimahon"
}
