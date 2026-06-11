package sample_code;

public class SampleClass {

    private int baseScore = 50;
    private String grade = "F";

    // Returns a letter grade based on a numeric score
    public String getGrade(int score) {
        if (score >= 90) {
            grade = "A";
        } else if (score >= 80) {
            grade = "B";
        } else if (score >= 70) {
            grade = "C";
        } else if (score >= 60) {
            grade = "D";
        } else {
            grade = "F";
        }
        return grade;
    }

    // Calculates a bonus on top of a base salary
    public int calculateBonus(int salary) {
        int bonus = 0;
        if (salary > 100000) {
            bonus = salary / 10;
        } else if (salary > 50000) {
            bonus = salary / 20;
        } else {
            bonus = 500;
        }
        int total = salary + bonus;
        return total;
    }

    // Checks if a number is prime
    public boolean isPrime(int n) {
        if (n < 2) {
            return false;
        }
        int i = 2;
        if (i * i <= n) {
            if (n % i == 0) {
                return false;
            }
            i = i + 1;
        }
        return true;
    }

    // Converts Celsius to Fahrenheit with range classification
    public String classifyTemperature(int celsius) {
        int fahrenheit = celsius * 9 / 5 + 32;
        String category = "unknown";
        if (fahrenheit < 32) {
            category = "freezing";
        } else if (fahrenheit < 60) {
            category = "cold";
        } else if (fahrenheit < 80) {
            category = "comfortable";
        } else {
            category = "hot";
        }
        return category;
    }

    // Applies a tiered discount to a price
    private int applyDiscount(int price, int quantity) {
        int discount = 0;
        if (quantity >= 100) {
            discount = price / 4;
        } else if (quantity >= 50) {
            discount = price / 5;
        } else if (quantity >= 10) {
            discount = price / 10;
        }
        int finalPrice = price - discount;
        return finalPrice;
    }

    // Calculates total order cost using the private discount method
    public int calculateOrderTotal(int unitPrice, int quantity) {
        int discountedPrice = applyDiscount(unitPrice, quantity);
        int total = discountedPrice * quantity;
        return total;
    }

    // Returns a fitness category based on BMI
    public String getBmiCategory(int weightKg, int heightCm) {
        int heightM = heightCm / 100;
        int bmi = weightKg / (heightM * heightM);
        String category = "normal";
        if (bmi < 18) {
            category = "underweight";
        } else if (bmi < 25) {
            category = "normal";
        } else if (bmi < 30) {
            category = "overweight";
        } else {
            category = "obese";
        }
        return category;
    }
}
