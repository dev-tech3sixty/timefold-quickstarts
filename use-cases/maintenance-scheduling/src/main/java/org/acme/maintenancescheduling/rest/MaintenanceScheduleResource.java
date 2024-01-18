package org.acme.maintenancescheduling.rest;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.acme.maintenancescheduling.domain.Job;
import org.acme.maintenancescheduling.domain.MaintenanceSchedule;
import org.acme.maintenancescheduling.persistence.CrewRepository;
import org.acme.maintenancescheduling.persistence.JobRepository;
import org.acme.maintenancescheduling.persistence.WorkCalendarRepository;
import org.acme.maintenancescheduling.rest.exception.ScheduleSolverException;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.panache.common.Sort;

@Path("/schedule")
public class MaintenanceScheduleResource {

    public static final Long SINGLETON_SCHEDULE_ID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceScheduleResource.class);
    private final ConcurrentMap<Long, JobScheudle> jobIdToJob = new ConcurrentHashMap<>();

    @Inject
    WorkCalendarRepository workCalendarRepository;
    @Inject
    CrewRepository crewRepository;
    @Inject
    JobRepository jobRepository;

    @Inject
    SolverManager<MaintenanceSchedule, Long> solverManager;
    @Inject
    SolutionManager<MaintenanceSchedule, HardSoftScore> solutionManager;

    // To try, open http://localhost:8080/schedule
    @GET
    public MaintenanceSchedule getSchedule() {
        // Get the solver status before loading the solution
        // to avoid the race condition that the solver terminates between them
        SolverStatus solverStatus = getSolverStatus();
        MaintenanceSchedule solution = findById(SINGLETON_SCHEDULE_ID);
        solutionManager.update(solution); // Sets the score
        solution.setSolverStatus(solverStatus);
        return solution;
    }

    public SolverStatus getSolverStatus() {
        return solverManager.getSolverStatus(SINGLETON_SCHEDULE_ID);
    }

    @POST
    @Path("solve")
    public void solve() {
        solverManager.solveBuilder()
                .withProblemId(SINGLETON_SCHEDULE_ID)
                .withProblemFinder(this::findById)
                .withBestSolutionConsumer(this::save)
                .run();
    }

    @POST
    @Path("stopSolving")
    public void stopSolving() {
        solverManager.terminateEarly(SINGLETON_SCHEDULE_ID);
    }

    @Transactional
    protected MaintenanceSchedule findById(Long id) {
        if (!SINGLETON_SCHEDULE_ID.equals(id)) {
            throw new IllegalStateException("There is no schedule with id (" + id + ").");
        }
        return new MaintenanceSchedule(
                workCalendarRepository.listAll().get(0),
                crewRepository.listAll(Sort.by("name").and("id")),
                jobRepository.listAll(Sort.by("dueDate").and("readyDate").and("name").and("id")));
    }

    @Transactional
    protected void save(MaintenanceSchedule schedule) {
        for (Job job : schedule.getJobs()) {
            // TODO this is awfully naive: optimistic locking causes issues if called by the
            // SolverManager
            Job attachedJob = jobRepository.findById(job.getId());
            attachedJob.setCrew(job.getCrew());
            attachedJob.setStartDate(job.getStartDate());
            attachedJob.setEndDate(job.getEndDate());
        }
    }
    ///

    //////////////////

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.TEXT_PLAIN)
    public Long solve(MaintenanceSchedule problem) {
        // String jobId = UUID.randomUUID().toString();
        Long jobId = 5L;
        jobIdToJob.put(jobId, JobScheudle.ofSchedule(problem));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(jobId_ -> jobIdToJob.get(jobId).schedule)
                .withBestSolutionConsumer(solution -> jobIdToJob.put(jobId, JobScheudle.ofSchedule(solution)))
                .withExceptionHandler((jobId_, exception) -> {
                    jobIdToJob.put(jobId, JobScheudle.ofException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public MaintenanceSchedule getSchedule(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") Long jobId) {
        MaintenanceSchedule schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}/status")
    public SolverStatus getStatus(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") Long jobId) {
        MaintenanceSchedule schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule.getSolverStatus();
    }

    private MaintenanceSchedule getScheduleAndCheckForExceptions(Long jobId) {
        JobScheudle job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new ScheduleSolverException(jobId, Response.Status.NOT_FOUND, "No data found.");
        }
        if (job.exception != null) {
            throw new ScheduleSolverException(jobId, job.exception);
        }
        return job.schedule;
    }

    private record JobScheudle(MaintenanceSchedule schedule, Throwable exception) {

        static JobScheudle ofSchedule(MaintenanceSchedule schedule) {
            return new JobScheudle(schedule, null);
        }

        static JobScheudle ofException(Throwable error) {
            return new JobScheudle(null, error);
        }
    }
}
