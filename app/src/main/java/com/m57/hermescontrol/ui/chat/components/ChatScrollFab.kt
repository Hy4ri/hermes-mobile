package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/**
 * Floating action button shown when the message list is not following the
 * bottom (issue #682). Tapping it resumes bottom-follow and clears the pending
 * (unseen) update count.
 *
 * @param show when the reader is NOT at the bottom and there is content.
 * @param pendingCount number of tail updates that arrived while follow was
 *   paused — rendered as a badge on the FAB.
 * @param onScrollToBottom invoked on tap; should call
 *   [ChatScrollController.resumeFollowing].
 */
@Composable
fun BoxScope.ChatScrollToBottomFab(
    show: Boolean,
    pendingCount: Int,
    onScrollToBottom: () -> Unit,
) {
    AnimatedVisibility(
        visible = show,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
    ) {
        BadgedBox(
            badge = {
                if (pendingCount > 0) {
                    Badge {
                        val label = if (pendingCount > 99) "99+" else pendingCount.toString()
                        Text(label)
                    }
                }
            },
        ) {
            FloatingActionButton(
                onClick = onScrollToBottom,
                modifier = Modifier.size(40.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.content_desc_scroll_to_bottom),
                )
            }
        }
    }
}
