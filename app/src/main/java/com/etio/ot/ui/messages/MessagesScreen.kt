package com.etio.ot.ui.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.etio.ot.data.model.Audience
import com.etio.ot.ui.common.EmptyState
import com.etio.ot.ui.theme.Etio
import com.etio.ot.ui.theme.glass

/**
 * The demo moment, and the only screen whose layout is an argument.
 *
 * Surgeon and family sit side by side in the top row, on screen together with no
 * scrolling, because the point is not that four messages exist — it is that the same
 * fact is said two completely different ways depending on who is reading. In a
 * vertical list you have to remember the first one to notice.
 *
 * No glass anywhere here: this is text people will read closely and copy verbatim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    delayId: String,
    onBack: () -> Unit,
    viewModel: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(delayId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        containerColor = Etio.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.glass(RoundedCornerShape(0.dp)),
                title = {
                    Column {
                        Text("Updates", style = MaterialTheme.typography.titleLarge)
                        state.record?.let { r ->
                            Text(
                                ("Case ${state.case?.caseNumber ?: "?"} · ${r.code.display}" +
                                    (r.estimatedMin?.let { " · $it min" } ?: "")).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = Etio.colors.textSecondary,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::generate, enabled = !state.generating) {
                        Icon(Icons.Default.Refresh, contentDescription = "Regenerate all")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Etio.space.m),
            verticalArrangement = Arrangement.spacedBy(Etio.space.s),
        ) {
            if (state.record == null && !state.generating) {
                EmptyState(
                    line = "That delay record is no longer here.",
                    actionLabel = "Back to the day",
                    onAction = onBack,
                )
                return@Column
            }

            // Row one is the comparison. Row two is the rest of the fan-out.
            MessageRow(
                left = Audience.SURGEON,
                right = Audience.FAMILY,
                state = state,
                context = context,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            MessageRow(
                left = Audience.WARD,
                right = Audience.ANAESTHESIA,
                state = state,
                context = context,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )

            state.record?.let { r ->
                Text(
                    "All four written from: “${r.transcriptRaw}”",
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.textSecondary,
                    modifier = Modifier.padding(vertical = Etio.space.s),
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    left: Audience,
    right: Audience,
    state: MessagesUiState,
    context: Context,
    viewModel: MessagesViewModel,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Etio.space.s)) {
        listOf(left, right).forEach { audience ->
            MessageCard(
                audience = audience,
                state = state,
                context = context,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun MessageCard(
    audience: Audience,
    state: MessagesUiState,
    context: Context,
    viewModel: MessagesViewModel,
    modifier: Modifier = Modifier,
) {
    val message = state.messages[audience]
    val isPending = audience in state.pending

    // Each card fades up as its own generation completes, rather than four empty
    // placeholders appearing at once and filling in later. Four blanks on screen is
    // the app admitting it has nothing yet; one card arriving is it delivering.
    val alpha by animateFloatAsState(
        targetValue = if (message != null || isPending) 1f else 0.5f,
        animationSpec = tween(Etio.motion.STANDARD_MS),
        label = "messageCardAlpha",
    )

    Surface(
        color = Etio.colors.surface,
        shape = RoundedCornerShape(Etio.radius.card),
        modifier = modifier.alpha(alpha),
    ) {
        Column(Modifier.padding(Etio.space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    audience.display.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Etio.colors.accent,
                    modifier = Modifier.weight(1f),
                )
                if (isPending) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Etio.colors.accent,
                    )
                }
            }

            Spacer(Modifier.height(Etio.space.s))

            Text(
                text = message?.body ?: if (isPending) "Writing…" else "—",
                style = MaterialTheme.typography.bodyLarge,
                color = if (message == null) Etio.colors.textSecondary else Etio.colors.textPrimary,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            )

            if (message != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            copyToClipboard(context, audience.display, message.body)
                            viewModel.markCopied(message.id)
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy the ${audience.display} message",
                            tint = if (message.copied) Etio.colors.running else Etio.colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { share(context, message.body) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share the ${audience.display} message",
                            tint = Etio.colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.regenerate(audience) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Rewrite the ${audience.display} message",
                            tint = Etio.colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Send update"))
}
