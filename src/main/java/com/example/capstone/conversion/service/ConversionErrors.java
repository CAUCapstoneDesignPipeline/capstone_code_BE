package com.example.capstone.conversion.service;
import com.example.capstone.global.exception.ApiException;
import com.example.capstone.global.exception.ErrorCode;
public final class ConversionErrors {
    private ConversionErrors() { }
    public static ApiException unavailable() { return new ApiException(ErrorCode.CONVERSION_UNAVAILABLE,"문서 변환은 현재 사용할 수 없습니다."); }
    public static ApiException unsupported() { return new ApiException(ErrorCode.UNSUPPORTED_FILE_TYPE,"지원하지 않거나 올바르지 않은 파일입니다."); }
    public static ApiException limit() { return new ApiException(ErrorCode.UPLOAD_LIMIT_EXCEEDED,"파일 처리 한도를 초과했습니다."); }
    public static ApiException missing() { return new ApiException(ErrorCode.NOT_FOUND,"원본 또는 변환 작업을 찾을 수 없습니다."); }
    public static ApiException conflict() { return new ApiException(ErrorCode.CONVERSION_CONFLICT,"변환 상태가 변경되었습니다. 다시 확인하세요."); }
    public static ApiException invalid() { return new ApiException(ErrorCode.VALIDATION_FAILED,"요청 값이 올바르지 않습니다."); }
}
