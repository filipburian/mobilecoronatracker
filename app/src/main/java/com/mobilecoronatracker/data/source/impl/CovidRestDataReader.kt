package com.mobilecoronatracker.data.source.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.mobilecoronatracker.connection.HttpsConnectionFacade
import com.mobilecoronatracker.connection.impl.HttpsConnection
import com.mobilecoronatracker.data.source.CovidCountriesDataObserver
import com.mobilecoronatracker.data.source.CovidCumulatedDataObserver
import com.mobilecoronatracker.data.source.CovidDataSource
import com.mobilecoronatracker.model.CountryReportModel
import com.mobilecoronatracker.model.GeneralReportModel
import com.mobilecoronatracker.model.pojo.CovidCountryEntry
import com.mobilecoronatracker.model.pojo.CovidCumulatedData
import java.io.IOException
import java.util.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class CovidRestDataReader : CovidDataSource {
    private val baseAddress = "https://corona.lmao.ninja/"
    private val cumulatedDataEndpoint = "all"
    private val perCountryDataEndpoint = "countries"
    private val observersCumulated: LinkedList<CovidCumulatedDataObserver> = LinkedList()
    private val observersCountries: LinkedList<CovidCountriesDataObserver> = LinkedList()
    private val taskExecutor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1)
    private var refreshInterval = DEFAULT_INTERVAL

    override fun setRefreshInterval(seconds: Long) {
        if (seconds in 1..MAX_INTERVAL) refreshInterval = seconds
    }

    override fun addCovidCountriesDataObserver(observer: CovidCountriesDataObserver) {
        if (observersCountries.contains(observer)) return
        observersCountries.add(observer)
        if (observersCountries.size == 1) {
            scheduleReading(0)
        }
    }

    override fun removeCovidCountriesDataObserver(observer: CovidCountriesDataObserver) {
        observersCountries.remove(observer)
    }

    override fun addCovidCumulatedDataObserver(observer: CovidCumulatedDataObserver) {
        if (observersCumulated.contains(observer)) return
        observersCumulated.add(observer)
        if (observersCumulated.size == 1) {
            scheduleReading(0)
        }
    }

    override fun removeCovidCumulatedDataObserver(observer: CovidCumulatedDataObserver) {
        observersCumulated.remove(observer)
    }

    override fun requestData() {
        readEndpoints()
    }

    private fun scheduleReading(delay: Long) {
        taskExecutor.schedule({
            try {
                readEndpoints()
            } catch (e: RuntimeException) {
                println("Http error: " + e.message)
                e.printStackTrace()
            }
            if (observersCountries.size > 0 || observersCumulated.size > 0) {
                scheduleReading(refreshInterval)
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun connectToEndpoint(endpoint: String): HttpsConnectionFacade {
        val connectionFacade = HttpsConnection()
        connectionFacade.connect(baseAddress + endpoint)
        return connectionFacade
    }

    private fun readEndpoints() {
        var conn: HttpsConnectionFacade = connectToEndpoint(cumulatedDataEndpoint)
        val mapper = ObjectMapper()
        try {
            val data: CovidCumulatedData = mapper.readValue(
                conn.getInputStream(),
                CovidCumulatedData::class.java
            )
            observersCumulated.forEach { observer -> observer.onCumulatedData(GeneralReportModel(data)) }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        conn.disconnect()
        conn = connectToEndpoint(perCountryDataEndpoint)
        try {
            var output = conn.getInput()
            val entries = mapper.readValue(
                output,
                Array<CovidCountryEntry>::class.java
            )
            val mappedEntries =
                entries.map { covidCountryEntry -> CountryReportModel(covidCountryEntry) }
            observersCountries.forEach { observer -> observer.onCountriesData(mappedEntries) }
        } catch (e: IOException) {
            e.printStackTrace()
        }
        conn.disconnect()
    }

    companion object {
        private const val MAX_INTERVAL = 604800000L //7 days
        private const val DEFAULT_INTERVAL = 900L //15 minutes
    }
}