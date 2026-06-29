package com.m57.hermescontrol.ui.pairing

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.m57.hermescontrol.R
import java.util.concurrent.Executors

/**
 * Pairing code entry screen that offers QR scanning (via CameraX + ML Kit)
 * and a manual text-entry fallback.
 *
 * On successful connection (stored credentials, rebuilt ApiClient) the
 * [onConnected] callback fires to navigate to the main chat screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingCodeEntryScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairingCodeEntryViewModel =
        viewModel(
            factory =
                run {
                    val app = LocalContext.current.applicationContext as Application
                    PairingCodeEntryViewModelFactory(app)
                },
        ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── Navigate to main screen on success ──────────────────────────────
    LaunchedEffect(state.connectionSuccess) {
        if (state.connectionSuccess) {
            onConnected()
        }
    }

    // ── Determine initial camera permission state ───────────────────────
    val cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    LaunchedEffect(Unit) {
        viewModel.onPermissionResult(cameraPermissionGranted)
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            viewModel.onPermissionResult(granted)
        }

    var selectedTab by remember { mutableStateOf(if (cameraPermissionGranted) 0 else 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pairing_code_entry_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = modifier.fillMaxSize().padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        if (cameraPermissionGranted) {
                            selectedTab = 0
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    text = { Text(stringResource(R.string.pairing_code_entry_tab_scan)) },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                    enabled = cameraPermissionGranted,
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.pairing_code_entry_tab_manual)) },
                    icon = { Icon(Icons.Filled.Keyboard, contentDescription = null) },
                )
            }

            when (selectedTab) {
                0 ->
                    QrScannerTab(
                        cameraPermissionGranted = cameraPermissionGranted,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onCodeScanned = viewModel::onCodeDetected,
                        modifier = Modifier.weight(1f),
                    )
                1 ->
                    ManualEntryTab(
                        manualCode = state.manualCode,
                        isConnecting = state.isConnecting,
                        errorMessage = state.errorMessage,
                        onCodeChange = viewModel::onManualCodeChange,
                        onConnect = { viewModel.onCodeDetected(state.manualCode) },
                        modifier = Modifier.weight(1f),
                    )
            }

            // Error banner shown below tabs for both modes
            if (selectedTab == 0 && state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            // Connecting spinner overlaid across the whole content area
            if (state.isConnecting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

// ── QR Scanner Tab ─────────────────────────────────────────────────────

@Composable
private fun QrScannerTab(
    cameraPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!cameraPermissionGranted) {
        // Camera permission not granted — show request prompt
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.pairing_code_entry_camera_required),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.pairing_code_entry_camera_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.pairing_code_entry_grant_permission))
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp)),
            ) {
                QrCodeScannerView(
                    onCodeScanned = onCodeScanned,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Text(
                text = stringResource(R.string.pairing_code_entry_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

/**
 * CameraX + ML Kit barcode scanner wrapped in an [AndroidView].
 * Continuously scans QR codes and calls [onCodeScanned] once per unique code.
 */
@Composable
private fun QrCodeScannerView(
    onCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lastScannedCode = remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val analysisExecutor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()

                    val preview =
                        Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val scanner: BarcodeScanner = BarcodeScanning.getClient()

                    val imageAnalysis =
                        ImageAnalysis.Builder()
                            .setTargetResolution(Size(1024, 768))
                            .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                            ).build()
                            .also { analysis ->
                                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                    processFrame(
                                        imageProxy = imageProxy,
                                        scanner = scanner,
                                        onBarcode = { barcode ->
                                            val rawValue = barcode?.rawValue
                                            if (
                                                rawValue != null &&
                                                rawValue != lastScannedCode.value
                                            ) {
                                                lastScannedCode.value = rawValue
                                                onCodeScanned(rawValue)
                                            }
                                        },
                                    )
                                }
                            }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                },
                ContextCompat.getMainExecutor(ctx),
            )

            previewView
        },
    )
}

private fun processFrame(
    imageProxy: ImageProxy,
    scanner: BarcodeScanner,
    onBarcode: (Barcode?) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage =
        InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    scanner.process(inputImage)
        .addOnSuccessListener { barcodes -> onBarcode(barcodes.firstOrNull()) }
        .addOnCompleteListener { imageProxy.close() }
}

// ── Manual Entry Tab ──────────────────────────────────────────────────

@Composable
private fun ManualEntryTab(
    manualCode: String,
    isConnecting: Boolean,
    errorMessage: String?,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_code_entry_manual_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = manualCode,
            onValueChange = onCodeChange,
            label = { Text(stringResource(R.string.pairing_code_entry_code_label)) },
            placeholder = {
                Text(stringResource(R.string.connect_placeholder_pairing))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting,
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = manualCode.isNotBlank() && !isConnecting,
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.action_connect))
            }
        }
    }
}
