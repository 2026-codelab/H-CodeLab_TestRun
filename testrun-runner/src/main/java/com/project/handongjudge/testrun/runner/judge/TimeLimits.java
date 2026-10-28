package com.project.handongjudge.testrun.runner.judge;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOMjudge와 같은 시간 제한 계산.
 * soft = 문제 시간 제한 × 언어 time_factor, hard = soft + overshoot(soft, timelimit_overshoot)
 * (DOMjudge judgedaemon.main.php overshoot_time, :1413-1478)
 */
public final class TimeLimits {

    private static final Pattern TOKEN = Pattern.compile("([+&|]?)\\s*([0-9.]+)\\s*([s%])");

    private TimeLimits() {
    }

    /**
     * overshoot 설정 해석. "Xs"는 X초, "Y%"는 soft의 Y%. 연산자: '+' 합, '&' 최소, '|' 최대.
     * 예: "1s|10%" → max(1초, 10%).
     */
    public static double overshoot(double soft, String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        Matcher m = TOKEN.matcher(spec);
        Double result = null;
        while (m.find()) {
            double value = Double.parseDouble(m.group(2));
            double seconds = "%".equals(m.group(3)) ? soft * value / 100.0 : value;
            String op = m.group(1);
            if (result == null) {
                result = seconds;
            } else if ("+".equals(op)) {
                result += seconds;
            } else if ("&".equals(op)) {
                result = Math.min(result, seconds);
            } else {
                result = Math.max(result, seconds);
            }
        }
        if (result == null) {
            throw new IllegalArgumentException("invalid timelimit_overshoot: " + spec);
        }
        return result;
    }

    public static double hard(double soft, String overshootSpec) {
        return soft + overshoot(soft, overshootSpec);
    }

    /** 테스트케이스 1개를 끝내는 데 필요한 최악의 시간(ms): wall hard 한도 + 준비·비교 여유. */
    public static long worstCaseMs(double hard, long marginMs) {
        return (long) Math.ceil(hard * 1000) + marginMs;
    }

    /** testcase_run.sh 인자 형식 "cpu_soft:cpu_hard,wall_soft:wall_hard". */
    public static String argument(double soft, double hard) {
        String pair = format(soft) + ":" + format(hard);
        return pair + "," + pair;
    }

    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
