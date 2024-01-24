package org.acme.orderpicking.rest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.orderpicking.domain.OrderPickingPlanning;
import org.acme.orderpicking.domain.OrderPickingSolution;
import org.acme.orderpicking.persistence.OrderPickingRepository;
import org.acme.orderpicking.rest.exception.ScheduleSolverException;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("orderPicking")
@ApplicationScoped
public class OrderPickingSolverResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderPickingSolverResource.class);

    private static final long PROBLEM_ID = 1;
    private final ConcurrentMap<Long, Job> jobIdToJob = new ConcurrentHashMap<>();

    private final AtomicBoolean solverWasNeverStarted = new AtomicBoolean(true);
    SolutionManager<OrderPickingSolution, HardSoftScore> solutionManager;

    @Inject
    SolverManager<OrderPickingSolution, Long> solverManager;

    @Inject
    public OrderPickingSolverResource(SolverManager<OrderPickingSolution, Long> solverManager,
            SolutionManager<OrderPickingSolution, HardSoftScore> solutionManager) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    @Inject
    OrderPickingRepository orderPickingRepository;

    @GET
    public OrderPickingPlanning getBestSolution() {
        OrderPickingSolution solution = orderPickingRepository.find();
        SolverStatus solverStatus = solverManager.getSolverStatus(PROBLEM_ID);
        return new OrderPickingPlanning(solverStatus, solution, solverWasNeverStarted.get());
    }

    @POST
    @Path("solve")
    public void solve() {
        solverWasNeverStarted.set(false);
        solverManager.solveBuilder()
                .withProblemId(PROBLEM_ID)
                .withProblemFinder((problemId) -> orderPickingRepository.find())
                .withBestSolutionConsumer(orderPickingRepository::save)
                .run();
    }

    @POST
    @Path("stopSolving")
    public void stopSolving() {
        solverManager.terminateEarly(PROBLEM_ID);
    }

    ///
    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.TEXT_PLAIN)
    public Long solve(OrderPickingSolution problem) {
        // String jobId = UUID.randomUUID().toString();
        Long jobId = 3L;
        jobIdToJob.put(jobId, Job.ofSchedule(problem));
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

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public OrderPickingSolution getSchedule(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") Long jobId) {
        OrderPickingSolution schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}/status")
    public SolverStatus getStatus(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") Long jobId) {
        OrderPickingSolution schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule.getSolverStatus();
    }

    private OrderPickingSolution getScheduleAndCheckForExceptions(Long jobId) {
        Job job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new ScheduleSolverException(jobId, Response.Status.NOT_FOUND, "No data found.");
        }
        if (job.exception != null) {
            throw new ScheduleSolverException(jobId, job.exception);
        }
        return job.schedule;
    }

    private record Job(OrderPickingSolution schedule, Throwable exception) {

        static Job ofSchedule(OrderPickingSolution schedule) {
            return new Job(schedule, null);
        }

        static Job ofException(Throwable error) {
            return new Job(null, error);
        }
    }

}