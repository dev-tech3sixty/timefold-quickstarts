package org.acme.facilitylocation.rest;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import ai.timefold.solver.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;

import org.acme.facilitylocation.domain.Facility;
import org.acme.facilitylocation.domain.FacilityLocationProblem;
import org.acme.facilitylocation.persistence.FacilityLocationProblemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/flp")
public class SolverResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SolverResource.class);
    private static final long PROBLEM_ID = 0L;
    private final ConcurrentMap<Long, Job> jobIdToJob = new ConcurrentHashMap<>();

    private final AtomicReference<Throwable> solverError = new AtomicReference<>();

    private final FacilityLocationProblemRepository repository;
    private final SolverManager<FacilityLocationProblem, Long> solverManager;
    private final SolutionManager<FacilityLocationProblem, HardSoftLongScore> solutionManager;

    public SolverResource(FacilityLocationProblemRepository repository,
            SolverManager<FacilityLocationProblem, Long> solverManager,
            SolutionManager<FacilityLocationProblem, HardSoftLongScore> solutionManager) {
        this.repository = repository;
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    private Status statusFromSolution(FacilityLocationProblem solution) {
        return new Status(solution,
                solutionManager.explain(solution).getSummary(),
                solverManager.getSolverStatus(PROBLEM_ID));
    }

    @GET
    @Path("status")
    public Status status() {
        Optional.ofNullable(solverError.getAndSet(null)).ifPresent(throwable -> {
            throw new RuntimeException("Solver failed", throwable);
        });
        return statusFromSolution(repository.solution().orElse(FacilityLocationProblem.empty()));
    }

    @GET
    public Optional<FacilityLocationProblem> get() {
        Optional.ofNullable(solverError.getAndSet(null)).ifPresent(throwable -> {
            throw new RuntimeException("Solver failed", throwable);
        });
        return repository.solution();
    }

    @POST
    @Path("solve")
    public void solve() {
        Optional<FacilityLocationProblem> maybeSolution = repository.solution();
        maybeSolution.ifPresent(facilityLocationProblem -> solverManager.solveBuilder()
                .withProblemId(PROBLEM_ID)
                .withProblemFinder(id -> facilityLocationProblem)
                .withBestSolutionConsumer(repository::update)
                .withExceptionHandler((problemId, throwable) -> solverError.set(throwable))
                .run());
    }

    @POST
    @Path("stopSolving")
    public void stopSolving() {
        solverManager.terminateEarly(PROBLEM_ID);
    }

    ///
    ////
    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.TEXT_PLAIN)
    public Long solve(FacilityLocationProblem problem) {
        FacilityLocationProblem maybeSolution = problem;
        // String jobId = UUID.randomUUID().toString();
        Long jobId = 5L;
        jobIdToJob.put(jobId, Job.ofSchedule(maybeSolution));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(jobId_ -> jobIdToJob.get(jobId).schedule)
                .withBestSolutionConsumer(solution -> jobIdToJob.put(jobId, Job.ofSchedule(solution)))
                .withExceptionHandler((jobId_, exception) -> {
                    jobIdToJob.put(jobId, Job.ofException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    private record Job(FacilityLocationProblem schedule, Throwable exception) {

        static Job ofSchedule(FacilityLocationProblem schedule) {
            return new Job(schedule, null);
        }

        static Job ofException(Throwable error) {
            return new Job(null, error);
        }
    }

}
