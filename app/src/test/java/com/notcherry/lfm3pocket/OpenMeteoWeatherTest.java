package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OpenMeteoWeatherTest {
    @Test public void parsesGeocodeAndForecastPayloadsAsUntrustedSummaries() throws Exception {
        String geocode = "{\"results\":[{\"name\":\"Warsaw\",\"country\":\"Poland\",\"latitude\":52.23,\"longitude\":21.01}]}";
        String forecast = "{\"current\":{\"temperature_2m\":12.5,\"weather_code\":3,\"wind_speed_10m\":8.0}}";
        OpenMeteoWeather.Report report = OpenMeteoWeather.parseGeocodeAndForecast(geocode, forecast);
        assertEquals("Warsaw, Poland", report.place);
        assertEquals(12.5, report.temperatureC, 0.0);
        assertTrue(report.summary.startsWith("UNTRUSTED_WEATHER"));
        assertTrue(report.summary.contains("temp_c=12.5"));
    }

    @Test public void parsesCoordinateForecastsAndRejectsEmptyGeocode() throws Exception {
        String forecast = "{\"current\":{\"temperature_2m\":-2.0,\"weather_code\":71,\"wind_speed_10m\":15.5}}";
        OpenMeteoWeather.Report report = OpenMeteoWeather.parseForecast("custom", 60.0, 10.0, forecast);
        assertEquals(-2.0, report.temperatureC, 0.0);
        assertTrue(report.summary.contains("snow"));

        try {
            OpenMeteoWeather.parseGeocodeAndForecast("{\"results\":[]}", forecast);
            fail();
        } catch (IllegalArgumentException expected) { }
    }
}
