import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import javax.xml.bind.DatatypeConverter

val baseURL = "http://build.cahcommtech.com/job/alfred-Device-Acceptance-Manual"
var buildNumber = 1465
val urlSuffix = "logText/progressiveText?start"
val finalLine = "Finished: "
val requestMethod = "GET"
val testStarted = "[SDR.run] Running"
val testEnded = "[STRL.testEnded]"
val testFailed = "[STRL.testFailed]"
val testFailedReason = "$testFailed failed"
val testCountPrepend = "[SDR.run] Active tests" // --> Need to count number of tests --> items in array
val replaceStrings: List<String> = mutableListOf(testStarted, testEnded, testFailed, "[SDR.printStream]", "test=com.cardinalhealth.alfred.patient.activity.", "STDOUT")
val dateFormat = SimpleDateFormat("yyyy-dd-MM hh:mm:ss")
val replaceTestInfo = "test=com.cardinalhealth.alfred.patient.activity."

var entries: MutableList<Entry> = mutableListOf()
var startList: MutableList<String> = mutableListOf()
var aggregationList: MutableMap<String, TabletResults> = mutableMapOf()
var tabletList: MutableList<TabletInfo> = mutableListOf()
var testGroupList: MutableMap<String, Int> = mutableMapOf()
var startIndex: Int = 0
var totalTestCount: Int = 0
var finishedProcessingBuildJob = false

fun main(args: Array<String>) {
    getContentsFromUrl()
}

fun processContents() {
    generateRunTimeStats()
    println("Test Count: ${entries.size}, Tests Passed: ${entries.filter { entry -> entry.testPassed }.count()}, Tests Failed: ${entries.filter { entry -> !entry.testPassed }.count()}")
    println("Build Number: $buildNumber")
}

fun getContentsFromUrl() {
    processNewLinesAndGetNewStartIndex(getBufferedReader())

    if (!finishedProcessingBuildJob) {
        Thread.sleep(2000)
        getContentsFromUrl()
    } else {
        buildNumber += 1
        waitForNextBuild()
    }
}

fun waitForNextBuild() {
    var tryAgain = true
    while (tryAgain) {
        if (getInputStreamFromConnection() == null) {
            println("Waiting for next build....")
            Thread.sleep(60000)
            waitForNextBuild()
        } else {
            processContents()
            resetInternals()
            getContentsFromUrl()
        }
    }
}

private fun resetInternals() {
    entries = mutableListOf()
    startList = mutableListOf()
    aggregationList = mutableMapOf()
    tabletList = mutableListOf()
    startIndex = 0
    totalTestCount = 0
    finishedProcessingBuildJob = false
}

fun convertExecutionTimeToMinutesAndSeconds(executionTime: Long): String {
    val time = executionTime / 1000
    if (time < 60) {
        return "$time sec."
    }
    val minutes = (time / 60)
    return "$minutes min. ${time - (minutes * 60)} sec."
}

private fun getBufferedReader(): BufferedReader {
    val content = getInputStreamFromConnection()
    return BufferedReader(InputStreamReader(content))
}

private fun getInputStreamFromConnection(): InputStream? {
    try {
        val url = URL("$baseURL/$buildNumber/$urlSuffix=$startIndex")
        val encoding = DatatypeConverter.printBase64Binary((System.getenv("un") + ":" + System.getenv("pw")).toByteArray(charset("utf-8")))
        val connection = url.openConnection() as HttpURLConnection

        connection.requestMethod = requestMethod
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Basic $encoding")

        return connection.inputStream
    } catch (exception: Exception) {
        return null
    }
}

private fun processNewLinesAndGetNewStartIndex(reader: BufferedReader) {
    while (true) {
        val line = reader.readLine() ?: break
        processCurrentLine(line)
    }
}

private fun generateRunTimeStats() {
    aggregationList.forEach { entry: Map.Entry<String, TabletResults> -> println("Tablet: ${entry.key}, Number of Tests: ${entry.value.numberOfTests} , Total Execution Time: ${convertExecutionTimeToMinutesAndSeconds(entry.value.totalRunTime)}") }
}

private fun processCurrentLine(line: String) {
    when {
        line.matches("[a-zA-Z\\d]*\\tdevice".toRegex()) -> addTabletToList(line)
        line.contains(testCountPrepend) -> parseSpoon2TestCount(line)
        line.contains(testStarted) -> parseStart(line)
        line.contains(testEnded) || line.contains(testFailed) -> parseEntry(line)
    }

    startIndex += line.toByteArray().size
    finishedProcessingBuildJob = line.startsWith(finalLine)
}

fun parseStart(line: String) {
    startList.add(line)

    val testName = line.substringAfter("Running ").substringBefore(" [")
    if (testGroupList.count() > 0) {
        var tabletId = testGroupList[testName]
        var trueTabletId = line.substringAfterLast('[').substringBefore(']')
        val currentTablet = tabletList.filter { tablet -> tablet.tabletId.equals(trueTabletId) }.first()

        if (tabletId != null && trueTabletId != null && currentTablet.tests.isEmpty()) {
            var tests = testGroupList.filter { entry -> entry.value.equals(tabletId) }
//            tabletList.add(TabletInfo(trueTabletId, tests.count(), tests.count()))
            tests.forEach { test -> testGroupList.remove(test.key) }

            currentTablet.testRemainingCount = tests.count()
            currentTablet.totalTestCount = tests.count()
            currentTablet.tests = tests.keys.toList()
            totalTestCount += tests.count()

            if (!tabletList.any { tablet -> tablet.totalTestCount == 0 }) {
                println("Total Test Count: $totalTestCount")
                tabletList.forEach { tablet -> println("Tablet: ${tablet.tabletId} - Test Count: ${tablet.totalTestCount}") }
            }
        }
    }
}

fun addTabletToList(line: String) {
    tabletList.add(TabletInfo(line.substringBefore("\t"), 0, 0, mutableListOf()))
}

fun parseSpoon2TestCount(line: String) {
    val tabletCount = if (testGroupList.count() == 0) {
        1
    } else {
        val largestEntry = testGroupList.maxBy { entry -> entry.value }
        largestEntry!!.value + 1
    }

    line.substringAfter("tests: [").substringBefore(']').split(", ").forEach { entry -> testGroupList.put(entry, tabletCount) }
}


fun parseEntry(contents: String) {
    try {
        if (contents.contains(testFailedReason)) {
            return
        }

        val entryEndDateTime = getDateString(contents)
        val (testName, tabletId) = getTestNameAndTabletId(contents.replace(entryEndDateTime, ""))
        val executionTime = getTestExecutionTime(testName, entryEndDateTime)
        val testPassed = contents.contains(testEnded)
        val status = if (testPassed) {
            "PASSED"
        } else {
            "FAILED"
        }
        val tablets = tabletList.filter { tablet -> tabletId.equals(tablet.tabletId) }

        if (tablets.isNotEmpty()) {
            var tablet = tablets[0]
            tablet.testRemainingCount -= 1
            totalTestCount -= 1

            println("Test $status: ${testName.replace(replaceTestInfo, "")}")
            println("     Tablet Id: $tabletId, Execution Time: ${convertExecutionTimeToMinutesAndSeconds(executionTime)}")
            println("     Tests Remaining For Tablet: ${tablet.testRemainingCount}/${tablet.totalTestCount}, Total Tests Remaining: $totalTestCount")

            populateAggregateMap(tabletId, executionTime)
            entries.add(Entry(testName, tabletId, executionTime, testPassed))
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
        println("ERROR PARSING ENTRY: $contents")
    }
}

private fun getTestExecutionTime(testName: String, entryEndDateTime: String): Long {
    val entryStartDateTime = getDateString(getCorrespondingStartEntry(testName))
    val startDT = dateFormat.parse(entryStartDateTime)
    val endDT = dateFormat.parse(entryEndDateTime)

    return endDT.time - startDT.time
}

private fun getCorrespondingStartEntry(testName: String): String {
    return startList.first { entry: String -> entry.contains(testName) }
}

private fun getTestNameAndTabletId(contents: String): Pair<String, String> {
    val parts = contents.split(' ').filter { c -> !replaceStrings.contains(c) && c.isNotEmpty() }
    val testName = parts[1]
    var tabletId: String = ""

    try {
        tabletId = tabletList.filter { tablet -> tablet.tests.contains(testName) }[0].tabletId
    } catch (ex: Exception) {
        println("ERROR YOU SUCK")
    }
    return Pair(testName, tabletId)
}

fun getDateString(rawData: String): String {
    return rawData.substring(0, 19)
}

private fun populateAggregateMap(tabletId: String, executionTime: Long) {
    when {
        !aggregationList.containsKey(tabletId) -> aggregationList.put(tabletId, TabletResults(1, executionTime))
        else -> {
            val tabletResults = aggregationList[tabletId]
            val numberOfTests = tabletResults?.numberOfTests ?: throw RuntimeException("a description of this epic failure")
            aggregationList[tabletId] = TabletResults(numberOfTests.plus(1), tabletResults.totalRunTime.plus(executionTime))
        }
    }
}