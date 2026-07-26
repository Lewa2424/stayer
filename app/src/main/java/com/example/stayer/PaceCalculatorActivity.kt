package com.example.stayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stayer.calculator.PaceCalculatorEngine
import com.example.stayer.calculator.PaceCalculatorInput
import com.example.stayer.calculator.PaceCalculatorResult
import com.example.stayer.calculator.PaceCalculatorValidator
import com.example.stayer.calculator.formatDistanceKm
import com.example.stayer.calculator.formatDurationSec
import com.example.stayer.calculator.formatPaceSecPerKm
import com.example.stayer.calculator.parseDistanceKm
import com.example.stayer.calculator.parseDurationSec
import com.example.stayer.calculator.parsePaceSecPerKm
import com.example.stayer.ui.theme.StayerTheme

private val CalcBg = Color(0xFFF8F9FA)
private val CalcCard = Color(0xFFFFFFFF)
private val CalcBlue = Color(0xFF0052FF)
private val CalcOrange = Color(0xFFFF6B00)
private val CalcText = Color(0xFF163A70)
private val CalcSubtle = Color(0xFF5F6F85)
private val CalcStroke = Color(0xFFD9E6F5)
private val CalcFont = FontFamily(Font(R.font.arista_pro))

class PaceCalculatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StayerTheme {
                PaceCalculatorScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaceCalculatorScreen(
    onBack: () -> Unit
) {
    var distanceText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    var paceText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PaceCalculatorResult?>(null) }

    Scaffold(
        containerColor = CalcBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Калькулятор темпа",
                        color = CalcText,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = CalcFont
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = CalcBlue
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CalcBg)
                .padding(inner)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = CalcCard,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Заполните любые 2 поля. Третье будет рассчитано автоматически.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CalcSubtle
                    )

                    CalculatorFieldRow(
                        value = distanceText,
                        onValueChange = {
                            distanceText = it
                            errorText = null
                            result = null
                        },
                        placeholder = "например 10.00",
                        label = "км",
                        keyboardType = KeyboardType.Decimal
                    )

                    CalculatorFieldRow(
                        value = durationText,
                        onValueChange = {
                            durationText = it
                            errorText = null
                            result = null
                        },
                        placeholder = "например 00:50:00",
                        label = "время",
                        keyboardType = KeyboardType.Text
                    )

                    CalculatorFieldRow(
                        value = paceText,
                        onValueChange = {
                            paceText = it
                            errorText = null
                            result = null
                        },
                        placeholder = "например 05:00",
                        label = "мин/км",
                        keyboardType = KeyboardType.Text
                    )

                    Button(
                        onClick = {
                            val input = PaceCalculatorInput(
                                distanceKm = parseDistanceKm(distanceText),
                                durationSec = parseDurationSec(durationText),
                                paceSecPerKm = parsePaceSecPerKm(paceText)
                            )

                            val validationError = PaceCalculatorValidator.validate(input)
                            if (validationError != null) {
                                errorText = validationError
                                result = null
                                return@Button
                            }

                            result = runCatching { PaceCalculatorEngine.calculate(input) }.getOrNull()
                            errorText = if (result == null) "Не удалось выполнить расчёт. Проверьте формат полей." else null

                            result?.let {
                                if (distanceText.isBlank()) distanceText = formatDistanceKm(it.distanceKm!!)
                                if (durationText.isBlank()) durationText = formatDurationSec(it.durationSec!!)
                                if (paceText.isBlank()) paceText = formatPaceSecPerKm(it.paceSecPerKm!!)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CalcOrange,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Рассчитать")
                    }
                }
            }

            if (errorText != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = CalcCard,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorText!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (result != null) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = CalcCard,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Результат",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = CalcText
                            )
                        )
                        Text(
                            text = "Дистанция: ${formatDistanceKm(result!!.distanceKm!!)} км",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CalcBlue
                        )
                        Text(
                            text = "Время: ${formatDurationSec(result!!.durationSec!!)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CalcOrange
                        )
                        Text(
                            text = "Темп: ${formatPaceSecPerKm(result!!.paceSecPerKm!!)} /км",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CalcText
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalculatorFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    keyboardType: KeyboardType
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(0.62f),
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CalcBlue,
                unfocusedBorderColor = CalcStroke,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedTextColor = CalcText,
                unfocusedTextColor = CalcText,
                cursorColor = CalcOrange
            )
        )
        Text(
            text = label,
            modifier = Modifier
                .width(96.dp)
                .padding(start = 12.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            ),
            color = CalcText,
            textAlign = TextAlign.Start
        )
    }
}
