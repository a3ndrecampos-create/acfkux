package com.rotacerta.entregador.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rotacerta.entregador.ocr.OcrHelper
import com.rotacerta.entregador.viewmodel.RotaViewModel
import com.rotacerta.entregador.viewmodel.PedidoPreview

/**
 * Fluxo unificado "Escanear pedido":
 *
 * 1. Câmera ao vivo — OCR lê o CEP da etiqueta, QR/barcode lê o código de rastreio.
 *    Só avança após confirmar o mesmo CEP duas vezes seguidas (anti-borrado).
 *
 * 2. Preview — mostra endereço geocodificado + rastreio para o entregador confirmar
 *    antes de adicionar à rota.
 *
 * 3. Adicionado — fecha o dialog. A rota já contém o novo pedido.
 */
@Composable
fun ScanPedidoDialog(
    viewModel: RotaViewModel,
    onDismiss: () -> Unit
) {
    val preview by viewModel.pedidoPreview.collectAsState()

    // Enquanto não há preview, mostra a câmera de escaneamento
    if (preview == null) {
        ScannerStep(viewModel = viewModel, onDismiss = onDismiss)
    } else {
        PreviewStep(
            preview = preview!!,
            onConfirm = { viewModel.confirmPedido() },
            onRescan = { viewModel.cancelPedidoPreview() },
            onDismiss = {
                viewModel.cancelPedidoPreview()
                onDismiss()
            }
        )
    }
}

// ─── Passo 1: câmera ──────────────────────────────────────────────────────────

@Composable
private fun ScannerStep(viewModel: RotaViewModel, onDismiss: () -> Unit) {
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    var hasResult by remember { mutableStateOf(false) }
    var candidateCep by remember { mutableStateOf<String?>(null) }
    var trackingCode by remember { mutableStateOf<String?>(null) }
    var lastFrameAt by remember { mutableStateOf(0L) }
    var instructions by remember { mutableStateOf("Aponte para a etiqueta do pedido") }

    EmbeddedScannerDialog(
        instructions = instructions,
        onFrame = { imageProxy ->
            val now = System.currentTimeMillis()
            val mediaImage = imageProxy.image
            if (mediaImage != null && !hasResult && now - lastFrameAt > 350) {
                lastFrameAt = now
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                // Tenta capturar código de rastreio (QR/barcode) em paralelo
                if (trackingCode == null) {
                    barcodeScanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull()?.rawValue?.let { trackingCode = it }
                        }
                }

                // OCR para CEP + número do endereço
                textRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text
                        val cep = OcrHelper.extractCep(text)
                        if (cep != null && !hasResult) {
                            if (cep == candidateCep) {
                                // Mesmo CEP duas vezes seguidas → aceito
                                hasResult = true
                                val numero = OcrHelper.extractNumero(text)
                                viewModel.preparePedidoPreview(cep, numero, trackingCode)
                            } else {
                                candidateCep = cep
                                instructions = "CEP $cep detectado — confirmando..."
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        },
        onDismiss = onDismiss
    )
}

// ─── Passo 2: preview para confirmar ──────────────────────────────────────────

@Composable
private fun PreviewStep(
    preview: PedidoPreview,
    onConfirm: () -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LocalShipping, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Confirmar pedido", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Endereço geocodificado
                SectionField(
                    label = "Endereço",
                    value = preview.address,
                    isLoading = preview.isGeocoding
                )

                // CEP bruto lido
                SectionField(label = "CEP lido", value = preview.cep)

                // Código de rastreio (se capturado)
                if (!preview.trackingCode.isNullOrBlank()) {
                    SectionField(label = "Rastreio", value = preview.trackingCode)
                }

                // Aviso se localização aproximada
                if (preview.approxLocation) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠ Localização aproximada — o endereço foi adicionado mas pode ter imprecisão.",
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // Erro de geocodificação
                if (preview.geocodeError != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Não foi possível localizar: ${preview.geocodeError}",
                            modifier = Modifier.padding(10.dp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = !preview.isGeocoding && preview.geocodeError == null,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Adicionar à rota", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Escanear de novo") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    )
}

@Composable
private fun SectionField(label: String, value: String, isLoading: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp
        )
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Localizando endereço...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(
                value,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start
            )
        }
    }
}
