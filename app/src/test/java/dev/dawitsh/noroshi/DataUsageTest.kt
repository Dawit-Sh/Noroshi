package dev.dawitsh.noroshi

import dev.dawitsh.noroshi.core.codec.XmlNode
import dev.dawitsh.noroshi.core.model.AlertMemory
import dev.dawitsh.noroshi.core.model.AlertSettings
import dev.dawitsh.noroshi.core.model.AsrDeviceUsage
import dev.dawitsh.noroshi.core.model.AsrTraffic
import dev.dawitsh.noroshi.core.model.BundlePlan
import dev.dawitsh.noroshi.core.model.BundleProblem
import dev.dawitsh.noroshi.core.model.BundleUsage
import dev.dawitsh.noroshi.core.model.CUT_OFF_OFF
import dev.dawitsh.noroshi.core.model.CountMode
import dev.dawitsh.noroshi.core.model.DailyBaseline
import dev.dawitsh.noroshi.core.model.GB
import dev.dawitsh.noroshi.core.model.MonthUsage
import dev.dawitsh.noroshi.core.model.UsageAlert
import dev.dawitsh.noroshi.core.model.checkAlerts
import dev.dawitsh.noroshi.core.model.correction
import dev.dawitsh.noroshi.core.model.dataSize
import dev.dawitsh.noroshi.core.model.gbToBytes
import dev.dawitsh.noroshi.core.model.parseRouterDate
import dev.dawitsh.noroshi.core.model.routerDate
import dev.dawitsh.noroshi.ui.screens.BundleForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DataUsageTest {
    private fun fixture(name: String): XmlNode =
        XmlNode.parse(javaClass.getResource("/stellar/$name")!!.readText())

    private val today = LocalDate.of(2026, 11, 25)

    // --- the router's real answers --------------------------------------------------------

    @Test
    fun `real statistics answer, never set up`() {
        val t = AsrTraffic.parse(fixture("statistics.xml"))
        assertNull(t.mode)
        assertEquals(662_665_551_358L, t.usedMonth)
        assertEquals(3_143_245_111_773L, t.usedPeriod)
        assertEquals(3_143_250_143_868L, t.usedUnlimited)
        assertNull(t.sizePeriod)
        assertNull(t.periodStart)
        assertFalse(t.cutOff)
        assertNull("no bundle until one is set", t.bundle(today))
        assertEquals(t.usedUnlimited, t.lifetime)
        assertEquals(6, t.history.size)
        assertEquals(MonthUsage(2026, 6, 673_192_338_442L), t.history.first())
        assertEquals(MonthUsage(2026, 11, 662_665_023_175L), t.history.last())
    }

    @Test
    fun `real statistics answer after the app's four writes`() {
        // Written to the router on 2026-11-25 with the app's exact bodies (500 GB, 25 Nov to 25 Dec, 0 used).
        val t = AsrTraffic.parse(fixture("statistics_bundle.xml"))
        assertEquals(CountMode.Period, t.mode)
        assertFalse("the cut-off stayed off", t.cutOff)
        val b = t.bundle(today)!!
        assertEquals(gbToBytes(500.0), b.size)
        assertEquals(2_146_281L, b.used)
        assertEquals(LocalDate.of(2026, 11, 25), b.start)
        assertEquals(LocalDate.of(2026, 12, 25), b.end)
        assertEquals(30L, b.daysLeft(today))
        assertTrue(b.percent < 0.01)
        assertEquals(1_996_668_763L, t.lifetime)
        assertEquals(listOf(MonthUsage(2026, 11, 1_996_570_529L)), t.history)

        // "Disabled" (what the app's bundle is removed with) shows no bundle, though size and dates stay stored.
        val off = AsrTraffic.parse(
            XmlNode.parse(
                "<RGW><WanStatistics><stat_mang_method>0</stat_mang_method><total_available_period>536870912000</total_available_period>" +
                    "<period_start_date>26,11,25</period_start_date><period_end_date>26,12,25</period_end_date></WanStatistics></RGW>"
            )
        )
        assertEquals(CountMode.Off, off.mode)
        assertNull(off.bundle(today))
    }

    @Test
    fun `statistics with a period bundle`() {
        val xml = """<RGW><statistics><WanStatistics><stat_mang_method>2</stat_mang_method>
            <total_used_month>10</total_used_month><total_used_period>${375 * GB}</total_used_period>
            <total_used_unlimit>99</total_used_unlimit><total_available_period>${500 * GB}</total_available_period>
            <period_start_date>26,11,25</period_start_date><period_end_date>26,12,25</period_end_date>
            <dis_value_at_upper>0</dis_value_at_upper></WanStatistics></statistics></RGW>"""
        val t = AsrTraffic.parse(XmlNode.parse(xml))
        assertEquals(CountMode.Period, t.mode)
        val b = t.bundle(today)!!
        assertEquals(500 * GB, b.size)
        assertEquals(375 * GB, b.used)
        assertEquals(125 * GB, b.left)
        assertEquals(75.0, b.percent, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 25), b.start)
        assertEquals(LocalDate.of(2026, 12, 25), b.end)
        assertEquals(30L, b.daysLeft(today))
        assertFalse(t.cutOff)
    }

    @Test
    fun `monthly and unlimit bundles`() {
        val month = AsrTraffic.parse(
            XmlNode.parse("<RGW><WanStatistics><stat_mang_method>1</stat_mang_method><total_used_month>${GB}</total_used_month><total_available_month>${10 * GB}</total_available_month></WanStatistics></RGW>")
        ).bundle(today)!!
        assertEquals(LocalDate.of(2026, 11, 1), month.start)
        assertEquals(LocalDate.of(2026, 12, 1), month.end)
        assertEquals(10.0, month.percent, 1e-9)

        val unlimit = AsrTraffic.parse(
            XmlNode.parse("<RGW><WanStatistics><stat_mang_method>3</stat_mang_method><total_used_unlimit>${GB}</total_used_unlimit><total_avaliable_unlimit>${4 * GB}</total_avaliable_unlimit></WanStatistics></RGW>")
        ).bundle(today)!!
        assertNull(unlimit.end)
        assertEquals(25.0, unlimit.percent, 1e-9)
    }

    @Test
    fun `real device_management answer keeps only believable counts`() {
        val cap = AsrTraffic.parse(fixture("statistics.xml")).lifetime
        val all = AsrDeviceUsage.parse(fixture("device_management.xml"), cap)
        assertEquals(22, all.size)

        // The wired device's counts are real.
        val wired = all.single { it.mac == "AA:BB:CC:00:00:01" }
        assertEquals("device-1", wired.name)
        assertTrue(wired.connected)
        assertEquals(10_752_072_281L + 20_672_001_380L, wired.month)
        assertEquals(4_818_776_660L + 2_786_036_702L, wired.last3Days)
        assertEquals(12_844_655_658L + 28_489_987_513L, wired.total)

        // Numbers near 2^64 are dropped.
        val wifi = all.single { it.mac == "AA:BB:CC:00:00:03" }
        assertNull(wifi.month)
        assertNull(wifi.total)

        // The same filler on every offline device is dropped too.
        val offline = all.single { it.mac == "AA:BB:CC:00:00:06" }
        assertNull(offline.month)
        assertNull(offline.last3Days)

        // Unnamed devices have no name, so the screen shows the MAC.
        assertEquals("", offline.name)

        val shown = AsrDeviceUsage.shown(all)
        assertEquals(listOf("device-1", "device-2", "device-3", "device-4", "device-5").toSet(), shown.map { it.name }.toSet())
        assertEquals("device-1", shown.first().name)
    }

    // --- writing a bundle -----------------------------------------------------------------

    @Test
    fun `router dates`() {
        assertEquals("26,11,25", routerDate(LocalDate.of(2026, 11, 25)))
        assertEquals("27,1,5", routerDate(LocalDate.of(2027, 1, 5)))
        assertEquals("05,3,9", routerDate(LocalDate.of(2005, 3, 9)))
        assertEquals(LocalDate.of(2026, 12, 25), parseRouterDate("26,12,25"))
        assertEquals(LocalDate.of(2027, 1, 5), parseRouterDate(routerDate(LocalDate.of(2027, 1, 5))))
        assertNull(parseRouterDate(""))
        assertNull(parseRouterDate("26,13,40"))
    }

    @Test
    fun `bundle writes are the panel's, in order, never the cut-off`() {
        val plan = BundlePlan(gbToBytes(500.0), LocalDate.of(2026, 11, 25), LocalDate.of(2026, 12, 25), 0)
        val w = plan.writes()
        val s = "RGW/statistics/WanStatistics"
        assertEquals(
            listOf(
                listOf("$s/set_action" to "set_mang_method", "$s/stat_mang_method" to "2"),
                listOf("$s/set_action" to "set_stat_period", "$s/period_start_date" to "26,11,25", "$s/period_end_date" to "26,12,25"),
                listOf("$s/total_available_period" to "536870912000"),
                listOf("$s/set_action" to "set_stat_value_period", "$s/corrected_value_period" to "0"),
            ),
            w,
        )
        assertTrue(w.flatten().none { it.first.endsWith("dis_value_at_upper") })
        assertEquals(3, plan.copy(used = null).writes().size)
    }

    @Test
    fun `cut-off write only ever turns it off`() {
        assertEquals("0", CUT_OFF_OFF.toMap()["RGW/statistics/WanStatistics/dis_value_at_upper"])
    }

    @Test
    fun `corrections per mode`() {
        val s = "RGW/statistics/WanStatistics"
        assertEquals(listOf("$s/set_action" to "set_stat_value_period", "$s/corrected_value_period" to "7"), correction(CountMode.Period, 7))
        assertEquals(listOf("$s/set_action" to "set_stat_value_month", "$s/corrected_value_month" to "7"), correction(CountMode.Month, 7))
        assertEquals(listOf("$s/set_action" to "set_stat_value_unlimit", "$s/corrected_value_unlimit" to "7"), correction(CountMode.Unlimited, 7))
    }

    @Test
    fun `bundle checks match the panel's`() {
        val start = LocalDate.of(2026, 11, 25)
        val ok = BundlePlan(100 * GB, start, start.plusDays(30), 0)
        assertNull(ok.problem(today))
        assertEquals(BundleProblem.NoSize, ok.copy(size = 0).problem(today))
        assertEquals(BundleProblem.EndBeforeStart, ok.copy(end = start).problem(today))
        assertEquals(BundleProblem.TodayOutside, ok.copy(start = today.plusDays(1), end = today.plusDays(31)).problem(today))
        assertEquals(BundleProblem.TodayOutside, ok.copy(start = today.minusDays(40), end = today.minusDays(10)).problem(today))
        assertEquals(BundleProblem.UsedTooBig, ok.copy(used = 101 * GB).problem(today))
    }

    // --- sizes and percentages --------------------------------------------------------------

    @Test
    fun `sizes are powers of 1024, like the panel`() {
        assertEquals(536_870_912_000L, gbToBytes(500.0))
        assertEquals(1_610_612_736L, gbToBytes(1.5))
        assertEquals("617.16 GB", dataSize(662_665_551_358L))
        assertEquals("500.00 GB", dataSize(gbToBytes(500.0)))
        assertEquals("2.86 TB", dataSize(3_143_250_143_868L))
        assertEquals("1.00 MB", dataSize(1024L * 1024))
        assertEquals("900 B", dataSize(900))
        assertEquals("0 B", dataSize(-5))
    }

    @Test
    fun `percent, left, over, pace`() {
        val b = BundleUsage(500 * GB, 100 * GB, today.minusDays(9), today.plusDays(20))
        assertEquals(20.0, b.percent, 1e-9)
        assertEquals(400 * GB, b.left)
        assertEquals(10 * GB, b.perDay(today))
        assertEquals(today.plusDays(40), b.runsOutOn(today))

        val over = BundleUsage(10 * GB, 12 * GB, null, null)
        assertEquals(120.0, over.percent, 1e-9)
        assertEquals(0L, over.left)
        assertNull(over.perDay(today))
        val usedUp = BundleUsage(10 * GB, 10 * GB, today.minusDays(4), today.plusDays(5))
        assertNull("nothing left: no run-out day", usedUp.runsOutOn(today))
        assertEquals(0.0, BundleUsage(0, 5, null, null).percent, 1e-9)
    }

    // --- alerts: each threshold once per bundle ---------------------------------------------

    private val settings = AlertSettings()
    private fun bundle(usedGb: Long, endInDays: Long = 20, size: Long = 100) =
        BundleUsage(size * GB, usedGb * GB, today.minusDays(10), today.plusDays(endInDays))

    /** Runs checks one after another, keeping the memory like the worker does. */
    private fun run(steps: List<Pair<BundleUsage, LocalDate>>, s: AlertSettings = settings): List<List<UsageAlert>> {
        var memory = AlertMemory()
        return steps.map { (b, day) ->
            val check = checkAlerts(b, s, memory, day)
            memory = check.memory
            check.alerts
        }
    }

    @Test
    fun `each percentage fires once, in order`() {
        val fired = run(listOf(40L, 50L, 55L, 74L, 75L, 80L, 90L, 95L, 100L, 100L, 103L).map { bundle(it) to today })
        val used = fired.map { list -> list.filterIsInstance<UsageAlert.Used>().map { it.threshold } }
        assertEquals(
            listOf(emptyList(), listOf(50), emptyList(), emptyList(), listOf(75), emptyList(), listOf(90), emptyList(), listOf(100), emptyList(), emptyList()),
            used,
        )
        assertEquals(4, used.flatten().size)
    }

    @Test
    fun `a jump past several sends only the highest, and the lower ones never come`() {
        val fired = run(listOf(bundle(80) to today, bundle(85) to today, bundle(91) to today))
        assertEquals(listOf(75), fired[0].filterIsInstance<UsageAlert.Used>().map { it.threshold })
        assertTrue(fired[1].isEmpty())
        assertEquals(listOf(90), fired[2].filterIsInstance<UsageAlert.Used>().map { it.threshold })
    }

    @Test
    fun `a new bundle starts over`() {
        val first = bundle(100)
        val second = BundleUsage(100 * GB, 50 * GB, today, today.plusDays(30))
        val fired = run(listOf(first to today, first to today, second to today, second to today))
        assertEquals(listOf(100), fired[0].filterIsInstance<UsageAlert.Used>().map { it.threshold })
        assertTrue(fired[1].isEmpty())
        assertEquals(listOf(50), fired[2].filterIsInstance<UsageAlert.Used>().map { it.threshold })
        assertTrue(fired[3].isEmpty())
    }

    @Test
    fun `ending reminders fire once each`() {
        val end = today.plusDays(5)
        val b = BundleUsage(100 * GB, 10 * GB, today.minusDays(25), end)
        val days = (0..6).map { today.plusDays(it.toLong()) }
        val fired = run(days.map { b to it })
        val ending = fired.map { list -> list.filterIsInstance<UsageAlert.Ending>().map { it.days } }
        // 5, 4 days left: nothing; 3: "3 days"; 2: nothing; 1: "1 day"; 0 and after the end: nothing.
        assertEquals(listOf(emptyList(), emptyList(), listOf(3L), emptyList(), listOf(1L), emptyList(), emptyList()), ending)
    }

    @Test
    fun `ending, first check close to the end sends one`() {
        val b = BundleUsage(100 * GB, 10 * GB, today.minusDays(29), today.plusDays(1))
        val fired = run(listOf(b to today, b to today))
        assertEquals(listOf(1L), fired[0].filterIsInstance<UsageAlert.Ending>().map { it.days })
        assertTrue(fired[1].isEmpty())
    }

    @Test
    fun `nothing when off or no bundle`() {
        assertTrue(checkAlerts(null, settings, AlertMemory(), today).alerts.isEmpty())
        assertTrue(checkAlerts(bundle(99), settings.copy(enabled = false), AlertMemory(), today).alerts.isEmpty())
    }

    @Test
    fun `his own thresholds`() {
        val s = AlertSettings(percents = AlertSettings.parseList("25, 50;80 abc 150", 1..100), endingDays = emptyList())
        assertEquals(listOf(25, 50, 80), s.percents)
        val fired = run(listOf(bundle(30) to today, bundle(85) to today), s)
        assertEquals(listOf(listOf(25), listOf(80)), fired.map { l -> l.filterIsInstance<UsageAlert.Used>().map { it.threshold } })
    }

    // --- today --------------------------------------------------------------------------------

    @Test
    fun `today starts at the first reading of the day`() {
        val morning = LocalDateTime.of(2026, 11, 25, 0, 10)
        val b1 = DailyBaseline.next(null, morning, 1_000)
        assertFalse(b1.partial)
        val b2 = DailyBaseline.next(b1, morning.plusHours(5), 4_000)
        assertEquals(b1, b2)
        assertEquals(3_000, b2.usedSince(4_000))

        val nextDay = DailyBaseline.next(b2, LocalDateTime.of(2026, 11, 26, 9, 4), 9_000)
        assertEquals(LocalDate.of(2026, 11, 26), nextDay.date)
        assertEquals(9_000, nextDay.counter)
        assertTrue("first reading at 9:04 AM", nextDay.partial)
        assertEquals(LocalTime.of(9, 4), nextDay.takenAt)

        // A count that went back (corrected or reset) starts over.
        val reset = DailyBaseline.next(nextDay, LocalDateTime.of(2026, 11, 26, 10, 0), 5)
        assertEquals(5, reset.counter)
    }

    // --- the Set bundle form ------------------------------------------------------------------

    @Test
    fun `form keeps days and end date together`() {
        val f = BundleForm.fresh(today).copy(sizeGb = "500")
        assertEquals("2026-12-25", f.end)
        assertEquals("2026-12-10", f.withDays("15").end)
        assertEquals("10", f.withEnd("2026-12-05").days)
        assertEquals("2026-12-27", f.withStart("2026-11-27").end)
        val plan = f.plan()!!
        assertEquals(gbToBytes(500.0), plan.size)
        assertEquals(0L, plan.used)

        val left = f.copy(byLeft = true, amountGb = "123.5").plan()!!
        assertEquals(gbToBytes(500.0) - gbToBytes(123.5), left.used)

        assertNull(f.copy(sizeGb = "").plan())
        assertNull(f.copy(start = "25/11/2026").plan())
        assertEquals("500", BundleForm.gbText(gbToBytes(500.0)))
        assertEquals("1.5", BundleForm.gbText(gbToBytes(1.5)))
    }
}
