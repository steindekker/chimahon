package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import chimahon.hoshi.HoshiCardData
import chimahon.hoshi.HoshiCardInstallState
import eu.kanade.presentation.more.settings.Preference
import hoshi.card.setup.HoshiCardInstallStep
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Settings → Anki: installs and updates the data behind the Hoshi note type's enriched fields
 * (enrichment pack and sentence pitch model) from the build's setup server.
 */
@Composable
internal fun hoshiCardDataGroup(): Preference.PreferenceGroup {
    val data = remember { Injekt.get<HoshiCardData>() }
    val installState by data.installState.collectAsState()
    val packInfo by data.packInfo.collectAsState()
    val sentenceModelInstalled by data.sentenceModelInstalled.collectAsState()

    val installed = packInfo != null && sentenceModelInstalled
    val running = installState is HoshiCardInstallState.Running
    val status = listOf(
        packInfo?.let { stringResource(MR.strings.pref_hoshi_card_data_pack_installed, it.entries.toString(), it.builtAt) }
            ?: stringResource(MR.strings.pref_hoshi_card_data_pack_missing),
        if (sentenceModelInstalled) {
            stringResource(MR.strings.pref_hoshi_card_data_sentence_installed)
        } else {
            stringResource(MR.strings.pref_hoshi_card_data_sentence_missing)
        },
    ).joinToString("\n")
    val progress = when (val state = installState) {
        HoshiCardInstallState.Idle -> null
        is HoshiCardInstallState.Running -> when (state.step) {
            null -> stringResource(MR.strings.pref_hoshi_card_data_reading_catalog)
            HoshiCardInstallStep.SentenceAccent, HoshiCardInstallStep.SentenceDictionary ->
                stringResource(MR.strings.pref_hoshi_card_data_downloading_sentence, state.percent)
            HoshiCardInstallStep.Pack -> stringResource(MR.strings.pref_hoshi_card_data_downloading_pack, state.percent)
        }
        HoshiCardInstallState.Done -> stringResource(MR.strings.pref_hoshi_card_data_done)
        is HoshiCardInstallState.Failed -> when (state.reason) {
            HoshiCardInstallState.Reason.Unavailable -> stringResource(MR.strings.pref_hoshi_card_data_unavailable)
            HoshiCardInstallState.Reason.Unreachable -> stringResource(MR.strings.pref_hoshi_card_data_error_unreachable)
            HoshiCardInstallState.Reason.Damaged -> stringResource(MR.strings.pref_hoshi_card_data_error_damaged)
            HoshiCardInstallState.Reason.Format -> stringResource(MR.strings.pref_hoshi_card_data_error_format)
        }
    }

    val action = if (data.isAvailable) {
        Preference.PreferenceItem.TextPreference(
            title = if (installed) {
                stringResource(MR.strings.pref_hoshi_card_data_update)
            } else {
                stringResource(MR.strings.pref_hoshi_card_data_download)
            },
            subtitle = listOfNotNull(progress, status).joinToString("\n"),
            enabled = !running,
            onClick = data::startInstall,
        )
    } else {
        Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.pref_hoshi_card_data_download),
            subtitle = stringResource(MR.strings.pref_hoshi_card_data_unavailable),
            enabled = false,
        )
    }

    return Preference.PreferenceGroup(
        title = stringResource(MR.strings.pref_hoshi_card_data),
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.pref_hoshi_card_data_summary),
            ),
            action,
        ),
    )
}
