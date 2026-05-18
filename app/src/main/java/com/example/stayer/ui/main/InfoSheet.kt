package com.example.stayer.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stayer.R

@Composable
fun InfoBottomSheetContent(
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.info_instruction_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Stayer — приложение для беговых тренировок с голосовым ведением темпа, GPS-трекингом, резервным расчётом дистанции по шагам, историей и аналитикой.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("🏃 РЕЖИМЫ ТРЕНИРОВОК")
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("1. Обычная тренировка")
        InfoText(
            "Классический режим: задаёте целевую дистанцию и время или целевой темп. Приложение считает нужный график и каждые 10% дистанции подсказывает, нужно ли ускориться или замедлиться."
        )
        InfoText(
            "• Дистанция — в формате КМ.МЕТРЫ, например 05.50 = 5 км 500 м\n" +
                "• Время — в формате ЧЧ:ММ:СС\n" +
                "• Либо задайте темп в формате ММ:СС/км, тогда время рассчитается автоматически"
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("2. Интервальная тренировка")
        InfoText(
            "Структурированный режим с разминкой, рабочими интервалами, отдыхом, повторами и заминкой. Подходит для скоростной работы и контроля серий."
        )
        InfoText(
            "Приложение озвучивает старт каждой фазы, номер текущей серии, обратный отсчёт перед сменой участка и собирает по участкам отдельную статистику."
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("3. Комбинированная тренировка")
        InfoText(
            "Гибкий конструктор тренировки из блоков. Можно собрать произвольную последовательность: разминка, обычный бег по темпу, интервалы, заминка."
        )
        InfoText(
            "Порядок блоков на экране настройки — это порядок выполнения. Для каждого блока сохраняется отдельная история по сегментам."
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("4. Свободный бег")
        InfoText(
            "Простой режим без цели по времени и без сценариев. Подходит, когда нужно просто бежать и получать базовые голосовые отметки."
        )
        InfoText(
            "Каждый полный километр Stayer озвучивает:\n" +
                "• общую дистанцию\n" +
                "• общее время\n" +
                "• средний темп за всю тренировку"
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("5. Забег")
        InfoText(
            "Режим для соревнований и темпового плана по участкам. Вы задаёте общую дистанцию забега и несколько участков с разными целевыми темпами."
        )
        InfoText(
            "Stayer работает по общей дистанции забега:\n" +
                "• озвучивает чекпоинты каждые 10% дистанции\n" +
                "• предупреждает о переходе на следующий участок и новый темп\n" +
                "• сообщает ориентировочное опережение или отставание от плана\n" +
                "• сохраняет отдельную статистику по каждому участку"
        )
        Spacer(Modifier.height(12.dp))

        InfoText(
            "📋 После сохранения цели на главном экране показывается карточка с кратким описанием текущего режима и плана.",
            bold = true
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("▶️ УПРАВЛЕНИЕ ТРЕНИРОВКОЙ")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "• Старт — нажмите центральную кнопку\n" +
                "• Пауза — нажмите её во время тренировки\n" +
                "• Продолжить — нажмите повторно\n" +
                "• Стоп и сохранение — удерживайте кнопку более 1 секунды"
        )
        InfoText(
            "После остановки результаты автоматически сохраняются в историю."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("📊 ГЛАВНЫЙ ЭКРАН И GPS")
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Индикатор GPS")
        InfoText(
            "Перед стартом смотрите на индикатор над центральной кнопкой:\n" +
                "🟢 GPS готов — можно начинать\n" +
                "🟡 сигнал уточняется — лучше подождать, но тренировка уже может стартовать\n" +
                "🔴 сигнал слабый — желательно выйти на более открытое место"
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Карточки статистики")
        InfoText(
            "На главном экране отображаются основные показатели:\n" +
                "• время\n" +
                "• дистанция\n" +
                "• темп\n" +
                "• цель / активный режим"
        )
        InfoText(
            "Во время интервальной и комбинированной тренировки карточки могут временно переключаться на текущую фазу, номер серии и обратный отсчёт."
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Резерв по шагам")
        InfoText(
            "Если GPS временно пропадает, Stayer использует шагомер и последнюю известную динамику, чтобы не терять дистанцию в тоннелях, плотной застройке и других проблемных местах."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("🔊 ГОЛОСОВОЙ ПОМОЩНИК")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "Озвучка зависит от режима:\n" +
                "• Обычная — чекпоинты по 10% дистанции и подсказка по графику\n" +
                "• Интервальная — смена фаз, серии, обратный отсчёт\n" +
                "• Комбо — переходы между блоками и подсказки по блоку\n" +
                "• Свободный бег — отчёт каждый километр\n" +
                "• Забег — глобальные чекпоинты, смены участков и ориентир по плану"
        )
        InfoText(
            "Громкость подсказок зависит от системной громкости мультимедиа на устройстве."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("🕘 ИСТОРИЯ И АНАЛИТИКА")
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("История")
        InfoText(
            "Кнопка истории открывает список сохранённых тренировок. Карточки разворачиваются по нажатию и показывают подробности по режиму."
        )
        InfoText(
            "Что хранится:\n" +
                "• обычная — факт по дистанции, времени и чекпоинтам\n" +
                "• интервальная — сегменты работы и отдыха\n" +
                "• комбо — блоки сценария\n" +
                "• свободный бег — общий итог без сценария\n" +
                "• забег — общий итог и отдельные участки с планом и фактом"
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Аналитика")
        InfoText(
            "Экран аналитики собирает отчёт отдельно по каждому режиму. Можно смотреть суммарную дистанцию, время, средние темпы, лучшие и слабые участки, а для забега — ещё и отклонение от плана."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("🧮 ДОПОЛНИТЕЛЬНЫЕ ИНСТРУМЕНТЫ")
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Калькулятор темпа")
        InfoText(
            "Кнопка слева на главном экране открывает калькулятор темпа. Введите любые два параметра:\n" +
                "• дистанция\n" +
                "• время\n" +
                "• темп\n" +
                "и приложение рассчитает третий."
        )
        InfoText(
            "Калькулятор принимает:\n" +
                "• дистанцию в километрах\n" +
                "• время в формате ММ:СС или ЧЧ:ММ:СС\n" +
                "• темп в формате ММ:СС/км"
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("⚙️ НАСТРОЙКИ")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "В настройках можно управлять голосовыми подсказками и другими параметрами поведения приложения."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        SectionTitle("✉️ ОБРАТНАЯ СВЯЗЬ")
        Spacer(Modifier.height(12.dp))

        Text(
            text = "Об ошибках и пожеланиях пишите на почту:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(
                text = stringResource(R.string.info_email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "(удерживайте, чтобы выделить и скопировать)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.info_close))
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold
    )
}

@Composable
internal fun SubSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun InfoText(text: String, bold: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.let {
            if (bold) it.copy(fontWeight = FontWeight.Medium) else it
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
