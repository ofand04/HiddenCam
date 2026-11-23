package com.hiddencam;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalculatorExpressionTest {
    @Test public void computesArithmeticAndPadsToSixDigits() {
        assertEquals("123456", new String(CalculatorExpression.toSixDigitPin("(1234 * 100) + 56")));
        assertEquals("000007", new String(CalculatorExpression.toSixDigitPin("21 / 3")));
    }
    @Test public void honorsOperatorPrecedenceAndParentheses() {
        assertEquals("000014", new String(CalculatorExpression.toSixDigitPin("2 + 3 * 4")));
        assertEquals("000020", new String(CalculatorExpression.toSixDigitPin("(2 + 3) * 4")));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsDivisionByZero() {
        CalculatorExpression.toSixDigitPin("123 / 0");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsFractions() {
        CalculatorExpression.toSixDigitPin("7 / 2");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsExpressionsOutsideSixDigitRange() {
        CalculatorExpression.toSixDigitPin("999999 + 1");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnsupportedInput() {
        CalculatorExpression.toSixDigitPin("__import__('os')");
    }
}
