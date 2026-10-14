package com.project.handongjudge.testrun.runner.judge;

import com.project.handongjudge.testrun.contract.EventType;
import com.project.handongjudge.testrun.contract.ResultSummary;
import java.util.Map;

/**
 * 작업 1건의 결과. 마지막 이벤트(complete/ce/error)와 저장용 요약을 함께 담는다.
 * 둘은 하나의 Redis 트랜잭션으로 같이 발행된다.
 */
public record JobOutcome(EventType terminalEvent, Map<String, Object> terminalData, ResultSummary summary) {
}
