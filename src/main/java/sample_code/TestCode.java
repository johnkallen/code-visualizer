package sample_code;

import java.util.Arrays;
import java.util.List;

public class TestCode {

    public static void main(String[] args) {
        int result = summarizeScores(75);
        System.out.println(result);
    }

    public static int summarizeScores(int threshold) {
        List<Integer> scores = Arrays.asList(85, 42, 90, 61, 78);
        int passing = (int) scores.stream()
                .filter(s -> s >= threshold)    // Embedded filtering logic
                .map(s -> s * 2)                // Embedded mapping logic
                .mapToInt(s -> {                // Embedded block of code
                    int bonus = s > 80 ? 10 : 0;
                    return s + bonus;
                })
                .count();
        int failing = scores.size() - passing;
        return (passing > failing) ? passing : -failing;
    }
}
