package com.notcherry.lfm3pocket;

/**
 * Bounded arithmetic expression evaluator. Accepts digits, {@code + - * / ( )},
 * unary minus, and decimal points. No identifiers, function calls, or scripting.
 */
public final class SafeCalculator {
    private SafeCalculator() {}

    public static double evaluate(String expression) {
        if (expression == null) throw new IllegalArgumentException("Expression is required");
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Expression is empty");
        if (trimmed.length() > 200) throw new IllegalArgumentException("Expression is too long");
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!(Character.isDigit(c) || c == '+' || c == '-' || c == '*' || c == '/'
                    || c == '(' || c == ')' || c == '.' || Character.isWhitespace(c))) {
                throw new IllegalArgumentException("Unsupported character in expression");
            }
        }
        Parser parser = new Parser(trimmed);
        double value = parser.parseExpression();
        parser.expectEnd();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Expression result is not a finite number");
        }
        return value;
    }

    public static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) { this.text = text; }

        double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipSpaces();
                if (match('+')) value += parseTerm();
                else if (match('-')) value -= parseTerm();
                else return value;
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipSpaces();
                if (match('*')) value *= parseFactor();
                else if (match('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0.0) throw new IllegalArgumentException("Division by zero");
                    value /= divisor;
                } else return value;
            }
        }

        private double parseFactor() {
            skipSpaces();
            if (match('+')) return parseFactor();
            if (match('-')) return -parseFactor();
            if (match('(')) {
                double value = parseExpression();
                skipSpaces();
                if (!match(')')) throw new IllegalArgumentException("Missing closing parenthesis");
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipSpaces();
            int start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
            }
            if (start == index) throw new IllegalArgumentException("Expected a number");
            try {
                return Double.parseDouble(text.substring(start, index));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number");
            }
        }

        private void expectEnd() {
            skipSpaces();
            if (index != text.length()) throw new IllegalArgumentException("Unexpected trailing input");
        }

        private void skipSpaces() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean match(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }
    }
}
