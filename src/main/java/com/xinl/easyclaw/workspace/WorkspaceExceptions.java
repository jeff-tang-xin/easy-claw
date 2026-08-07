package com.xinl.easyclaw.workspace;

public class WorkspaceExceptions {
    
    public static class WorkspacePathExistsException extends RuntimeException {
        public WorkspacePathExistsException(String message) {
            super(message);
        }
    }
    
    public static class WorkspacePathNotFoundException extends RuntimeException {
        public WorkspacePathNotFoundException(String message) {
            super(message);
        }
    }
    
    public static class WorkspacePathNotDirectoryException extends RuntimeException {
        public WorkspacePathNotDirectoryException(String message) {
            super(message);
        }
    }
    
    public static class WorkspacePathNotWritableException extends RuntimeException {
        public WorkspacePathNotWritableException(String message) {
            super(message);
        }
    }
    
    public static class WorkspaceNotFoundException extends RuntimeException {
        public WorkspaceNotFoundException(String message) {
            super(message);
        }
    }
}
