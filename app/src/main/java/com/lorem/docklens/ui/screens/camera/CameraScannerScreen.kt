package com.lorem.docklens.ui.screens.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lorem.docklens.ui.theme.DockLensTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraScannerScreen(
    onImageCaptured: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView<PreviewView>(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val cameraPreview = CameraPreview.Builder().build().also {
                            it.surfaceProvider = surfaceProvider
                        }
                        
                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                cameraPreview,
                                imageCapture
                            )
                        } catch (exc: Exception) {
                            Log.e("CameraScannerScreen", "Use case binding failed", exc)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        ScannerOverlay()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
            }
            
            IconButton(
                onClick = {
                    flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) {
                        ImageCapture.FLASH_MODE_ON
                    } else {
                        ImageCapture.FLASH_MODE_OFF
                    }
                    imageCapture.flashMode = flashMode
                },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
            ) {
                Icon(
                    if (flashMode == ImageCapture.FLASH_MODE_ON) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = "Flash",
                    tint = Color.White
                )
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            FloatingActionButton(
                onClick = {
                    takePhoto(context, imageCapture, cameraExecutor, onImageCaptured)
                },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Rounded.Camera, contentDescription = "Capture", modifier = Modifier.size(36.dp))
            }
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
}

@Composable
fun ScannerOverlay() {
    val primaryColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val overlayWidth = width * 0.85f
        val overlayHeight = height * 0.65f
        val left = (width - overlayWidth) / 2
        val top = (height - overlayHeight) / 2
        
        drawRect(color = Color.Black.copy(alpha = 0.6f))
        
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(overlayWidth, overlayHeight),
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            blendMode = BlendMode.Clear
        )
        
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(left, top),
            size = Size(overlayWidth, overlayHeight),
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onImageCaptured: (String) -> Unit
) {
    val outputDirectory = context.filesDir
    val photoFile = File(
        outputDirectory,
        "SCAN_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".jpg"
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraScannerScreen", "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onImageCaptured(photoFile.absolutePath)
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun CameraScannerPreview() {
    DockLensTheme {
        Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray)) {
            ScannerOverlay()
            
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(modifier = Modifier.size(48.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.5f)) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.padding(12.dp))
                }
                Surface(modifier = Modifier.size(48.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.5f)) {
                    Icon(Icons.Rounded.FlashOff, null, tint = Color.White, modifier = Modifier.padding(12.dp))
                }
            }
            
            FloatingActionButton(
                onClick = {},
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).size(72.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.Camera, null, modifier = Modifier.size(36.dp))
            }
        }
    }
}
