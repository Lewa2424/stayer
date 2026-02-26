package com.example.stayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.stayer.debug.PacerTestActivity
import com.example.stayer.ui.theme.StayerTheme

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StayerTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Настройки (Сервис)") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                                }
                            }
                        )
                    }
                ) { inner ->
                    Column(modifier = Modifier.padding(inner).fillMaxSize()) {
                        ListItem(
                            headlineContent = { Text("Тест пейсера (симуляция)") },
                            supportingContent = { Text("Инструмент отладки голосовых подсказок без GPS") },
                            modifier = Modifier.clickable {
                                startActivity(Intent(this@SettingsActivity, PacerTestActivity::class.java))
                            }
                        )
                    }
                }
            }
        }
    }
}
