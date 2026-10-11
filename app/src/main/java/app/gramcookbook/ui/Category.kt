package app.gramcookbook.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gramcookbook.R

/** A text field for the category, with the existing ones as one-tap suggestions. */
@Composable
fun CategoryField(value: String, onChange: (String) -> Unit, existing: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.category)) }, singleLine = true,
        )
        val others = existing.filter { !it.equals(value.trim(), ignoreCase = true) }
        if (others.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in others) SuggestionChip(onClick = { onChange(c) }, label = { Text(c) })
            }
        }
    }
}

@Composable
fun CategoryDialog(current: String, existing: List<String>, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.category)) },
        text = { CategoryField(text, { text = it }, existing) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
