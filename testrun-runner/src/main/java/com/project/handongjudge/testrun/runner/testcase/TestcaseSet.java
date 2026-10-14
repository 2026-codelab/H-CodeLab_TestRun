package com.project.handongjudge.testrun.runner.testcase;

import com.project.handongjudge.testrun.contract.Enums;
import java.nio.file.Path;
import java.util.List;

/** 실행할 테스트케이스 묶음. 샘플이 있으면 샘플, 없으면 secret (설계 문서 5장). */
public record TestcaseSet(String hash, Enums.TestcaseSource source, List<Testcase> testcases) {

    public record Testcase(int rank, Path input, Path answer) {
    }
}
