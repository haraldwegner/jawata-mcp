package org.jawata.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 2.0 message representation.
 * Used for both requests and responses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcMessage {

    private String jsonrpc = "2.0";
    private Object id;  // Can be String, Integer, or null
    private String method;
    private JsonNode params;
    /**
     * The answer. JSON-RPC requires a success response to carry this member, and for a request with nothing to answer it is {@code null}
     * on the wire; in memory that is a {@link com.fasterxml.jackson.databind.node.NullNode}, because a Java null means "no result
     * member" here (and is not written). A result that is not null is held and written exactly as before.
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ResultDeserializer.class)
    private Object result;
    private JsonRpcError error;

    public JsonRpcMessage() {
    }

    // Request constructor
    public static JsonRpcMessage request(Object id, String method, JsonNode params) {
        JsonRpcMessage msg = new JsonRpcMessage();
        msg.id = id;
        msg.method = method;
        msg.params = params;
        return msg;
    }

    // Success response constructor
    public static JsonRpcMessage successResponse(Object id, Object result) {
        JsonRpcMessage msg = new JsonRpcMessage();
        msg.id = id;
        msg.result = result == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : result;
        return msg;
    }

    // Error response constructor
    public static JsonRpcMessage errorResponse(Object id, int code, String message, Object data) {
        JsonRpcMessage msg = new JsonRpcMessage();
        msg.id = id;
        msg.error = new JsonRpcError(code, message, data);
        return msg;
    }

    // Notification (no id)
    public static JsonRpcMessage notification(String method, JsonNode params) {
        JsonRpcMessage msg = new JsonRpcMessage();
        msg.method = method;
        msg.params = params;
        return msg;
    }

    @JsonIgnore
    public boolean isRequest() {
        return method != null && id != null;
    }

    @JsonIgnore
    public boolean isNotification() {
        return method != null && id == null;
    }

    @JsonIgnore
    public boolean isResponse() {
        return method == null && (result != null || error != null);
    }

    // Getters and setters
    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public JsonNode getParams() {
        return params;
    }

    public void setParams(JsonNode params) {
        this.params = params;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public void setError(JsonRpcError error) {
        this.error = error;
    }

    /**
     * JSON-RPC 2.0 error object.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;

        public JsonRpcError() {
        }

        public JsonRpcError(int code, String message, Object data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        // Standard JSON-RPC error codes
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }

    /**
     * Reads a {@code result} member as it always was read (maps, lists, scalars), except that a JSON {@code null} becomes a
     * {@link com.fasterxml.jackson.databind.node.NullNode} and not a Java null, so a message that carries {@code "result": null} is
     * told apart from one that carries no result.
     */
    static final class ResultDeserializer extends com.fasterxml.jackson.databind.deser.std.StdDeserializer<Object> {

        private static final long serialVersionUID = 1L;

        ResultDeserializer() {
            super(Object.class);
        }

        @Override
        public Object deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context)
                throws java.io.IOException {
            return context.readValue(parser, Object.class);
        }

        @Override
        public Object getNullValue(com.fasterxml.jackson.databind.DeserializationContext context) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
    }
}
