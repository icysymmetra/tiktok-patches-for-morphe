/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.bytedance.applog.priority;

/**
 * Stub of the AppLog SDK's priority upload reply, for compiling against. TikTok builds one from a
 * status code, an error message and the reply's data, and its callers read the data as JSON.
 */
@SuppressWarnings("unused")
public class PriorityHttpResponse {
    public final int code;
    public final String errMsg;
    public final String data;

    public PriorityHttpResponse(int code, String errMsg, String data) {
        this.code = code;
        this.errMsg = errMsg;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public String getErrMsg() {
        return errMsg;
    }

    public String getData() {
        return data;
    }
}
