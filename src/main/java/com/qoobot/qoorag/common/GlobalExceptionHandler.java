package com.qoobot.qoorag.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** 全局异常处理：将业务异常 / 校验失败 / 内部异常统一映射为 Result{code,message,data} */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：使用异常自带错误码 */
    @ExceptionHandler(BizException.class)
    public Result handleBiz(BizException ex) {
        return Result.fail(ex.getCode(), ex.getMessage());
    }

    /** 参数校验失败（@Validated / @Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.PARAM_INVALID, "参数校验失败: " + detail);
    }

    /** 缺少必填请求头（如 Authorization） */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result handleMissingHeader(MissingRequestHeaderException ex) {
        return Result.fail(ErrorCode.UNAUTHENTICATED, "缺少请求头: " + ex.getHeaderName());
    }

    /** 请求体无法解析（JSON 格式错误） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleNotReadable(HttpMessageNotReadableException ex) {
        return Result.fail(ErrorCode.PARAM_INVALID, "请求体格式错误");
    }

    /** 兜底：未预期异常统一为 50001 */
    @ExceptionHandler(Exception.class)
    public Result handleOther(Exception ex) {
        log.error("未处理异常: {}", ex.getMessage(), ex);
        return Result.fail(ErrorCode.INTERNAL, "服务器内部错误: " + ex.getMessage());
    }
}
