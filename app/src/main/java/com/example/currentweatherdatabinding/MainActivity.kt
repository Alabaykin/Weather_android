package com.example.currentweatherdatabinding

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.lifecycle.lifecycleScope
import com.example.currentweatherdatabinding.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/** Observable state used by the XML data-binding expressions. */
class WeatherViewModel {
    val cityName = ObservableField("Погода")
    val temperature = ObservableField("—°C")
    val temperatureCelsius = ObservableField(0.0)
    val feelsLikeCelsius = ObservableField(0.0)
    val details = ObservableField("")
    val windDirection = ObservableField("")
    val description = ObservableField("Выберите город")
    val humidity = ObservableField(0)
    val windSpeed = ObservableField(0.0)
    val status = ObservableField("")
    val loading = ObservableBoolean(false)

    var onRefresh: (() -> Unit)? = null
    fun refresh() = onRefresh?.invoke()
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val weather = WeatherViewModel()
    private var weatherRequest: Job? = null
    private var selectedCity = "Иркутск"
    private var useFahrenheit = false
        set(value) {
            field = value
            renderWeather()
        }
    private var showWindDirection = false
        set(value) {
            field = value
            renderWeather()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.weather = weather
        binding.citySpinner.setSelection(0, false)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(24, bars.top + 16, 24, bars.bottom + 16)
            insets
        }

        binding.citySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedCity = resources.getStringArray(R.array.cities)[position]
                loadWeather()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.unitsRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            useFahrenheit = checkedId == R.id.fahrenheitRadio
        }
        binding.windDirectionCheckBox.setOnCheckedChangeListener { _, checked ->
            showWindDirection = checked
        }
        weather.onRefresh = { loadWeather() }
    }

    private fun loadWeather() {
        weather.loading.set(true)
        weather.status.set("Загружаем погоду…")
        weatherRequest?.cancel()
        weatherRequest = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetchWeather(selectedCity) }
                weather.cityName.set(result.name)
                weather.temperatureCelsius.set(result.temperature)
                weather.feelsLikeCelsius.set(result.feelsLike)
                weather.humidity.set(result.humidity)
                weather.windSpeed.set(result.wind)
                weather.windDirection.set(directionLabel(result.windDirectionDegrees))
                weather.description.set(
                    result.description.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                )
                renderWeather()
                weather.status.set("")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                weather.status.set("Не удалось загрузить погоду. Проверьте сеть и название города.")
            } finally {
                if (weatherRequest?.isActive != true) weather.loading.set(false)
            }
        }
    }

    private fun fetchWeather(city: String): WeatherResult {
        val encodedCity = URLEncoder.encode(city, "UTF-8")
        val geocodingUrl = URL(
            "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=ru&format=json"
        )
        val locationJson = requestJson(geocodingUrl)
        val locations = locationJson.optJSONArray("results")
            ?: error("Город не найден")
        if (locations.length() == 0) error("Город не найден")

        val location = locations.getJSONObject(0)
        val latitude = location.getDouble("latitude")
        val longitude = location.getDouble("longitude")
        val displayName = location.optString("name", city)
        val forecastUrl = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
                "&wind_speed_unit=ms&timezone=auto"
        )
        val current = requestJson(forecastUrl).getJSONObject("current")
        val code = current.getInt("weather_code")
        return WeatherResult(
            name = displayName,
            temperature = current.getDouble("temperature_2m"),
            feelsLike = current.getDouble("apparent_temperature"),
            humidity = current.getInt("relative_humidity_2m"),
            wind = current.getDouble("wind_speed_10m"),
            windDirectionDegrees = current.getDouble("wind_direction_10m"),
            description = weatherDescription(code)
        )
    }

    private fun requestJson(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("Weather API returned HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "Ясно"
        1 -> "Преимущественно ясно"
        2 -> "Переменная облачность"
        3 -> "Пасмурно"
        45, 48 -> "Туман"
        51, 53, 55 -> "Морось"
        56, 57 -> "Ледяная морось"
        61, 63, 65 -> "Дождь"
        66, 67 -> "Ледяной дождь"
        71, 73, 75, 77 -> "Снег"
        80, 81, 82 -> "Ливень"
        85, 86 -> "Снегопад"
        95 -> "Гроза"
        96, 99 -> "Гроза с градом"
        else -> "Погодные данные получены"
    }

    private fun renderWeather() {
        val temperatureC = weather.temperatureCelsius.get() ?: 0.0
        val feelsLikeC = weather.feelsLikeCelsius.get() ?: 0.0
        val unit = if (useFahrenheit) "°F" else "°C"
        val temperature = if (useFahrenheit) temperatureC * 9.0 / 5.0 + 32.0 else temperatureC
        val feelsLike = if (useFahrenheit) feelsLikeC * 9.0 / 5.0 + 32.0 else feelsLikeC
        val wind = weather.windSpeed.get() ?: 0.0
        val windText = String.format(Locale.getDefault(), "%.1f", wind)
        val windDirectionText = if (showWindDirection) {
            "\nНаправление ветра ${weather.windDirection.get().orEmpty()}"
        } else ""

        // The screen is rendered through the same observable model used by data binding.
        weather.temperature.set("${temperature.roundToInt()}$unit")
        weather.details.set(
            "Ощущается как ${feelsLike.roundToInt()}$unit\n" +
                "Влажность ${weather.humidity.get()}%  •  Ветер $windText м/с" + windDirectionText
        )
    }

    private fun directionLabel(degrees: Double): String {
        val directions = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        val index = ((degrees + 22.5) / 45.0).toInt() % directions.size
        return "${directions[index]} (${degrees.roundToInt()}°)"
    }

    private data class WeatherResult(
        val name: String,
        val temperature: Double,
        val feelsLike: Double,
        val humidity: Int,
        val wind: Double,
        val windDirectionDegrees: Double,
        val description: String
    )
}
