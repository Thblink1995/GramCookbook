package app.gramcookbook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gramcookbook.BuildConfig
import app.gramcookbook.R

private const val RELEASES_PAGE = "https://github.com/${BuildConfig.UPDATE_REPO}/releases"

/** On the recipe list, only while a newer version is available or on its way. */
@Composable
fun UpdateBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val u = vm.update
    val release = when (u) {
        is UpdateState.Available -> u.release
        is UpdateState.Downloading -> u.release
        is UpdateState.Ready -> u.release
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.update_available, release.version), style = MaterialTheme.typography.titleSmall)
            if (u is UpdateState.Downloading) {
                LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp))
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = { vm.installUpdate() }, enabled = u !is UpdateState.Downloading) {
                    Text(stringResource(if (u is UpdateState.Ready) R.string.update_install else R.string.update_now))
                }
            }
        }
    }
}

/** The "Updates" part of the settings screen. */
@Composable
fun UpdateSection(vm: AppViewModel) {
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.current_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        when (val u = vm.update) {
            UpdateState.Idle -> OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.check_updates))
            }
            UpdateState.Checking -> {
                Text(stringResource(R.string.checking_updates))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            UpdateState.UpToDate -> {
                Text("✓ " + stringResource(R.string.up_to_date), color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.check_updates))
                }
            }
            is UpdateState.Available -> {
                Text(stringResource(R.string.update_available, u.release.version), style = MaterialTheme.typography.titleSmall)
                ReleaseNotes(u.release.notes)
                Button(onClick = { vm.installUpdate() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_now)) }
            }
            is UpdateState.Downloading -> {
                Text(stringResource(R.string.update_downloading))
                LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> Button(onClick = { vm.installUpdate() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_install) + " " + u.release.version)
            }
            is UpdateState.Failed -> {
                Text("✗ " + u.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = { if (u.release != null) vm.installUpdate() else vm.checkForUpdate(manual = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.retry)) }
            }
        }
        TextButton(onClick = { runCatching { uri.openUri(RELEASES_PAGE) } }) { Text(stringResource(R.string.all_versions)) }
    }
}

/** Release notes as plain text: Markdown markers dropped, kept short. */
@Composable
private fun ReleaseNotes(markdown: String) {
    val text = markdown.lines()
        .map { it.replace(Regex("""\*\*|`|^#+\s*"""), "").replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1").trimEnd() }
        .dropLastWhile { it.isBlank() }
        .joinToString("\n")
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
    }
}
