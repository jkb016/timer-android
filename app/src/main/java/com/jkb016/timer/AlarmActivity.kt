package com.jkb016.timer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.jkb016.timer.data.TimerRepository
import com.jkb016.timer.data.TimerStatus
import com.jkb016.timer.service.TimerForegroundService
import com.jkb016.timer.ui.theme.TimerTheme

class AlarmActivity : ComponentActivity() {

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TimerForegroundService.ACTION_ALARM_STOPPED) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        turnScreenOnAndShowWhenLocked()
        super.onCreate(savedInstanceState)
        hideSystemUI()
        registerStopReceiver()

        val timerName = intent.getStringExtra(EXTRA_TIMER_NAME) ?: "Timer"

        setContent {
            TimerTheme {
                AlarmScreen(
                    timerName = timerName,
                    onStop = {
                        val serviceIntent = Intent(this@AlarmActivity, TimerForegroundService::class.java).apply {
                            action = TimerForegroundService.ACTION_STOP_TIMER
                        }
                        try {
                            startService(serviceIntent)
                        } catch (e: Exception) {
                            // In background or stopped
                        }
                        finish()
                    },
                    onAddExtraTime = { secondsToAdd ->
                        val serviceIntent = Intent(this@AlarmActivity, TimerForegroundService::class.java).apply {
                            action = TimerForegroundService.ACTION_ADD_TIME
                            putExtra(TimerForegroundService.EXTRA_ADD_SECONDS, secondsToAdd)
                        }
                        try {
                            startService(serviceIntent)
                        } catch (e: Exception) {
                            // In background or stopped
                        }
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        turnScreenOnAndShowWhenLocked()
        hideSystemUI()
    }

    override fun onResume() {
        super.onResume()
        turnScreenOnAndShowWhenLocked()
        hideSystemUI()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        turnScreenOnAndShowWhenLocked()
        hideSystemUI()
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(TimerForegroundService.ACTION_ALARM_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(stopReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun turnScreenOnAndShowWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
    }

    companion object {
        const val EXTRA_TIMER_NAME = "extra_timer_name"
    }
}

@Composable
fun AlarmScreen(
    timerName: String,
    onStop: () -> Unit,
    onAddExtraTime: (Long) -> Unit
) {
    // Screen blinking animation between bright red and dark red every 400ms
    val infiniteTransition = rememberInfiniteTransition(label = "blinkingScreen")
    val backgroundColor by infiniteTransition.animateColor(
        initialValue = Color(0xFF991B1B), // Dark Red
        targetValue = Color(0xFFEF4444),   // Bright Vibrant Red
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgColorAnimation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section - Timer Name & Icon
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Alarm Active Icon",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = timerName,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "TIME'S UP!",
                    style = MaterialTheme.typography.displayMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 44.sp,
                        textAlign = TextAlign.Center
                    )
                )
            }

            // Middle Section - Giant 00:00 Display
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = Color.Black.copy(alpha = 0.3f),
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                Text(
                    text = "00:00",
                    style = MaterialTheme.typography.displayLarge.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 72.sp
                    ),
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                )
            }

            // Bottom Section - Add Time options & Huge STOP Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                // Add Extra Time Option Buttons (+1:00, +2:00, +3:00, +5:00)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val options = listOf(
                        "+1:00" to 60L,
                        "+2:00" to 120L,
                        "+3:00" to 180L,
                        "+5:00" to 300L
                    )
                    for ((label, seconds) in options) {
                        Button(
                            onClick = { onAddExtraTime(seconds) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.25f),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                        }
                    }
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFFB91C1C)
                    ),
                    shape = RoundedCornerShape(28.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Alarm",
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "STOP",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 22.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
