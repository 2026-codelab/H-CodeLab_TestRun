package com.project.handongjudge.testrun.service.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** API 오류. code는 backend가 분기에 쓰는 고정 문자열이다 (예: NO_RUNNER면 DOMjudge 경로로 폴백). */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
