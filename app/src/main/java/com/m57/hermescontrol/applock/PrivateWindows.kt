package com.m57.hermescontrol.applock

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBoxScope
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Native sub-windows sit above an Activity overlay. Every app-owned window must respect this gate (#1604). */
internal val LocalPrivateWindowsBlocked = compositionLocalOf { false }

@Composable
internal fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    PrivateWindow { visibility ->
        androidx.compose.ui.window.Dialog({ visibility.dismiss(onDismissRequest) }, properties) {
            CapturePrivateWindow(visibility)
            content()
        }
    }
}

@Composable
internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    PrivateWindow { visibility ->
        androidx.compose.material3.AlertDialog(
            { visibility.dismiss(onDismissRequest) },
            {
                CapturePrivateWindow(visibility)
                confirmButton()
            },
            modifier,
            dismissButton,
            icon,
            title,
            text,
            shape,
            containerColor,
            iconContentColor,
            titleContentColor,
            textContentColor,
            tonalElevation,
            properties,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit,
) {
    PrivateWindow { visibility ->
        androidx.compose.material3.ModalBottomSheet(
            { visibility.dismiss(onDismissRequest) },
            modifier,
            sheetState,
            sheetMaxWidth,
            sheetGesturesEnabled,
            shape,
            containerColor,
            contentColor,
            tonalElevation,
            scrimColor,
            dragHandle,
            contentWindowInsets,
            properties,
        ) {
            CapturePrivateWindow(visibility)
            content()
        }
    }
}

@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PrivateWindow { visibility ->
        androidx.compose.material3.DropdownMenu(
            expanded,
            { visibility.dismiss(onDismissRequest) },
            modifier,
            offset,
            scrollState,
            properties,
            shape,
            containerColor,
            tonalElevation,
            shadowElevation,
            border,
        ) {
            CapturePrivateWindow(visibility)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExposedDropdownMenuBoxScope.PrivateExposedDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    matchAnchorWidth: Boolean = true,
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    tonalElevation: Dp = MenuDefaults.TonalElevation,
    shadowElevation: Dp = MenuDefaults.ShadowElevation,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PrivateWindow { visibility ->
        ExposedDropdownMenu(
            expanded,
            { visibility.dismiss(onDismissRequest) },
            modifier,
            scrollState,
            matchAnchorWidth,
            shape,
            containerColor,
            tonalElevation,
            shadowElevation,
            border,
        ) {
            CapturePrivateWindow(visibility)
            content()
        }
    }
}

@Composable
internal fun Popup(
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset(0, 0),
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    PrivateWindow { visibility ->
        androidx.compose.ui.window.Popup(alignment, offset, { visibility.dismiss(onDismissRequest) }, properties) {
            CapturePrivateWindow(visibility)
            content()
        }
    }
}

@Composable
internal fun Popup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    PrivateWindow { visibility ->
        androidx.compose.ui.window.Popup(popupPositionProvider, { visibility.dismiss(onDismissRequest) }, properties) {
            CapturePrivateWindow(visibility)
            content()
        }
    }
}
