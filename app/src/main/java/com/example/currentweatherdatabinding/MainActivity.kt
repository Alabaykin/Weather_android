package com.example.currentweatherdatabinding

import android.os.Bundle
import android.widget.ArrayAdapter
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
    val city = ObservableField("Иркутск")
    val cityName = ObservableField("Погода")
    val temperature = ObservableField("—°")
    val description = ObservableField("Выберите город")
    val details = ObservableField("")
    val status = ObservableField("")
    val loading = ObservableBoolean(false)

    var onRefresh: (() -> Unit)? = null
    fun refresh() = onRefresh?.invoke()
}

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val weather = WeatherViewModel()
    private val cities = listOf(
        "Иркутск", "Москва", "Санкт-Петербург", "Новосибирск",
        "Казань", "Владивосток", "Екатеринбург", "Якутск"
    )
    private var weatherRequest: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        binding.lifecycleOwner = this
        binding.weather = weather

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(24, bars.top + 16, 24, bars.bottom + 16)
            insets
        }

        binding.cityInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, cities)
        )
        binding.cityInput.setOnItemClickListener { _, _, _, _ -> loadWeather() }
        weather.onRefresh = { loadWeather() }
        binding.cityInput.setText(weather.city.get(), false)
        loadWeather()
    }

    private fun loadWeather() {
        val selectedCity = weather.city.get()?.trim().orEmpty()
        if (selectedCity.isBlank()) {
            weather.status.set("Выберите город")
            return
        }
        weather.loading.set(true)
        weather.status.set("Загружаем погоду…")
        weatherRequest?.cancel()
        weatherRequest = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetchWeather(selectedCity) }
                weather.cityName.set(result.name)
                weather.temperature.set("${result.temperature.roundToInt()}°")
                weather.description.set(
                    result.description.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                )
                weather.details.set(
                    "Ощущается как ${result.feelsLike.roundToInt()}°\n" +
                        "Влажность ${result.humidity}%  •  Ветер ${String.format(Locale.getDefault(), "%.1f", result.wind)} м/с"
                )
                weather.status.set("")
            } catch (_: Exception) {
                weather.status.set("Не удалось загрузить погоду. Проверьте сеть и название города.")
            } finally {
                weather.loading.set(false)
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
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
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

    private data class WeatherResult(
        val name: String,
        val temperature: Double,
        val feelsLike: Double,
        val humidity: Int,
        val wind: Double,
        val description: String
    )
}
