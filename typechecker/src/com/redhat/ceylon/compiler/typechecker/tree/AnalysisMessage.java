package com.redhat.ceylon.compiler.typechecker.tree;

import org.antlr.runtime.Token;

import com.redhat.ceylon.common.Backend;

/**
 * An error or warning relating to a node of the AST.
 */
public class AnalysisMessage implements Message {
    
    private final Node treeNode;
    private final String message;
    private final int code;
    private final Backend backend;
    
    @Override
    public boolean isWarning() {
        return false;
    }
    
    public AnalysisMessage(Node treeNode, String message) {
        this(treeNode, message, 0);
    }
    
    public AnalysisMessage(Node treeNode, String message, Backend backend) {
        this(treeNode, message, 0, backend);
    }
    
    public AnalysisMessage(Node treeNode, String message, int code) {
        this(treeNode, message, code, null);
    }

    public AnalysisMessage(Node treeNode, String message, ErrorCode errorCode) {
        this(treeNode, message, errorCode, null);
    }

    public AnalysisMessage(Node treeNode, String message, ErrorCode errorCode, Backend backend) {
        this(treeNode, message, errorCode == null ? ErrorCode.UNKNOWN.code : errorCode.code, null);
    }
    
    public AnalysisMessage(Node treeNode, String message, int code, Backend backend) {
        this.treeNode = treeNode;
        this.message = message;
        this.code = code;
        this.backend = backend;
    }
    
    @Override
    public String getMessage() {
        return message;
    }
    
    public Node getTreeNode() {
        return treeNode;
    }
    
    @Override
    public int getCode() {
        return code;
    }
    
    @Override
    public Backend getBackend() {
        return backend;
    }
    
    @Override
    public int getLine() {
        Token token = treeNode.getToken();
        return token==null ? -1 : token.getLine();
    }
    
    @Override
    public String toString() {
        return message;
    }

    @Override
    public ErrorCode getErrorCode() {
        return ErrorCode.getErrorCode(code);
    }
}
