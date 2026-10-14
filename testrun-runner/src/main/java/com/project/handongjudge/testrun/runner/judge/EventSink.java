package com.project.handongjudge.testrun.runner.judge;

import com.project.handongjudge.testrun.contract.EventType;
import java.util.Map;

/** 진행 중 이벤트(total, testcase)를 내보내는 곳. 마지막 이벤트는 {@link JobOutcome}으로 돌려준다. */
public interface EventSink {

    void emit(EventType type, Map<String, Object> data);
}
