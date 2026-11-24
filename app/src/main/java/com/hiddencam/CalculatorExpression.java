package com.hiddencam;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

/** Side-effect-free decimal calculator parser used by the public calculator and PIN flow. */
final class CalculatorExpression {
    private static final int MAX_INPUT_LENGTH = 120;
    private static final int MAX_RESULT_LENGTH = 24;
    private static final MathContext DIVISION_CONTEXT = new MathContext(18, RoundingMode.HALF_UP);

    private CalculatorExpression() { }

    /** Evaluates a normal calculator expression and returns a locale-independent display value. */
    static String evaluateForDisplay(String expression) {
        BigDecimal value = evaluate(expression).stripTrailingZeros();
        if (value.signum() == 0) return "0";
        String result = value.toPlainString();
        if (result.length() > MAX_RESULT_LENGTH) {
            throw new IllegalArgumentException("The result is too large to display.");
        }
        return result;
    }

    /**
     * Converts an expression to the six-digit numeric value used for expression-based PIN entry.
     * Plain 4–12 digit PINs are handled separately by MainActivity so their exact length is kept.
     */
    static char[] toSixDigitPin(String expression) {
        BigDecimal value = evaluate(expression).stripTrailingZeros();
        final long whole;
        try {
            whole = value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Use an expression with a whole-number result.");
        }
        if (whole < 0 || whole > 999999) {
            throw new IllegalArgumentException("The result must be between 0 and 999999.");
        }
        return String.format(Locale.ROOT, "%06d", whole).toCharArray();
    }

    private static BigDecimal evaluate(String expression) {
        if (expression == null || expression.trim().isEmpty() || expression.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("Enter a short calculator expression.");
        }
        Parser parser = new Parser(expression);
        BigDecimal value = parser.parseExpression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("The expression contains an unsupported character.");
        }
        return value;
    }

    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) { this.input = input; }
        boolean atEnd() { return position >= input.length(); }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(position))) position++;
        }

        BigDecimal parseExpression() {
            BigDecimal value = parseTerm();
            while (true) {
                skipWhitespace();
                if (take('+')) value = value.add(parseTerm(), DIVISION_CONTEXT);
                else if (take('-')) value = value.subtract(parseTerm(), DIVISION_CONTEXT);
                else return value;
            }
        }

        BigDecimal parseTerm() {
            BigDecimal value = parsePostfix();
            while (true) {
                skipWhitespace();
                if (take('*')) {
                    value = value.multiply(parsePostfix(), DIVISION_CONTEXT);
                } else if (take('/')) {
                    BigDecimal divisor = parsePostfix();
                    if (divisor.signum() == 0) throw new IllegalArgumentException("Division by zero is not allowed.");
                    value = value.divide(divisor, DIVISION_CONTEXT);
                } else {
                    return value;
                }
            }
        }

        BigDecimal parsePostfix() {
            BigDecimal value = parseUnary();
            skipWhitespace();
            while (take('%')) {
                value = value.movePointLeft(2);
                skipWhitespace();
            }
            return value;
        }

        BigDecimal parseUnary() {
            skipWhitespace();
            if (take('+')) return parseUnary();
            if (take('-')) return parseUnary().negate();
            if (take('(')) {
                BigDecimal value = parseExpression();
                skipWhitespace();
                if (!take(')')) throw new IllegalArgumentException("A closing parenthesis is missing.");
                return value;
            }
            return parseNumber();
        }

        BigDecimal parseNumber() {
            skipWhitespace();
            int start = position;
            boolean decimalPoint = false;
            boolean hasDigit = false;
            while (!atEnd()) {
                char current = input.charAt(position);
                if (Character.isDigit(current)) {
                    hasDigit = true;
                    position++;
                } else if (current == '.' && !decimalPoint) {
                    decimalPoint = true;
                    position++;
                } else {
                    break;
                }
            }
            if (!hasDigit) throw new IllegalArgumentException("Expected a number.");
            try {
                return new BigDecimal(input.substring(start, position), DIVISION_CONTEXT);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("The number is invalid.");
            }
        }

        boolean take(char expected) {
            if (!atEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }
    }
}
