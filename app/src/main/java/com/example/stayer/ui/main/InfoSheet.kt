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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stayer.R

@Composable
fun InfoBottomSheetContent(
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // ── Заголовок ──
        Text(
            text = stringResource(R.string.info_instruction_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))

        // ── О приложении ──
        Text(
            text = "Stayer \u2014 умное приложение для отслеживания и ведения темпа во время беговых тренировок. " +
                    "Отслеживает дистанцию по GPS (с умной страховкой по шагомеру в слепых зонах) " +
                    "и даёт голосовые подсказки, чтобы вы достигли цели вовремя.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // РЕЖИМЫ ТРЕНИРОВОК
        // ══════════════════════════════════
        SectionTitle("\uD83C\uDFC3 РЕЖИМЫ ТРЕНИРОВОК")
        Spacer(Modifier.height(12.dp))

        // --- Обычная ---
        SubSectionTitle("1. Обычная тренировка")
        InfoText(
            "Классический режим: задаёте целевую дистанцию и время (или темп), " +
                    "и бежите. Приложение рассчитает необходимый темп и будет " +
                    "подсказывать голосом, нужно ли ускориться или замедлиться."
        )
        InfoText(
            "\u2022 Дистанция \u2014 в формате КМ.МЕТРЫ (например, 05.50 = 5 км 500 м)\n" +
                    "\u2022 Время \u2014 в формате ЧЧ:ММ:СС (например, 00:30:00 = 30 минут)\n" +
                    "\u2022 Или задайте целевой темп (ММ:СС на км) \u2014 время рассчитается автоматически"
        )
        Spacer(Modifier.height(12.dp))

        // --- Интервальная ---
        SubSectionTitle("2. Интервальная тренировка")
        InfoText(
            "Структурированная тренировка с чередованием работы и отдыха. " +
                    "Идеальна для развития скорости и выносливости."
        )
        InfoText(
            "\u2022 Разминка \u2014 продолжительность и темп\n" +
                    "\u2022 Рабочий интервал \u2014 длительность и целевой темп\n" +
                    "\u2022 Отдых \u2014 длительность и темп (или свободный)\n" +
                    "\u2022 Количество повторений \u2014 сколько раз повторить цикл работа+отдых\n" +
                    "\u2022 Заминка \u2014 продолжительность и темп\n" +
                    "\nГолосовой помощник объявляет каждую смену фазы и текущую серию."
        )
        Spacer(Modifier.height(12.dp))

        // --- Комбинированная ---
        SubSectionTitle("3. Комбинированная тренировка")
        InfoText(
            "Гибкий конструктор \u2014 добавляйте любые блоки в произвольном порядке:"
        )
        InfoText(
            "\u2022 Разминка (Разм.) \u2014 время + опциональный темп\n" +
                    "\u2022 Обычный бег (Обыч.) \u2014 дистанция + целевой темп\n" +
                    "\u2022 Интервалы (Интерв.) \u2014 работа/отдых \u00d7 повторы + темп\n" +
                    "\u2022 Заминка (Замин.) \u2014 время + опциональный темп\n" +
                    "\nДобавляйте и удаляйте блоки кнопками \u00ab+ Добавить\u00bb и \u00ab\u2212\u00bb. " +
                    "Порядок блоков \u2014 порядок выполнения."
        )
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\uD83D\uDCCB Превью: после сохранения цели на главном экране появится " +
                    "карточка с планом тренировки \u2014 удобно перепроверить перед стартом.",
            bold = true
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // УПРАВЛЕНИЕ ТРЕНИРОВКОЙ
        // ══════════════════════════════════
        SectionTitle("\u25B6\uFE0F УПРАВЛЕНИЕ ТРЕНИРОВКОЙ")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\u2022 Старт \u2014 нажмите медаль в центре экрана\n" +
                    "\u2022 Пауза \u2014 нажмите медаль во время тренировки\n" +
                    "\u2022 Продолжить \u2014 нажмите медаль повторно\n" +
                    "\u2022 Стоп и сохранение \u2014 удерживайте медаль более 1 секунды\n" +
                    "\nПосле остановки результаты автоматически сохраняются в историю."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ГЛАВНЫЙ ЭКРАН И GPS
        // ══════════════════════════════════
        SectionTitle("\uD83D\uDCCA ГЛАВНЫЙ ЭКРАН И GPS")
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Индикатор GPS (над кнопкой Старт)")
        InfoText(
            "Перед началом тренировки обратите внимание на цвет индикатора:\n" +
                    "\uD83D\uDFE2 Зеленый («GPS готов») — отличная точность (<15м). Можно начинать бег, дистанция будет считаться максимально точно с первого шага.\n" +
                    "\uD83D\uDFE1 Желтый («Уточняем сигнал...») — средняя точность (16-30м). Часто бывает в помещении из-за Wi-Fi навигации. Если нажать старт, первые метры на улице будут считаться шагомером до захвата спутников.\n" +
                    "\uD83D\uDD34 Серый/Красный («Поиск спутников...») — плохой сигнал. Подождите на открытом месте."
        )
        Spacer(Modifier.height(12.dp))

        SubSectionTitle("Карточки статистики")
        InfoText(
            "В нижней панели отображаются 4 карточки:\n" +
                    "\u2022 Время \u2014 текущее время тренировки\n" +
                    "\u2022 Дистанция \u2014 пройденное расстояние (GPS + шагомер)\n" +
                    "\u2022 Темп \u2014 текущий темп в мин/км (обновляется каждые 15 сек)\n" +
                    "\u2022 Цель \u2014 нажмите для настройки целей и режима"
        )
        Spacer(Modifier.height(8.dp))

        SubSectionTitle("Во время интервала / комбо")
        InfoText(
            "Карточки автоматически переключаются:\n" +
                    "\u2022 Время \u2192 Название фазы\n" +
                    "\u2022 Дистанция \u2192 Номер серии (например, 3/8)\n" +
                    "\u2022 Темп \u2192 Осталось (обратный отсчёт до конца фазы)\n\n" +
                    "\uD83D\uDCA1 При потере GPS (туннель, лес) включится умный шагомер: " +
                    "он запоминает длину вашего шага и продолжает точно считать дистанцию."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ГОЛОСОВОЙ ПОМОЩНИК
        // ══════════════════════════════════
        SectionTitle("\uD83D\uDD0A ГОЛОСОВОЙ ПОМОЩНИК")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "Stayer озвучивает подсказки во время тренировки:\n\n" +
                    "\u2022 Каждые 10% от целевой дистанции \u2014 текущее время, дистанция, темп и рекомендация\n" +
                    "\u2022 При отклонении от темпа \u2014 \u00abускоряйтесь\u00bb или \u00abзамедляйтесь\u00bb\n" +
                    "\u2022 Смена фазы (интервалы/комбо) \u2014 название фазы, номер серии\n" +
                    "\u2022 Обратный отсчёт \u2014 последние 5 секунд перед сменой фазы\n\n" +
                    "Громкость подсказок зависит от громкости медиа на устройстве."
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ИСТОРИЯ
        // ══════════════════════════════════
        SectionTitle("\uD83D\uDCDA ИСТОРИЯ ТРЕНИРОВОК")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\u2022 Нажмите иконку Истории в правом верхнем углу\n" +
                    "\u2022 Появится список красивых карточек-аккордеонов\n" +
                    "\u2022 В шапке указана ваша Цель. Нажмите на карточку, чтобы развернуть детальную статистику (факт, темп, средний темп по фазам работы и отдыха)\n" +
                    "\u2022 Для удаления записи нажмите иконку корзины"
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // НАСТРОЙКИ
        // ══════════════════════════════════
        SectionTitle("\u2699\uFE0F НАСТРОЙКИ")
        Spacer(Modifier.height(12.dp))

        InfoText(
            "\u2022 Иконка шестерёнки в правом верхнем углу\n" +
                    "\u2022 Здесь можно настроить голосовые оповещения и другие параметры"
        )
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        // ══════════════════════════════════
        // ОБРАТНАЯ СВЯЗЬ
        // ══════════════════════════════════
        SectionTitle("\u2709\uFE0F ОБРАТНАЯ СВЯЗЬ")
        Spacer(Modifier.height(12.dp))

        Text(
            text = "Обо всех ошибках и пожеланиях пишите на почту:",
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
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onOpenSetup,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setup_check_button))
        }
    }
}

// ── Info sheet helper composables ──

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
