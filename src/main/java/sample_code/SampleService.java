package sample_code;

public class SampleService {

    private int userCount = 0;
    private boolean dbConnected = true;
    private int maxUsers = 100;

    // Looks up a user by ID and returns a status code
    public int findUser(int userId) {
        int status = 0;
        if (!dbConnected) {
            status = 503;
            return status;
        }
        if (userId <= 0) {
            status = 400;
            return status;
        }
        if (userId > userCount) {
            status = 404;
            return status;
        }
        status = 200;
        return status;
    }

    // Validates and registers a new user, returns the new user ID or -1 on failure
    public int registerUser(String name, int age) {
        if (name == null) {
            return -1;
        }
        if (age < 18) {
            return -1;
        }
        if (userCount >= maxUsers) {
            return -1;
        }
        userCount = userCount + 1;
        int newId = userCount;
        return newId;
    }

    // Removes a user by ID, returns true if successful
    public boolean removeUser(int userId, boolean isAdmin) {
        if (!isAdmin) {
            return false;
        }
        if (userId <= 0 || userId > userCount) {
            return false;
        }
        userCount = userCount - 1;
        return true;
    }

    // Updates a user's score, returns the adjusted score or -1 on failure
    public int updateScore(int userId, int score) {
        int status = findUser(userId);
        if (status != 200) {
            return -1;
        }
        if (score < 0) {
            score = 0;
        }
        if (score > 100) {
            score = 100;
        }
        int adjustedScore = score + 5;
        return adjustedScore;
    }

    // Checks if the service is healthy, returns a status message code
    public int healthCheck() {
        int code = 0;
        if (!dbConnected) {
            code = 503;
            return code;
        }
        if (userCount < 0) {
            code = 500;
            return code;
        }
        code = 200;
        return code;
    }
}
