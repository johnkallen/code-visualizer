package sample_code;
/*  SAMPLE CODE

--- Sample 1: simple if ---

int x = 3;
if (x > 0) {
    x--;
}

--- Sample 2: if-else with multiple statements ---

int x = 4;
if (x < 5) {
    x++;
    y = 50;
} else {
    x--;
}
System.out.println(x);


--- Sample 3: sequential if-else blocks ---

int x = 5;
int y = 10;
if (x > 0) {
x--;
} else {
x = 0;
}
if (y <= 10) {
y++;
} else {
y = 10;
}
System.out.println(x,y);


--- Sample 4: compound condition ---

int x = 5;
int y = 10;
if (x > 0 && y < 15) {
x++;
y++;
}
System.out.println(x,y);


--- Sample 5: nested if ---

int x = 5;
int y = 0;
if (x > 0) {
x--;
if (y == 0) {
    y = 10;
} else {
    y--;
}
} else {
 x = 0;
}


--- Sample 6: save order to database ---

public void updateOrder(int requestId) {
int orderId = getNextOrderId(requestId);
int customerId = getCustomerId(orderId);
boolean isValid = validateOrder(orderId);
if (isValid) {
    double total = calculateTotal(orderId);
    applyDiscount(orderId);
    saveOrder(orderId, customerId, total);
} else {
    logInvalidOrder(orderId);
}
sendConfirmation(customerId);
}


--- Sample 7: score summary with streaming and ternary ---

public int summarizeScores(int threshold) {
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
    int result = (passing > failing) ? passing : -failing;
    return result;
}

*/
