package org.acme.foodpackaging.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.acme.foodpackaging.domain.PackagingSchedule;
import org.acme.foodpackaging.persistence.PackagingScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("schedule")
public class PackagingScheduleResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackagingScheduleResource.class);
    private final ConcurrentMap<Long, JobSchedule> jobIdToJob = new ConcurrentHashMap<>();

    public static final Long SINGLETON_SOLUTION_ID = 1L;

    @Inject
    PackagingScheduleRepository repository;

    @Inject
    SolverManager<PackagingSchedule, Long> solverManager;
    SolutionManager<PackagingSchedule, HardMediumSoftLongScore> solutionManager;

    @Inject
    public PackagingScheduleResource(SolverManager<PackagingSchedule, Long> solverManager,
            SolutionManager<PackagingSchedule, HardMediumSoftLongScore> solutionManager) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    @GET
    public PackagingSchedule get() {
        // Get the solver status before loading the solution
        // to avoid the race condition that the solver terminates between them
        SolverStatus solverStatus = solverManager.getSolverStatus(SINGLETON_SOLUTION_ID);
        PackagingSchedule schedule = repository.read();
        schedule.setSolverStatus(solverStatus);
        return schedule;
    }

    @POST
    @Path("solve")
    public void solve() {
        solverManager.solveBuilder()
                .withProblemId(SINGLETON_SOLUTION_ID)
                .withProblemFinder(id -> repository.read())
                .withBestSolutionConsumer(schedule -> repository.write(schedule))
                .run();
    }

    @POST
    @Path("stopSolving")
    public void stopSolving() {
        solverManager.terminateEarly(SINGLETON_SOLUTION_ID);
    }

    ////
    ///
    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.TEXT_PLAIN)
    public Long solve(PackagingSchedule problem) {
        System.out.println("Testing");
        // String jobId = UUID.randomUUID().toString();
        Long jobId = 3L;
        System.out.println("******************");
        System.out.println(problem.getProducts().size());
        System.out.println("******************");
        jobIdToJob.put(jobId, JobSchedule.ofSchedule(problem));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(jobId_ -> jobIdToJob.get(jobId).schedule)
                .withBestSolutionConsumer(solution -> jobIdToJob.put(jobId, JobSchedule.ofSchedule(solution)))
                .withExceptionHandler((jobId_, exception) -> {
                    jobIdToJob.put(jobId, JobSchedule.ofException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    private record JobSchedule(PackagingSchedule schedule, Throwable exception) {

        static JobSchedule ofSchedule(PackagingSchedule schedule) {
            return new JobSchedule(schedule, null);
        }

        static JobSchedule ofException(Throwable error) {
            return new JobSchedule(null, error);
        }
    }

}
