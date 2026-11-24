package com.hiddencam;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CalculatorExpressionTest {
    @Test public void evaluatesArithmeticAndParentheses() {
        assertEquals("14", CalculatorExpression.evaluateForDisplay("2 + 3 * 4"));
        assertEquals("20", CalculatorExpression.evaluateForDisplay("(2 + 3) * 4"));
        assertEquals("123456", CalculatorExpression.evaluateForDisplay("(1234 * 100) + 56"));
    }

    @Test public void supportsDecimalsAndPercentages() {
        assertEquals("3.75", CalculatorExpression.evaluateForDisplay("1.5 * 2.5"));
        assertEquals("0.5", CalculatorExpression.evaluateForDisplay("50%"));
        assertEquals("20", CalculatorExpression.evaluateForDisplay("200 * 10%"));
    }

    @Test public void convertsIntegerExpressionsToSixDigitPinValues() {
        assertEquals("123456", new String(CalculatorExpression.toSixDigitPin("(1234 * 100) + 56")));
        assertEquals("000007", new String(CalculatorExpression.toSixDigitPin("21 / 3")));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsDivisionByZero() {
        CalculatorExpression.evaluateForDisplay("123 / 0");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsFractionalPinExpressions() {
        CalculatorExpression.toSixDigitPin("7 / 2");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsPinValuesOutsideRange() {
        CalculatorExpression.toSixDigitPin("999999 + 1");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsMalformedInput() {
        CalculatorExpression.evaluateForDisplay("__import__('os')");
    }
}
