package chimahon.hoshi

import android.content.Context
import android.util.Log
import chimahon.anki.HoshiPreset
import hoshi.card.CardEnricher
import hoshi.card.HoshiCardEnricher
import hoshi.card.jrp.JrpSentenceModel
import hoshi.card.pack.EnrichmentPack
import hoshi.card.pack.EnrichmentPackFormat
import hoshi.card.pack.InMemoryPackSettings
import hoshi.card.pack.PackInfo
import hoshi.card.setup.HoshiCardInstallStep
import hoshi.card.setup.HoshiCardInstaller
import hoshi.card.setup.SetupCatalogClient
import hoshi.card.setup.SetupFailure
import hoshi.card.setup.SetupServerConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.chimahon.BuildConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Progress of [HoshiCardData.install]. */
sealed interface HoshiCardInstallState {
    data object Idle : HoshiCardInstallState

    /** [step] is null while the catalog is read. */
    data class Running(val step: HoshiCardInstallStep?, val percent: Int) : HoshiCardInstallState

    data object Done : HoshiCardInstallState

    data class Failed(val reason: Reason) : HoshiCardInstallState

    enum class Reason {
        /** This build has no setup server. */
        Unavailable,

        /** The server could not be reached (or refused the token), or a download broke off. */
        Unreachable,

        /** A download did not match its size or checksum. */
        Damaged,

        /** The catalog or the pack is in a format this version cannot read. */
        Format,
    }
}

/**
 * The data the hoshi-card fields need, shared with Hoshi Reader: the enrichment pack and the JRP
 * sentence model, both under `files/enrich`. They come from the setup server configured at build
 * time ([BuildConfig.HOSHI_SETUP_URL]); without them, Hoshi cards still get their fields from the
 * dictionaries and the plain sentence. One instance per app (Injekt singleton).
 */
class HoshiCardData(
    context: Context,
    val catalogUrl: String = BuildConfig.HOSHI_SETUP_URL,
    token: String = BuildConfig.HOSHI_SETUP_TOKEN,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val directory = File(context.filesDir, EnrichmentPackFormat.DIRECTORY)
    private val connections = SetupServerConnections(catalogUrl, token)

    val sentenceModel: JrpSentenceModel = JrpSentenceModel.inDirectory(directory)

    // Installs always start from the catalog, which also points the pack at its pack.json.
    val pack: EnrichmentPack = EnrichmentPack(
        directory = directory,
        settings = InMemoryPackSettings(),
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        sentenceModel = sentenceModel,
        openConnection = connections::open,
    )

    val enricher: CardEnricher = HoshiCardEnricher(pack, sentenceModel, HoshiPreset.markerFallbacks)

    /** Whether this build has a setup server to download from. */
    val isAvailable: Boolean
        get() = catalogUrl.isNotBlank()

    val packInfo: StateFlow<PackInfo?> = pack.info
    val sentenceModelInstalled: StateFlow<Boolean> = sentenceModel.installed

    private val _installState = MutableStateFlow<HoshiCardInstallState>(HoshiCardInstallState.Idle)
    val installState: StateFlow<HoshiCardInstallState> = _installState.asStateFlow()

    /** Installs or updates the data in the app scope, so leaving the screen does not stop it. */
    fun startInstall() {
        if (_installState.value is HoshiCardInstallState.Running) return
        scope.launch { install() }
    }

    /** Reads the catalog, then installs what changed (the sentence model, then the pack). */
    suspend fun install(): HoshiCardInstallState = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext HoshiCardInstallState.Failed(HoshiCardInstallState.Reason.Unavailable)
                .also { _installState.value = it }
        }
        _installState.value = HoshiCardInstallState.Running(step = null, percent = 0)
        val result = try {
            val manifest = SetupCatalogClient(catalogUrl, connections::open).manifest()
            HoshiCardInstaller(sentenceModel, pack, connections::open).install(catalogUrl, manifest) { step, percent ->
                _installState.value = HoshiCardInstallState.Running(step, percent.coerceIn(0, 100))
            }
            HoshiCardInstallState.Done
        } catch (e: CancellationException) {
            _installState.value = HoshiCardInstallState.Idle
            throw e
        } catch (e: SetupFailure) {
            Log.w(TAG, "Hoshi card data install failed: ${e.reason}", e)
            HoshiCardInstallState.Failed(
                when (e.reason) {
                    SetupFailure.Reason.Unreachable -> HoshiCardInstallState.Reason.Unreachable
                    SetupFailure.Reason.Damaged -> HoshiCardInstallState.Reason.Damaged
                    SetupFailure.Reason.Format -> HoshiCardInstallState.Reason.Format
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Hoshi card data install failed", e)
            HoshiCardInstallState.Failed(HoshiCardInstallState.Reason.Unreachable)
        }
        _installState.value = result
        result
    }

    companion object {
        private const val TAG = "HoshiCardData"

        /** Dictionary fallbacks and the plain sentence only, for when no data holder is registered. */
        val OfflineEnricher: CardEnricher =
            HoshiCardEnricher(pack = null, sentenceModel = null, fallbacks = HoshiPreset.markerFallbacks)

        /** The app's enricher; [OfflineEnricher] (logged) when [HoshiCardData] is not registered. */
        fun currentEnricher(): CardEnricher = try {
            Injekt.get<HoshiCardData>().enricher
        } catch (e: Exception) {
            Log.w(TAG, "HoshiCardData is not registered; Hoshi fields use dictionary fallbacks", e)
            OfflineEnricher
        }
    }
}
