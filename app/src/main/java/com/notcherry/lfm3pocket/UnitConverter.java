package com.notcherry.lfm3pocket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Converts common length, mass, temperature, volume, and speed units. */
public final class UnitConverter {
    public enum Kind { LENGTH, MASS, TEMPERATURE, VOLUME, SPEED }

    private static final Map<String, Factor> FACTORS;
    static {
        Map<String, Factor> map = new LinkedHashMap<>();
        // Length → metres
        put(map, Kind.LENGTH, 1.0, "m", "meter", "meters", "metre", "metres");
        put(map, Kind.LENGTH, 0.01, "cm", "centimeter", "centimeters", "centimetre", "centimetres");
        put(map, Kind.LENGTH, 0.001, "mm", "millimeter", "millimeters", "millimetre", "millimetres");
        put(map, Kind.LENGTH, 1000.0, "km", "kilometer", "kilometers", "kilometre", "kilometres");
        put(map, Kind.LENGTH, 0.0254, "in", "inch", "inches");
        put(map, Kind.LENGTH, 0.3048, "ft", "foot", "feet");
        put(map, Kind.LENGTH, 0.9144, "yd", "yard", "yards");
        put(map, Kind.LENGTH, 1609.344, "mi", "mile", "miles");
        // Mass → kilograms
        put(map, Kind.MASS, 1.0, "kg", "kilogram", "kilograms");
        put(map, Kind.MASS, 0.001, "g", "gram", "grams");
        put(map, Kind.MASS, 0.000001, "mg", "milligram", "milligrams");
        put(map, Kind.MASS, 1000.0, "t", "tonne", "tonnes", "metric_ton", "metric_tons");
        put(map, Kind.MASS, 0.45359237, "lb", "lbs", "pound", "pounds");
        put(map, Kind.MASS, 0.028349523125, "oz", "ounce", "ounces");
        // Volume → litres
        put(map, Kind.VOLUME, 1.0, "l", "liter", "liters", "litre", "litres");
        put(map, Kind.VOLUME, 0.001, "ml", "milliliter", "milliliters", "millilitre", "millilitres");
        put(map, Kind.VOLUME, 3.785411784, "gal", "gallon", "gallons", "us_gallon", "us_gallons");
        put(map, Kind.VOLUME, 0.473176473, "cup", "cups", "us_cup", "us_cups");
        put(map, Kind.VOLUME, 0.01478676478125, "tbsp", "tablespoon", "tablespoons");
        put(map, Kind.VOLUME, 0.00492892159375, "tsp", "teaspoon", "teaspoons");
        // Speed → metres per second
        put(map, Kind.SPEED, 1.0, "m_s", "m/s", "mps", "meter_per_second", "meters_per_second");
        put(map, Kind.SPEED, 1000.0 / 3600.0, "km_h", "km/h", "kph", "kilometer_per_hour", "kilometers_per_hour");
        put(map, Kind.SPEED, 1609.344 / 3600.0, "mph", "mi/h", "mile_per_hour", "miles_per_hour");
        put(map, Kind.SPEED, 0.514444, "kn", "kt", "knot", "knots");
        // Temperature (special-cased below)
        put(map, Kind.TEMPERATURE, 1.0, "c", "celsius", "centigrade", "deg_c", "°c");
        put(map, Kind.TEMPERATURE, 1.0, "f", "fahrenheit", "deg_f", "°f");
        put(map, Kind.TEMPERATURE, 1.0, "k", "kelvin", "deg_k");
        FACTORS = Collections.unmodifiableMap(map);
    }

    private UnitConverter() {}

    public static double convert(double value, String fromUnit, String toUnit) {
        Factor from = lookup(fromUnit);
        Factor to = lookup(toUnit);
        if (from.kind != to.kind) {
            throw new IllegalArgumentException("Cannot convert " + from.kind + " to " + to.kind);
        }
        if (from.kind == Kind.TEMPERATURE) {
            return fromTemperature(toTemperatureCelsius(value, from.canonical), to.canonical);
        }
        return value * from.toBase / to.toBase;
    }

    public static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Conversion result is not finite");
        }
        if (Math.abs(value) >= 1e-4 && value == Math.rint(value) && Math.abs(value) < 1e12) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.6g", value);
    }

    private static Factor lookup(String unit) {
        if (unit == null || unit.trim().isEmpty()) throw new IllegalArgumentException("Unit is required");
        Factor factor = FACTORS.get(normalize(unit));
        if (factor == null) throw new IllegalArgumentException("Unsupported unit: " + unit.trim());
        return factor;
    }

    private static String normalize(String unit) {
        StringBuilder out = new StringBuilder();
        String value = unit.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '°') continue;
            if (c == '-' || c == ' ' || c == '/') out.append('_');
            else out.append(c);
        }
        String normalized = out.toString();
        while (normalized.contains("__")) normalized = normalized.replace("__", "_");
        if (normalized.startsWith("_")) normalized = normalized.substring(1);
        if (normalized.endsWith("_")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static double toTemperatureCelsius(double value, String unit) {
        switch (unit) {
            case "c": return value;
            case "f": return (value - 32.0) * 5.0 / 9.0;
            case "k":
                if (value < 0) throw new IllegalArgumentException("Kelvin cannot be negative");
                return value - 273.15;
            default: throw new IllegalArgumentException("Unsupported temperature unit");
        }
    }

    private static double fromTemperature(double celsius, String unit) {
        switch (unit) {
            case "c": return celsius;
            case "f": return celsius * 9.0 / 5.0 + 32.0;
            case "k": return celsius + 273.15;
            default: throw new IllegalArgumentException("Unsupported temperature unit");
        }
    }

    private static void put(Map<String, Factor> map, Kind kind, double toBase, String... aliases) {
        Factor factor = new Factor(kind, toBase, aliases[0]);
        for (String alias : aliases) map.put(normalize(alias), factor);
    }

    private static final class Factor {
        final Kind kind;
        final double toBase;
        final String canonical;
        Factor(Kind kind, double toBase, String canonical) {
            this.kind = kind;
            this.toBase = toBase;
            this.canonical = canonical;
        }
    }
}
