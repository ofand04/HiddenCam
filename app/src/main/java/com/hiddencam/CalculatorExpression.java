package com.hiddencam;

/** A deliberately small, side-effect-free integer expression parser for the alternate PIN entry. */
final class CalculatorExpression {
    private CalculatorExpression() { }

    static char[] toSixDigitPin(String expression) {
        if (expression == null || expression.trim().isEmpty() || expression.length() > 120) {
            throw new IllegalArgumentException("Enter a short calculator expression.");
        }
        Parser parser = new Parser(expression);
        long value = parser.parseExpression();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("The expression contains an unsupported character.");
        }
        if (value < 0 || value > 999999) {
            throw new IllegalArgumentException("The result must be between 0 and 999999.");
        }
        return String.format(java.util.Locale.ROOT, "%06d", value).toCharArray();
    }

    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) { this.input = input; }
        boolean atEnd() { return position >= input.length(); }
        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(position))) position++;
        }

        long parseExpression() {
            long value = parseTerm();
            while (true) {
                skipWhitespace();
                if (take('+')) value = Math.addExact(value, parseTerm());
                else if (take('-')) value = Math.subtractExact(value, parseTerm());
                else return value;
            }
        }

        long parseTerm() {
            long value = parseFactor();
            while (true) {
                skipWhitespace();
                if (take('*')) value = Math.multiplyExact(value, parseFactor());
                else if (take('/')) {
                    long divisor = parseFactor();
                    if (divisor == 0) throw new IllegalArgumentException("Division by zero is not allowed.");
                    if (value % divisor != 0) throw new IllegalArgumentException("Use an expression with a whole-number result.");
                    value /= divisor;
                } else return value;
            }
        }

        long parseFactor() {
            skipWhitespace();
            if (take('+')) return parseFactor();
            if (take('-')) return Math.negateExact(parseFactor());
            if (take('(')) {
                long value = parseExpression();
                skipWhitespace();
                if (!take(')')) throw new IllegalArgumentException("A closing parenthesis is missing.");
                return value;
            }
            int start = position;
            while (!atEnd() && Character.isDigit(input.charAt(position))) position++;
            if (start == position) throw new IllegalArgumentException("Expected a number.");
            try {
                return Long.parseLong(input.substring(start, position));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("The number is too large.");
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
