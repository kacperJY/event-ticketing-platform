package pl.kacper.exception;

public class ResourcesNotFoundException extends RuntimeException{

    public ResourcesNotFoundException(String message) {
        super(message);
    }

    public ResourcesNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResourcesNotFoundException(Throwable cause) {
        super(cause);
    }
}
