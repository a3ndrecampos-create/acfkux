package com.rotacerta.entregador

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SplashScreen {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }
}

@Composable
private fun SplashScreen(onFinish: () -> Unit) {
    // Fade in do conteúdo principal
    val alphaMain = remember { Animatable(0f) }
    // Fade in do rodapé "from"
    val alphaFrom = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alphaMain.animateTo(1f, animationSpec = tween(700))
        delay(1200)
        alphaFrom.animateTo(1f, animationSpec = tween(500))
        delay(800)
        onFinish()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        // Nome do app centralizado
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(alphaMain.value),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Flux",
                color = Color(0xFFFF6B00),
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp
            )
        }

        // Rodapé: "from" + logo
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(alphaFrom.value),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "from",
                color = Color(0xFF8B93A7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 2.sp
            )
            Image(
                painter = painterResource(id = R.drawable.logo_arkacortex),
                contentDescription = "Arka Cortex",
                modifier = Modifier.width(200.dp)
            )
        }
    }
}
