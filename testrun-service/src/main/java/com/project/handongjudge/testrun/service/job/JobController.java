package com.project.handongjudge.testrun.service.job;

import com.project.handongjudge.testrun.contract.CreateJobRequest;
import com.project.handongjudge.testrun.contract.CreateJobResponse;
import com.project.handongjudge.testrun.service.api.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/jobs")
public class JobController {

    private final JobAdmissionService admission;
    private final JobRepository jobs;

    public JobController(JobAdmissionService admission, JobRepository jobs) {
        this.admission = admission;
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<CreateJobResponse> create(@RequestBody CreateJobRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(admission.create(request));
    }

    /** 단건 조회 (디버깅·검증용). 코드는 반환하지 않는다. */
    @GetMapping("/{jobId}")
    public Map<String, Object> get(@PathVariable String jobId) {
        return jobs.findByJobId(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "job " + jobId));
    }
}
