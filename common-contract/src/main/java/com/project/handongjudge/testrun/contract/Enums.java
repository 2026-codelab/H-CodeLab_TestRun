package com.project.handongjudge.testrun.contract;

/** 계약에서 쓰는 작은 열거형 모음. */
public final class Enums {

    private Enums() {
    }

    /** 테스트하기가 어디서 실행됐는지 (통계 구분용). */
    public enum ContextType {
        ASSIGNMENT, QUIZ
    }

    /** NORMAL: 학생 테스트하기. VERIFY: 일치성 검증 (통계 제외). 둘 다 모든 테스트케이스를 실행한다. */
    public enum Mode {
        NORMAL, VERIFY
    }

    public enum JobStatus {
        QUEUED, DONE, ERROR, LOST
    }

    /** 샘플이 있으면 SAMPLE (입출력 공개), 없으면 SECRET (판정만 공개). runner가 결정한다. */
    public enum TestcaseSource {
        SAMPLE, SECRET
    }
}
