import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.text.SimpleDateFormat
import javax.xml.bind.DatatypeConverter

val UN = ""
val PW = ""





val BASE_URL = "http://build.cahcommtech.com/job/alfred-Device-Acceptance-Develop"
val BUILD_NUMBER = "602"
val URL_END = "logText/progressiveText?start"
val FINAL_LINE = "Finished: "

val TEST_STARTED = "testStarted"
val TEST_ENDED = "testEnded"
val TEST_FAILED = "testFailed"

var entries: MutableList<Entry> = mutableListOf()
var startList: MutableList<String> = mutableListOf()
var aggregationList: MutableMap<String, TabletResults> = mutableMapOf()
var startIndex: Int = 0

fun main(args: Array<String>) {
    getContentsFromUrl()
    processContents()
}

fun processContents() {
    generateRunTimeStats()
    println("Entry Count: " + entries.size)
}

fun getContentsFromUrl()
{
    try {
        var continueRequestLoop = true
        while (continueRequestLoop) {
            var content = getInputStreamFromConnection()
            val reader = BufferedReader (InputStreamReader (content))

            val isLastLine = processNewLinesAndGetNewStartIndex(reader)

            if (isLastLine) {
                break
            } else {
                Thread.sleep(2000)
            }
        }
    } catch (e: Exception) {
        println(e.toString())
    }
}

private fun getInputStreamFromConnection(): InputStream {
    val url = URL ("$BASE_URL/$BUILD_NUMBER/$URL_END=$startIndex")
    val encoding = DatatypeConverter.printBase64Binary((UN + ":" +  PW).toByteArray(charset("utf-8")))

    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Basic " + encoding)

    return connection.inputStream
}

private fun processNewLinesAndGetNewStartIndex(reader: BufferedReader): Boolean {
    var isLastLine = false

    while (true) {
        var line = reader.readLine() ?: break

        getStartAndEndLists(line)
        println(line)
        startIndex += line.toByteArray().size
        isLastLine = line.startsWith(FINAL_LINE)
    }

    return isLastLine
}

private fun generateRunTimeStats() {
    aggregationList.forEach { entry: Map.Entry<String, TabletResults> -> println("Tablet: " + entry.key + ", Number of Tests: " + entry.value.numberOfTests + ", Total Execution Time: " + entry.value.totalRunTime) }
}

private fun getStartAndEndLists(contents: String) {
    if (contents.contains(TEST_STARTED)) {
        startList.add(contents)
    } else if (contents.contains(TEST_ENDED) || contents.contains(TEST_FAILED)) {
        parseEntry(contents)
    }
}

fun parseEntry(contents: String) {
    try {
        val entryEndDateTime = getDateString(contents)
        val massagedEntry = contents.replace("[SDR.printStream] [", "")
                .replace("] STDOUT", "")
                .replace(entryEndDateTime,"")
                .replace(" [STRL.testFailed]", "")
                .replace(" [STRL.testEnded]", "")
                .replace("test=com.cardinalhealth.alfred.patient.", "")
        val parts = massagedEntry.trim().split(' ')
        val name = parts[2]
        val tabletId = parts[0]

        val startEntry: String = startList.first{ entry: String -> entry.contains(name) }
        val entryStartDateTime = getDateString(startEntry)

        val startDT = translateDateTime(entryStartDateTime)
        val endDT = translateDateTime(entryEndDateTime)
        val executionTime = endDT.time - startDT.time

        populateAggregateMap(tabletId, executionTime)

        println("Test: $name, Execution Time: $executionTime, Tablet Id: $tabletId")
        entries.add(Entry(name, tabletId, executionTime))
    } catch (ex: Exception) {
        println("ERROR: $contents")
    }

}

fun translateDateTime (data: String): Date {
    val dateFormat = SimpleDateFormat("yyyy-dd-MM hh:mm:ss")
    return dateFormat.parse(data)
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