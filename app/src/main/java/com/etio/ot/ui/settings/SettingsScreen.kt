package com.etio.ot.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.data.settings.ThemeMode
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.glass

/**
 * Two settings, both of which change what happens on stage: which theme the demo
 * runs in, and whether the tutorial can be replayed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onReplayTutorial: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val mode by viewModel.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
    val splashOverride by viewModel.splashDurationMs.collectAsStateWithLifecycle(initialValue = null)
    var confirmReplay by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Etio.colors.background,
        topBar = {
            Surface(color = Color.Transparent, modifier = Modifier.glass(shape = MaterialTheme.shapes.extraSmall)) {
                TopAppBar(
                    title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Etio.space.gutter),
            verticalArrangement = Arrangement.spacedBy(Etio.space.xs),
        ) {
            Spacer(Modifier.height(Etio.space.l))
            SectionLabel("Appearance")

            ThemeMode.entries.forEach { option ->
                ThemeRow(
                    label = when (option) {
                        ThemeMode.SYSTEM -> "Follow the system"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    },
                    selected = option == mode,
                    onClick = { viewModel.setThemeMode(option) },
                )
            }

            Spacer(Modifier.height(Etio.space.xl))
            SectionLabel("First run")

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { confirmReplay = true }
                    .padding(vertical = Etio.space.m),
            ) {
                Column {
                    Text("Replay the tutorial", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Runs in a sandbox theatre, then re-seeds the day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Etio.colors.textSecondary,
                    )
                }
            }

            Spacer(Modifier.height(Etio.space.xl))
            SectionLabel("Diagnostics")
            DiagnosticsSection(
                splashDurationMs = splashOverride,
                onSplashDurationChange = viewModel::setSplashDurationMs,
            )
            Spacer(Modifier.height(Etio.space.xxl))
        }
    }

    if (confirmReplay) {
        AlertDialog(
            onDismissRequest = { confirmReplay = false },
            title = { Text("Replay the tutorial?") },
            text = {
                Text(
                    "It runs against a sandbox case, so the current day is cleared and " +
                        "re-seeded when you finish. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplay = false
                        onReplayTutorial()
                        viewModel.replayTutorial()
                    },
                ) { Text("Replay", color = Etio.colors.delay) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplay = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = Etio.colors.textSecondary,
        modifier = Modifier.padding(vertical = Etio.space.s),
    )
}

@Composable
private fun ThemeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Etio.space.m),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Etio.colors.accent else Etio.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = "Selected", tint = Etio.colors.accent)
        }
    }
}
