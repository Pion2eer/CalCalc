package ltd.pion.calcalc.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import ltd.pion.calcalc.data.DayTotal
import ltd.pion.calcalc.data.Entry
import ltd.pion.calcalc.data.EntryStatus
import ltd.pion.calcalc.data.EntryType
import ltd.pion.calcalc.data.Profile
import ltd.pion.calcalc.update.Release
import ltd.pion.calcalc.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * Draws every screen with realistic data into build/outputs/roborazzi. CI uploads the pictures, so each
 * change can be looked at before a release. Size is Dimon's phone: 406×904 dp.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w406dp-h904dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 28)

    private fun at(h: Int, m: Int) = today.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val entries = listOf(
        Entry(type = EntryType.FOOD, rawText = "овсянка с бананом", title = "Овсянка с бананом, 250 г", kcal = 320,
            protein = 9.0, fat = 6.0, carbs = 58.0, status = EntryStatus.DONE, day = today.toString(), createdAt = at(8, 30)),
        Entry(type = EntryType.FOOD, rawText = "кофе с молоком", title = "Кофе с молоком", kcal = 60, estimated = true,
            protein = 3.0, fat = 3.0, carbs = 5.0, status = EntryStatus.DONE, day = today.toString(), createdAt = at(8, 35)),
        Entry(type = EntryType.FOOD, rawText = "борщ и хлеб", title = "Борщ со сметаной и хлеб", kcal = 410,
            protein = 14.0, fat = 18.0, carbs = 45.0, status = EntryStatus.DONE, day = today.toString(), createdAt = at(13, 10)),
        Entry(type = EntryType.FOOD, rawText = "пицца", title = "пицца", kcal = null, status = EntryStatus.NEEDS_ANSWER,
            question = "Сколько кусков и какая пицца?", options = """["1 кусок","2 куска","Половина большой"]""",
            day = today.toString(), createdAt = at(14, 0)),
        Entry(type = EntryType.FOOD, rawText = "гречка с курицей", title = "гречка с курицей", kcal = null,
            status = EntryStatus.QUEUED, day = today.toString(), createdAt = at(19, 5)),
        Entry(type = EntryType.ACTIVITY, rawText = "падел 1 час", title = "Падел, 60 мин", kcal = -400,
            status = EntryStatus.DONE, day = today.toString(), createdAt = at(18, 0)),
        Entry(type = EntryType.MANUAL, rawText = "+120 печенье", title = "Печенье", kcal = 120,
            status = EntryStatus.DONE, day = today.toString(), createdAt = at(16, 30)),
    )
    private val summary = DaySummary(norm = 2100, eaten = 910, burned = 400, pending = 2)
    private val release = Release(5, "0.1.5", "", "", "Новый экран «Сегодня»")

    private fun shot(name: String, content: @Composable () -> Unit) {
        // spinners never stop, so time is moved by hand instead of waiting for the screen to settle
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CalCalcTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        // the whole screen, so dialogs (their own window) are in the picture too
        compose.mainClock.advanceTimeBy(500)
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun today(
        list: List<Entry>, s: DaySummary, update: UpdateState = UpdateState.Idle, input: String = "",
        day: LocalDate = today, blocker: String? = null,
    ) = TodayContent(day, today, list, s, update, blocker, {}, {}, {}, { _, _ -> }, {}, initialInput = input)

    @Test fun today_empty() = shot("01_today_empty") { today(emptyList(), DaySummary(2100, 0, 0, 0)) }

    @Test fun today_full() = shot("02_today_full") { today(entries, summary) }

    @Test fun today_ai_not_ready() = shot("02b_today_ai_not_ready") {
        today(entries, summary, blocker = "Войдите через Google в «Профиле», и ИИ посчитает еду")
    }

    @Test fun today_typing() = shot("03_today_typing") { today(entries, summary, input = "гречка с курицей 300 г") }

    @Test fun today_over_and_update() = shot("04_today_over_update") {
        today(entries.take(3), DaySummary(1800, 2350, 0, 0), UpdateState.Available(release), day = today.minusDays(1))
    }

    @Test
    @Config(qualifiers = "w406dp-h430dp-xxhdpi")
    fun today_keyboard_open() = shot("05_today_keyboard_open") { today(entries, summary, input = "овсянка") }

    @Test fun diary() = shot("07_diary") {
        val totals = (0 until 14).map { i ->
            val d = today.minusDays(i.toLong())
            DayTotal(d.toString(), eaten = listOf(1850, 2300, 1600, 2050, 0, 1950, 2400)[i % 7], burned = listOf(0, 400, 0, 250, 0, 0, 600)[i % 7])
        }.filter { it.eaten > 0 }
        DiaryContent(totals, 2100, today)
    }

    @Test fun diary_empty() = shot("08_diary_empty") { DiaryContent(emptyList(), 2100, today) }

    @Composable
    private fun profile(account: String?, update: UpdateState) = ProfileContent(
        Profile(true, 1990, 180, -1), 82.5, update, account, null,
        if (account != null) "https://docs.google.com/spreadsheets/d/x" else null,
        "https://script.google.com/macros/s/x/exec", true, "0.1.5",
        { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {},
    )

    @Test fun profile_signed_out() = shot("09_profile_signed_out") { profile(null, UpdateState.Idle) }

    @Test fun profile_signed_in() = shot("10_profile_signed_in") { profile("pion2er@gmail.com", UpdateState.UpToDate) }
}
