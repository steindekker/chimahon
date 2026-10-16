package chimahon.anki

import android.content.ContextWrapper
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.MediaInfo
import chimahon.TermResult
import chimahon.TransformGroup
import chimahon.hoshi.HoshiCardData
import hoshi.card.CardEnricher
import hoshi.card.CardEnrichment
import hoshi.card.HoshiCardFields
import hoshi.card.notetype.HoshiNoteType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * The Hoshi note type must get the same field values in chimahon as in Hoshi Reader. Expected
 * strings come from Hoshi Reader itself: its renderer tests (AnkiHandlebarRendererTest: cloze,
 * {sentence}) and its popup.js `constructFuriganaPlain` run under node.
 */
class HoshiCardParityTest {

    @AfterEach
    fun tearDown() {
        AnkiCardCreator.resetBridgeFactoryForTests()
        AnkiCardCreator.resetFieldMapParserForTests()
        AnkiCardCreator.resetCardEnricherProviderForTests()
    }

    @Test
    fun `hoshi card fields match Hoshi Reader with pack values`() = runTest {
        val bridge = CapturingBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        val packValues = mapOf(
            // Braces in values must survive: they are never re-parsed as markers.
            HoshiCardFields.Definition to "<ol><li>cat {feline}</li></ol>",
            HoshiCardFields.DefinitionSource to "JMdict",
            HoshiCardFields.PitchGraph to "<svg>1</svg>",
            HoshiCardFields.FrequencyRank to "1139",
            HoshiCardFields.ExampleSentences to "<ul><li>猫がいる。</li></ul>",
        )
        val seen = mutableListOf<Triple<String, String, String>>()
        AnkiCardCreator.cardEnricherProvider = {
            CardEnricher { expression, reading, sentence, _ ->
                seen += Triple(expression, reading, sentence)
                CardEnrichment(packValues + (HoshiCardFields.SentencePitch to "まるで、<jrp>猫</jrp>のようだ。"))
            }
        }

        val result = addHoshiCard(sentence = "まるで、猫のようだ。", offset = 4)

        assertEquals(AnkiResult.Success(7), result)
        assertEquals(listOf(Triple("猫", "ねこ", "まるで、猫のようだ。")), seen)
        assertEquals(HoshiPreset.MODEL_NAME, bridge.modelName)
        assertEquals(
            mapOf(
                "Term" to "猫",
                "Reading" to "ねこ",
                "Furigana" to "猫[ねこ]",
                "Sentence" to "まるで、<jrp>猫</jrp>のようだ。",
                "SentenceFurigana" to "まるで、<b>猫</b>のようだ。",
                "Cloze Before" to "まるで、",
                "Cloze Inside" to "猫",
                "Cloze After" to "のようだ。",
                "Meaning" to "<ol><li>cat {feline}</li></ol>",
                "Context" to "テスト本",
                "Frequency" to "1139",
                "Pitch Accent" to "<svg>1</svg>",
                "Image" to "<img src=\"shot.webp\">",
                "Term Audio" to "",
                "Sentence Audio" to "[sound:sentence.m4a]",
                "Definition Source" to "JMdict",
                "Examples" to "<ul><li>猫がいる。</li></ul>",
            ),
            bridge.fields,
        )
        assertEquals(listOf(HoshiNoteType.DefaultTag), bridge.tags)
    }

    @Test
    fun `without data the fields fall back to the dictionary and the plain sentence`() = runTest {
        val bridge = CapturingBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.cardEnricherProvider = { HoshiCardData.OfflineEnricher }

        addHoshiCard(sentence = "まるで、猫のようだ。", offset = 4)

        val fields = bridge.fields
        assertEquals("まるで、猫のようだ。", fields["Sentence"])
        assertEquals("まるで、<b>猫</b>のようだ。", fields["SentenceFurigana"])
        assertTrue(fields.getValue("Meaning").contains("cat"), fields["Meaning"])
        assertEquals("JMdict", fields["Definition Source"])
        assertEquals("9999999", fields["Frequency"])
        assertEquals("", fields["Examples"])
    }

    @Test
    fun `a picked sentence that spells the word differently still gets its cloze`() = runTest {
        // AnkiHandlebarRendererTest.clozePartsFallBackToTheTermWhenThePickedSentenceSpellsItDifferently
        val bridge = CapturingBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.cardEnricherProvider = { HoshiCardData.OfflineEnricher }

        addHoshiCard(sentence = "まるで、猫のようだ。", offset = -1, matched = "ねこ")
        assertEquals(
            listOf("まるで、", "猫", "のようだ。", "まるで、<b>猫</b>のようだ。"),
            bridge.clozeAndSentence(),
        )

        addHoshiCard(sentence = "犬だ", offset = -1, matched = "ねこ")
        assertEquals(listOf("犬だ", "", "", "犬だ"), bridge.clozeAndSentence())
    }

    @Test
    fun `cloze uses the selected occurrence and checks the offset like Hoshi Reader`() {
        fun split(sentence: String, offset: Int, matched: String, expression: String = matched) =
            HoshiCardRendering.cloze(sentence, offset, matched, expression, reading = "").let {
                listOf(it.prefix, it.body, it.suffix, HoshiCardRendering.sentenceBold(it))
            }
        // AnkiHandlebarRendererTest.sentenceBoldUsesSelectedOccurrenceOnly / ...WithoutSelectionOffset
        assertEquals("僕は<b>僕</b>の本を読んだ。", split("僕は僕の本を読んだ。", 2, "僕")[3])
        assertEquals("<b>僕</b>は僕の本を読んだ。", split("僕は僕の本を読んだ。", -1, "僕")[3])
        // AnkiHandlebarRendererTest.clozePartsHandleSupplementaryCharactersAndInvalidOffsets
        assertEquals(listOf("前", "𠮟る", "後"), split("前𠮟る後", 1, "𠮟る").take(3))
        assertEquals(listOf("前", "𠮟る", "後"), split("前𠮟る後", 99, "𠮟る").take(3))
        // AnkiHandlebarRendererTest.sentenceFallsBackToRawContextWhenMatchedPayloadIsBlank
        assertEquals(listOf("本を読む。", "", "", "本を読む。"), split("本を読む。", -1, "", "読む"))
    }

    @Test
    fun `furigana plain matches Hoshi Reader popup js`() {
        // node: constructFuriganaPlain(expression, reading) from hoshi-web/popup/popup.js
        val expected = listOf(
            Triple("猫", "ねこ", "猫[ねこ]"),
            Triple("食べる", "たべる", "食[た]べる "),
            Triple("ねこ", "ねこ", "ねこ "),
            Triple("ねこ", "", "ねこ "),
            Triple("お茶", "おちゃ", "お 茶[ちゃ]"),
            Triple("取り扱い", "とりあつかい", "取[と]り 扱[あつか]い "),
            Triple("人々", "ひとびと", "人々[ひとびと]"),
            Triple("カタカナ", "かたかな", "カタカナ[かたかな]"),
            Triple("日本語", "にほんご", "日本語[にほんご]"),
        )
        for ((expression, reading, furigana) in expected) {
            assertEquals(furigana, HoshiCardRendering.furiganaPlain(expression, reading), "$expression/$reading")
        }
    }

    @Test
    fun `other note types keep chimahon's own rendering`() = runTest {
        val bridge = CapturingBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.cardEnricherProvider = { HoshiCardData.OfflineEnricher }
        // org.json is Android's stub in JVM tests, so the map is handed over like the other tests do.
        AnkiCardCreator.fieldMapParser = {
            mapOf("F" to "{furigana-plain}", "S" to "{sentence-bold}", "D" to "{definition}")
        }

        AnkiCardCreator.addToAnki(
            context = TestContext,
            result = lookupResult("ねこ", "ねこ", "ねこ"),
            deck = "deck",
            model = "Custom",
            fieldMapJson = "custom",
            tags = "",
            dupCheck = false,
            dupScope = "collection",
            dupAction = "add",
            sentence = "ねこだ",
            offset = 0,
        )

        assertEquals("ねこ", bridge.fields["F"])
        assertEquals("<b>ねこ</b>だ", bridge.fields["S"])
        assertTrue(bridge.fields.getValue("D").contains("cat"))
    }

    @Test
    fun `the default mapping is Hoshi Reader's in chimahon markers`() {
        val markers = Regex("""\{([^{}]*)\}""")
        val used = (HoshiPreset.defaultFieldMap.values + HoshiPreset.markerFallbacks.values)
            .flatMap { template -> markers.findAll(template).map { it.groupValues[1] } }
        assertTrue(used.isNotEmpty())
        assertEquals(emptyList<String>(), used.filterNot { it in Marker.ALL })
        assertEquals(HoshiNoteType.DefaultFieldMapping.keys, HoshiPreset.defaultFieldMap.keys)
        assertEquals("{${Marker.SENTENCE_BOLD}}", HoshiPreset.defaultFieldMap["SentenceFurigana"])
        assertEquals("{${HoshiCardFields.SentencePitch}}", HoshiPreset.defaultFieldMap["Sentence"])
    }

    private suspend fun addHoshiCard(sentence: String, offset: Int, matched: String = "猫"): AnkiResult =
        AnkiCardCreator.addToAnki(
            context = TestContext,
            result = lookupResult("猫", "ねこ", matched),
            deck = "deck",
            model = HoshiPreset.MODEL_NAME,
            fieldMapJson = "{}",
            tags = HoshiPreset.DEFAULT_TAGS,
            dupCheck = false,
            dupScope = "collection",
            dupAction = "add",
            sentence = sentence,
            offset = offset,
            media = MediaInfo(mangaTitle = "テスト本", chapterName = "1"),
            screenshotBytes = byteArrayOf(1),
            sentenceAudioBytes = byteArrayOf(2),
            selection = matched,
        )

    private fun lookupResult(expression: String, reading: String, matched: String) = LookupResult(
        matched = matched,
        deinflected = expression,
        process = emptyArray<TransformGroup>(),
        term = TermResult(
            expression = expression,
            reading = reading,
            rules = "",
            glossaries = arrayOf(GlossaryEntry("JMdict", "\"cat\"", "", "")),
            frequencies = emptyArray(),
            pitches = emptyArray(),
        ),
        preprocessorSteps = 0,
    )

    private class CapturingBridge : AnkiCardBridge {
        var fields: Map<String, String> = emptyMap()
        var tags: List<String> = emptyList()
        var modelName = ""

        fun clozeAndSentence() =
            listOf("Cloze Before", "Cloze Inside", "Cloze After", "SentenceFurigana").map { fields.getValue(it) }

        override fun hasPermission(): Boolean = true
        override suspend fun ensureDefaultDeckName(): String = "deck"
        override suspend fun ensureLapisModelName(): String = "Lapis"
        override suspend fun getDeckId(deckName: String): Long = 1L
        override suspend fun findNotes(expression: String, modelName: String?, deckId: Long?): List<Long> = emptyList()
        override suspend fun storeMedia(filename: String, data: ByteArray): String = "shot.webp"
        override suspend fun storeMedia(source: AnkiSentenceAudioSource): String = "sentence.m4a"
        override suspend fun addNote(deckName: String, modelName: String, fields: Map<String, String>, tags: List<String>): Long {
            this.fields = fields
            this.tags = tags
            this.modelName = modelName
            return 7L
        }
        override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>) = Unit
        override fun triggerSync() = Unit
    }

    private object TestContext : ContextWrapper(null) {
        private val files = Files.createTempDirectory("hoshi-card-parity-test").toFile()
        override fun getFilesDir(): File = files
    }
}
