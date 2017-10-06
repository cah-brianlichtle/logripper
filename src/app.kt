import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import javax.xml.bind.DatatypeConverter

val BASE_URL = "http://build.cahcommtech.com/job/alfred-Device-Acceptance-Manual"
val BUILD_NUMBER = "1380"
val URL_END = "logText/progressiveText?start"
val FINAL_LINE = "Finished: "
val REQUEST_METHOD = "GET"
val TEST_STARTED = "[STRL.testStarted]"
val TEST_ENDED = "[STRL.testEnded]"
val TEST_FAILED = "[STRL.testFailed]"
val TEST_FAILED_REASON = "$TEST_FAILED failed"
val REPLACE_STRINGS: List<String> = mutableListOf(TEST_STARTED,TEST_ENDED,TEST_FAILED,"[SDR.printStream]","test=com.cardinalhealth.alfred.patient.activity.","STDOUT")
val DATE_FORMAT = SimpleDateFormat("yyyy-dd-MM hh:mm:ss")

var entries: MutableList<Entry> = mutableListOf()
var startList: MutableList<String> = mutableListOf()
var aggregationList: MutableMap<String, TabletResults> = mutableMapOf()
var startIndex: Int = 0
var finishedProcessingBuildJob = false

fun main(args: Array<String>) {
    getContentsFromUrl()
    processContents()
}

fun processContents() {
    generateRunTimeStats()
    println("Test Count: ${entries.size}, Tests Passed: ${entries.filter{entry -> entry.testPassed }.count()}, Tests Failed: ${entries.filter{entry -> !entry.testPassed }.count()}")
}

fun getContentsFromUrl()
{
    processNewLinesAndGetNewStartIndex(getBufferedReader())

    if (!finishedProcessingBuildJob) {
        Thread.sleep(2000)
        getContentsFromUrl()
    }
}

fun convertExecutionTimeToMinutesAndSeconds(executionTime: Long): String  {
    var time = executionTime / 1000
    if (time < 60) {
        return "$time sec."
    }
    var minutes = (time / 60)
    return "$minutes min. ${time - (minutes * 60)} sec."
}

private fun getBufferedReader(): BufferedReader {
    var content = getInputStreamFromConnection()
    return BufferedReader(InputStreamReader(content))
}

private fun getInputStreamFromConnection(): InputStream {
    val url = URL ("$BASE_URL/$BUILD_NUMBER/$URL_END=$startIndex")
    val encoding = DatatypeConverter.printBase64Binary((System.getenv("un") + ":" +  System.getenv("pw")).toByteArray(charset("utf-8")))
    val connection = url.openConnection() as HttpURLConnection

    connection.requestMethod = REQUEST_METHOD
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Basic $encoding")

    return connection.inputStream
}

private fun processNewLinesAndGetNewStartIndex(reader: BufferedReader){
    while (true) {
        var line = reader.readLine() ?: break
        processCurrentLine(line)
    }
}

private fun generateRunTimeStats() {
    aggregationList.forEach { entry: Map.Entry<String, TabletResults> -> println("Tablet: ${entry.key}, Number of Tests: ${entry.value.numberOfTests} , Total Execution Time: ${convertExecutionTimeToMinutesAndSeconds(entry.value.totalRunTime)}") }
}

private fun processCurrentLine(line: String) {
    if (line.contains(TEST_STARTED)) {
        startList.add(line)
    } else if (line.contains(TEST_ENDED) || line.contains(TEST_FAILED)) {
        parseEntry(line)
    }

    startIndex += line.toByteArray().size
    finishedProcessingBuildJob = line.startsWith(FINAL_LINE)
}

fun parseEntry(contents: String) {
    try {
        if (!contents.contains(TEST_FAILED_REASON)) {
            val entryEndDateTime = getDateString(contents)
            val (testName, tabletId) = getTestNameAndTabletId(contents.replace(entryEndDateTime,""))
            val executionTime = getTestExecutionTime(testName, entryEndDateTime)
            val testPassed = contents.contains(TEST_ENDED)

            println("Test: $testName, Execution Time: ${ convertExecutionTimeToMinutesAndSeconds(executionTime) }, Tablet Id: $tabletId, Test Passed: $testPassed")

            populateAggregateMap(tabletId, executionTime)
            entries.add(Entry(testName, tabletId, executionTime, testPassed))
        }
    } catch (ex: Exception) {
        println("ERROR PARSING ENTRY: $contents")
    }
}

private fun getTestExecutionTime(testName: String, entryEndDateTime: String): Long {
    val entryStartDateTime = getDateString(getCorrespondingStartEntry(testName))
    val startDT = DATE_FORMAT.parse(entryStartDateTime)
    val endDT = DATE_FORMAT.parse(entryEndDateTime)

    return endDT.time - startDT.time
}

private fun getCorrespondingStartEntry(testName: String): String {
    return startList.first { entry: String -> entry.contains(testName) }
}

private fun getTestNameAndTabletId(contents: String): Pair<String, String> {
    var parts = contents.split(' ').filter{ c -> !REPLACE_STRINGS.contains(c) && c.isNotEmpty()}
    var testName = parts[1]
    var tabletId = parts[0].replace("[","").replace("]","")
    return Pair(testName, tabletId)
}

fun getDateString (rawData: String): String {
    return rawData.substring(0,19)
}

private fun populateAggregateMap(tabletId: String, executionTime: Long) {
    if (!aggregationList.containsKey(tabletId)) {
        aggregationList.put(tabletId, TabletResults(1, executionTime))
    } else {
        val tabletResults = aggregationList[tabletId]
        val numberOfTests = tabletResults?.numberOfTests ?: throw RuntimeException("a description of this epic failure")
        aggregationList[tabletId] = TabletResults(numberOfTests.plus(1), tabletResults.totalRunTime.plus(executionTime))
    }
}