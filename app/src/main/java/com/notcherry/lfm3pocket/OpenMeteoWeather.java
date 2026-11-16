package com.notcherry.lfm3pocket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Open-Meteo geocoding + current forecast. No API key. Callers must gate on the
 * user-visible network-tools toggle and treat the returned text as untrusted.
 */
public final class OpenMeteoWeather {
    public interface Transport {
        String get(String url) throws Exception;
    }

    public static final class Report {
        public final String place;
        public final double latitude;
        public final double longitude;
        public final double temperatureC;
        public final double windSpeedKmh;
        public final int weatherCode;
        public final String summary;

        Report(String place, double latitude, double longitude, double temperatureC,
               double windSpeedKmh, int weatherCode, String summary) {
            this.place = place;
            this.latitude = latitude;
            this.longitude = longitude;
            this.temperatureC = temperatureC;
            this.windSpeedKmh = windSpeedKmh;
            this.weatherCode = weatherCode;
            this.summary = summary;
        }
    }

    private OpenMeteoWeather() {}

    public static Report lookup(String location, Double latitude, Double longitude, Transport transport)
            throws Exception {
        if (transport == null) throw new IllegalArgumentException("Missing transport");
        double lat;
        double lon;
        String place;
        if (latitude != null && longitude != null) {
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                throw new IllegalArgumentException("Coordinates are out of range");
            }
            lat = latitude;
            lon = longitude;
            place = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
        } else if (location != null && !location.trim().isEmpty()) {
            String trimmed = location.trim();
            if (trimmed.length() > 100) throw new IllegalArgumentException("Location is too long");
            JSONObject geo = new JSONObject(transport.get(
                    "https://geocoding-api.open-meteo.com/v1/search?name="
                            + URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
                            + "&count=1&language=en&format=json"));
            JSONArray results = geo.optJSONArray("results");
            if (results == null || results.length() == 0) {
                throw new IllegalArgumentException("No matching place for that location");
            }
            JSONObject first = results.getJSONObject(0);
            lat = first.getDouble("latitude");
            lon = first.getDouble("longitude");
            String name = first.optString("name", trimmed);
            String country = first.optString("country", "");
            place = country.isEmpty() ? name : name + ", " + country;
        } else {
            throw new IllegalArgumentException("Provide location or latitude and longitude");
        }
        String forecastUrl = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f"
                        + "&current=temperature_2m,weather_code,wind_speed_10m&wind_speed_unit=kmh",
                lat, lon);
        JSONObject forecast = new JSONObject(transport.get(forecastUrl));
        JSONObject current = forecast.optJSONObject("current");
        if (current == null) throw new IllegalArgumentException("Forecast response missing current conditions");
        double temperature = current.getDouble("temperature_2m");
        double wind = current.optDouble("wind_speed_10m", Double.NaN);
        int code = current.optInt("weather_code", -1);
        String summary = String.format(Locale.ROOT,
                "UNTRUSTED_WEATHER place=%s temp_c=%.1f wind_kmh=%s code=%s (%s)",
                place,
                temperature,
                Double.isNaN(wind) ? "n/a" : String.format(Locale.ROOT, "%.1f", wind),
                code < 0 ? "n/a" : Integer.toString(code),
                describeCode(code));
        return new Report(place, lat, lon, temperature, wind, code, summary);
    }

    public static Report parseGeocodeAndForecast(String geocodeJson, String forecastJson) throws Exception {
        Transport canned = url -> {
            if (url.contains("geocoding-api.open-meteo.com")) return geocodeJson;
            if (url.contains("api.open-meteo.com")) return forecastJson;
            throw new IllegalArgumentException("Unexpected URL in test transport");
        };
        JSONObject geo = new JSONObject(geocodeJson);
        JSONArray results = geo.optJSONArray("results");
        if (results == null || results.length() == 0) {
            throw new IllegalArgumentException("No matching place for that location");
        }
        return lookup("fixture", null, null, canned);
    }

    /** Parses a forecast-only payload for coordinate lookups in tests. */
    public static Report parseForecast(String place, double lat, double lon, String forecastJson)
            throws Exception {
        Transport canned = url -> forecastJson;
        return lookup(null, lat, lon, canned);
    }

    public static Transport httpTransport() {
        return url -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            try {
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("Weather returned HTTP " + code);
                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    StringBuilder body = new StringBuilder();
                    char[] buffer = new char[4096];
                    int n;
                    while ((n = reader.read(buffer)) != -1) {
                        if (body.length() + n > 500_000) throw new Exception("Weather response is too large");
                        body.append(buffer, 0, n);
                    }
                    return body.toString();
                }
            } finally {
                connection.disconnect();
            }
        };
    }

    static String describeCode(int code) {
        if (code == 0) return "clear";
        if (code == 1 || code == 2 || code == 3) return "partly cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "rain showers";
        if (code >= 85 && code <= 86) return "snow showers";
        if (code >= 95) return "thunderstorm";
        if (code < 0) return "unknown";
        return "cloudy";
    }
}
