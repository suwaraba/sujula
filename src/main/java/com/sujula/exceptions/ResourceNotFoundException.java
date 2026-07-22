package com.sujula.exceptions;

public class ResourceNotFoundException extends RuntimeException {
	
	
    private static final long serialVersionUID = 180866571731677190L;
    
    
	public ResourceNotFoundException(String message) {
        super(message);
    }
    public ResourceNotFoundException(String resource, Long id) {
        super(resource + " not found with id: " + id);
    }
    public ResourceNotFoundException(String resource, String identifier) {
        super(resource + " not found: " + identifier);
    }
}
