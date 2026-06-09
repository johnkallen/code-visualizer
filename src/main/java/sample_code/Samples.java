package sample_code;
/*  SAMPLE CODE

--- Sample 1: simple if ---

int x = 5;
if (x > 0) {
    x++;
}


--- Sample 2: if-else with multiple statements ---

int x = 5;
if (x > 0) {
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
if (y < 10) {
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

public void updateOrder() {
int orderId = getNextOrderId();
int customerId = getCustomerId();
boolean isValid = validateOrder(orderId);
if (isValid) {
    int total = calculateTotal(orderId);
    applyDiscount(orderId);
    saveOrder(orderId, customerId, total);
} else {
    logInvalidOrder(orderId);
}
sendConfirmation(customerId);
}

*/
