package me.chenfeng.attributepotion.utils;

import net.objecthunter.exp4j.ExpressionBuilder;

public final class ExpressionUtil {
    private ExpressionUtil() {
    }

    public static double eval(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("expression cannot be null");
        }

        String expr = expression.trim();
        if (expr.isEmpty()) {
            throw new IllegalArgumentException("expression cannot be empty");
        }

        try {
            return new ExpressionBuilder(expr).build().evaluate();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to evaluate expression: " + expression, ex);
        }
    }

    public static boolean check(String expression) {
        if (expression == null) {
            return false;
        }

        String expr = expression.trim();
        if (expr.isEmpty()) {
            return false;
        }

        int orIndex = findLogical(expr, "||");
        if (orIndex >= 0) {
            return check(expr.substring(0, orIndex)) || check(expr.substring(orIndex + 2));
        }

        int andIndex = findLogical(expr, "&&");
        if (andIndex >= 0) {
            return check(expr.substring(0, andIndex)) && check(expr.substring(andIndex + 2));
        }

        Comparison comparison = findComparison(expr);
        if (comparison != null) {
            return compare(
                    expr.substring(0, comparison.index).trim(),
                    expr.substring(comparison.index + comparison.operator.length()).trim(),
                    comparison.operator
            );
        }

        if ("true".equalsIgnoreCase(expr) || "yes".equalsIgnoreCase(expr)) {
            return true;
        }
        if ("false".equalsIgnoreCase(expr) || "no".equalsIgnoreCase(expr)) {
            return false;
        }

        return eval(expr) != 0;
    }

    private static boolean compare(String left, String right, String operator) {
        if (isStringCompare(left, right)) {
            String leftValue = unquote(left);
            String rightValue = unquote(right);
            int result = leftValue.compareTo(rightValue);
            switch (operator) {
                case "==":
                    return result == 0;
                case "!=":
                    return result != 0;
                default:
                    throw new IllegalArgumentException("String comparison only supports == and !=");
            }
        }

        double leftValue = eval(left);
        double rightValue = eval(right);
        switch (operator) {
            case ">=":
                return leftValue >= rightValue;
            case "<=":
                return leftValue <= rightValue;
            case "==":
                return Math.abs(leftValue - rightValue) < 0.0000001;
            case "!=":
                return Math.abs(leftValue - rightValue) >= 0.0000001;
            case ">":
                return leftValue > rightValue;
            case "<":
                return leftValue < rightValue;
            default:
                throw new IllegalArgumentException("Unknown comparison operator: " + operator);
        }
    }

    private static boolean isStringCompare(String left, String right) {
        return isQuoted(left) || isQuoted(right) || !isNumericExpression(left) || !isNumericExpression(right);
    }

    private static boolean isNumericExpression(String value) {
        try {
            eval(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isQuoted(String value) {
        String s = value.trim();
        return s.length() >= 2
                && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''));
    }

    private static String unquote(String value) {
        String s = value.trim();
        if (isQuoted(s)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Comparison findComparison(String expr) {
        String[] operators = {">=", "<=", "==", "!=", ">", "<"};
        for (String operator : operators) {
            int index = findOutsideQuotes(expr, operator);
            if (index >= 0) {
                return new Comparison(index, operator);
            }
        }
        return null;
    }

    private static int findLogical(String expr, String operator) {
        return findOutsideQuotes(expr, operator);
    }

    private static int findOutsideQuotes(String expr, String token) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i <= expr.length() - token.length(); i++) {
            char c = expr.charAt(i);
            if (quote != 0) {
                if (c == quote && (i == 0 || expr.charAt(i - 1) != '\\')) {
                    quote = 0;
                }
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')' && depth > 0) {
                depth--;
                continue;
            }
            if (depth == 0 && expr.startsWith(token, i)) {
                return i;
            }
        }
        return -1;
    }

    private static final class Comparison {
        private final int index;
        private final String operator;

        private Comparison(int index, String operator) {
            this.index = index;
            this.operator = operator;
        }
    }
}
