package com.sujula.exceptions;

public class BadRequestException extends RuntimeException {
	
	
    private static final long serialVersionUID = -5414312391113085040L;

	public BadRequestException(String message) {
        super(message);
    }
}
