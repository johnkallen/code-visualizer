package sample_code;

public class SampleController {

    private SampleService service = new SampleService();

    // GET /users/{id} — fetch a user by ID
    public String getUser(int id) {
        if (id <= 0) {
            return "400 Bad Request: invalid id";
        }
        int status = service.findUser(id);
        if (status == 404) {
            return "404 Not Found";
        }
        if (status == 503) {
            return "503 Service Unavailable";
        }
        return "200 OK";
    }

    // POST /users — register a new user
    public String createUser(String name, int age) {
        if (name == null) {
            return "400 Bad Request: name required";
        }
        if (age < 18) {
            return "400 Bad Request: must be 18 or older";
        }
        int newId = service.registerUser(name, age);
        if (newId == -1) {
            return "409 Conflict: registration failed";
        }
        return "201 Created";
    }

    // DELETE /users/{id} — remove a user (admin only)
    public String deleteUser(int id, boolean isAdmin) {
        if (!isAdmin) {
            return "403 Forbidden";
        }
        if (id <= 0) {
            return "400 Bad Request: invalid id";
        }
        boolean removed = service.removeUser(id, isAdmin);
        if (!removed) {
            return "404 Not Found";
        }
        return "200 OK";
    }

    // PUT /users/{id}/score — update a user's score
    public String updateScore(int id, int score) {
        if (id <= 0) {
            return "400 Bad Request: invalid id";
        }
        if (score < 0 || score > 100) {
            return "400 Bad Request: score must be 0-100";
        }
        int result = service.updateScore(id, score);
        if (result == -1) {
            return "404 Not Found";
        }
        return "200 OK";
    }

    // GET /health — service health check
    public String healthCheck() {
        int code = service.healthCheck();
        if (code == 503) {
            return "503 Service Unavailable";
        }
        if (code == 500) {
            return "500 Internal Server Error";
        }
        return "200 OK";
    }
}
